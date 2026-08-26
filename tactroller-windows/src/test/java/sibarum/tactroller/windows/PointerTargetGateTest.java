package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import sibarum.tactroller.api.NativeWindow;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Holds down the positional routing gate's second half: <b>occlusion</b>.
 *
 * <p>Positional input (wheel, pointer, button presses) belongs to one window — the one the cursor is over.
 * A gate that asks only "is the cursor inside my client rect?" is right about geometry and wrong about the
 * desktop: every window of an overlapping stack contains the same point, so every one of them passed, and a
 * single wheel notch scrolled all of them at once. Nothing above the backend can correct that — a GUI
 * hit-tests its own tree and cannot see another window's pixels — so {@link WindowsInputBackend#isPointerTarget()}
 * has to consult the stacking order itself, and this test is what says it still does.
 *
 * <p>Two real overlapping windows are created under the current cursor position (briefly, and without
 * taking activation), because the stacking order is the thing under test and only the OS keeps one.
 */
@EnabledOnOs(OS.WINDOWS)
class PointerTargetGateTest {

    private static final int WS_POPUP = 0x80000000;
    private static final int WS_VISIBLE = 0x10000000;
    /** No activation (the developer's focus stays put) and no taskbar button for a test's scratch window. */
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    /** Topmost, so whatever the cursor happened to be over cannot sit above the pair under test. */
    private static final int WS_EX_TOPMOST = 0x00000008;
    private static final int SIZE = 200;

    @Test
    @SuppressWarnings("restricted")
    void aCoveredWindowIsNotThePointersTarget() throws Throwable {
        int[] cursor = cursorPos();
        long under = createWindow(cursor[0] - SIZE / 2, cursor[1] - SIZE / 2);
        long over = createWindow(cursor[0] - SIZE / 2, cursor[1] - SIZE / 2);   // created later, so drawn above
        assumeTrue(under != 0L && over != 0L, "could not create test windows (no interactive desktop?)");

        WindowsInputBackend below = new WindowsInputBackend();
        WindowsInputBackend above = new WindowsInputBackend();
        try {
            below.initialize();
            above.initialize();
            below.attach(NativeWindow.ofHwnd(under));
            above.attach(NativeWindow.ofHwnd(over));

            assertTrue(above.isPointerTarget(),
                    "the topmost window under the cursor is the pointer's target");
            assertFalse(below.isPointerTarget(),
                    "a covered window claimed the pointer — one wheel notch scrolls every overlapping window");

            // The control: the same window, same cursor, nothing on top of it any more. Without this, the
            // assertion above would also pass if the gate had closed for some unrelated reason.
            destroyWindow(over);
            over = 0L;
            assertTrue(below.isPointerTarget(),
                    "uncovering the window did not give it the pointer back — the gate is not reading z-order");
        } finally {
            above.close();
            below.close();
            destroyWindow(over);
            destroyWindow(under);
        }
    }

    /** A windowless backend has nothing to be covered by, so it stays the target. */
    @Test
    void aWindowlessBackendIsAlwaysThePointersTarget() throws Exception {
        WindowsInputBackend backend = new WindowsInputBackend();
        try {
            backend.initialize();
            assertTrue(backend.isPointerTarget(), "an unattached backend must not gate positional events");
        } finally {
            backend.close();
        }
    }

    // ---- Win32 scratch windows -------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());

    @SuppressWarnings("restricted")
    private static int[] cursorPos() throws Throwable {
        MethodHandle getCursorPos = LINKER.downcallHandle(USER32.findOrThrow("GetCursorPos"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pt = a.allocate(8);
            assumeTrue((int) getCursorPos.invokeExact(pt) != 0, "GetCursorPos failed");
            return new int[] {pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4)};
        }
    }

    /**
     * A visible top-level window at {@code (x, y)}. The class is {@code BUTTON} rather than the more obvious
     * {@code STATIC} on purpose: a static control reports itself transparent to hit-testing, which is exactly
     * the thing this test must not accidentally rely on.
     */
    @SuppressWarnings("restricted")
    private static long createWindow(int x, int y) throws Throwable {
        MethodHandle create = LINKER.downcallHandle(USER32.findOrThrow("CreateWindowExW"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cls = wide(a, "BUTTON");
            MemorySegment name = wide(a, "tactroller-pointer-target-test");
            MemorySegment hwnd = (MemorySegment) create.invokeExact(
                    WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST, cls, name, WS_POPUP | WS_VISIBLE,
                    x, y, SIZE, SIZE,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            return hwnd.address();
        }
    }

    @SuppressWarnings("restricted")
    private static void destroyWindow(long hwnd) throws Throwable {
        if (hwnd == 0L) {
            return;
        }
        MethodHandle destroy = LINKER.downcallHandle(USER32.findOrThrow("DestroyWindow"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        int ignored = (int) destroy.invokeExact(MemorySegment.ofAddress(hwnd));
    }

    private static MemorySegment wide(Arena arena, String s) {
        MemorySegment seg = arena.allocate((s.length() + 1) * 2L);
        for (int i = 0; i < s.length(); i++) {
            seg.set(JAVA_SHORT, i * 2L, (short) s.charAt(i));
        }
        seg.set(JAVA_SHORT, s.length() * 2L, (short) 0);
        return seg;
    }
}
