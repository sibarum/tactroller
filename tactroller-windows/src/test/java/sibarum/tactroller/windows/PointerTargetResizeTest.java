package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.PointerTarget;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Over a resize grip a window draws, the pointer is the window's and the press is the window manager's
 * ({@link PointerTarget#WINDOW_MANAGER}). A window that draws its own frame answers {@code WM_NCHITTEST} with a resize
 * code inside its client area, and the backend asks it: so a scratch window is put under the current cursor (without
 * moving it, and without taking activation) and its window procedure is made to answer what a grip would.
 */
@EnabledOnOs(OS.WINDOWS)
class PointerTargetResizeTest {

    private static final int WS_POPUP = 0x80000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_TOPMOST = 0x00000008;
    private static final int SIZE = 200;
    private static final int GWLP_WNDPROC = -4;
    private static final int WM_NCHITTEST = 0x0084;
    private static final long HTCLIENT = 1;
    private static final long HTBOTTOMRIGHT = 17;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());

    /** What the subclassed window answers {@code WM_NCHITTEST} with. */
    private static volatile long answer = HTCLIENT;
    private static volatile MemorySegment original = MemorySegment.NULL;
    private static MethodHandle callWindowProc;

    @Test
    @SuppressWarnings("restricted")
    void overAGripThePointerIsTheWindowsAndThePressIsNot() throws Throwable {
        int[] cursor = cursorPos();
        long hwnd = createWindow(cursor[0] - SIZE / 2, cursor[1] - SIZE / 2);
        assumeTrue(hwnd != 0L, "could not create a test window (no interactive desktop?)");
        subclass(hwnd);
        WindowsInputBackend backend = new WindowsInputBackend();
        try {
            backend.initialize();
            backend.attach(NativeWindow.ofHwnd(hwnd));
            assumeTrue(backend.pointerTarget() != PointerTarget.ELSEWHERE,
                    "something else is over the cursor; the window under test is not the topmost there");

            answer = HTCLIENT;
            assertEquals(PointerTarget.CONTENT, backend.pointerTarget(), "content is content");
            assertTrue(backend.isPointerTarget());

            answer = HTBOTTOMRIGHT;
            assertEquals(PointerTarget.WINDOW_MANAGER, backend.pointerTarget(),
                    "a grip is over the window, so a drawn grip can see the pointer");
            assertFalse(backend.isPointerTarget(), "and its press is the window manager's");
        } finally {
            backend.close();
            destroyWindow(hwnd);
        }
    }

    /** The subclassed window procedure: {@link #answer} for a hit test, the window's own for everything else. */
    @SuppressWarnings("unused")
    private static long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        if (msg == WM_NCHITTEST) {
            return answer;
        }
        try {
            return (long) callWindowProc.invokeExact(original, hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            return 0L;   // an upcall must not throw; a message the button's own procedure failed on is unhandled
        }
    }

    @SuppressWarnings("restricted")
    private static void subclass(long hwnd) throws Throwable {
        FunctionDescriptor proc = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
        callWindowProc = LINKER.downcallHandle(USER32.findOrThrow("CallWindowProcW"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
        MethodHandle target = MethodHandles.lookup().findStatic(PointerTargetResizeTest.class, "wndProc",
                MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
        MemorySegment stub = LINKER.upcallStub(target, proc, Arena.global());
        MethodHandle setLong = LINKER.downcallHandle(USER32.findOrThrow("SetWindowLongPtrW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        original = (MemorySegment) setLong.invokeExact(MemorySegment.ofAddress(hwnd), GWLP_WNDPROC, stub);
    }

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

    /** As {@code PointerTargetGateTest}'s: a {@code BUTTON}, which does not report itself transparent to hit tests. */
    @SuppressWarnings("restricted")
    private static long createWindow(int x, int y) throws Throwable {
        MethodHandle create = LINKER.downcallHandle(USER32.findOrThrow("CreateWindowExW"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hwnd = (MemorySegment) create.invokeExact(
                    WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST, wide(a, "BUTTON"),
                    wide(a, "tactroller-pointer-target-resize-test"), WS_POPUP | WS_VISIBLE,
                    x, y, SIZE, SIZE,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            return hwnd.address();
        }
    }

    @SuppressWarnings("restricted")
    private static void destroyWindow(long hwnd) throws Throwable {
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
