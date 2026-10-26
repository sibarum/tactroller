package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import sibarum.tactroller.api.InputEdge;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.NativeWindow;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Holds down the focal gate on queued key edges: a key pressed while another program has the foreground is that
 * program's, and is never queued for this window.
 *
 * <p>The keyboard is registered INPUTSINK, so the hub hears every keystroke on the desktop. Gating only when a frame
 * drains the queue asks whether the window is focused <em>then</em>, not when the key went down: an application that
 * parks while in the background drains nothing until it is looked at again, and the first frame after it is brought
 * forward replayed every chord typed into other programs in the meantime — Ctrl+W in a browser closed a tab here.
 *
 * <p>The window is real but hidden and never activated, so it is certain not to be the foreground window and the
 * developer's focus stays put. The reports are handed to the backend directly, as the hub's pump would.
 */
@EnabledOnOs(OS.WINDOWS)
class KeyFocusGateTest {

    private static final int VK_W = 0x57;

    @Test
    void aKeyPressedInAnotherProgramIsNotQueued() throws Throwable {
        long hwnd = createHiddenWindow();
        assumeTrue(hwnd != 0L, "could not create a test window (no interactive desktop?)");
        WindowsInputBackend backend = new WindowsInputBackend();
        try {
            backend.initialize();
            backend.attach(NativeWindow.ofHwnd(hwnd));
            assumeTrue(!backend.isFocused(), "the hidden test window is somehow the foreground window");
            backend.drainEdges();

            backend.acceptRawKey(VK_W, 0x11, false, true, 1L);
            assertTrue(backend.drainEdges().isEmpty(),
                    "a key pressed while another program has the foreground was queued for this window");

            // A release is still queued: it can only end a press this window was shown, and dropping it would leave
            // that key held here for good.
            backend.acceptRawKey(VK_W, 0x11, false, false, 2L);
            assertEquals(List.of(InputEdge.key(Key.W, false, 2L)), backend.drainEdges());
        } finally {
            backend.close();
            destroyWindow(hwnd);
        }
    }

    /** The control: with no window to be in front of, every key is this backend's, as it always was. */
    @Test
    void aWindowlessBackendQueuesEveryKey() throws Exception {
        WindowsInputBackend backend = new WindowsInputBackend();
        try {
            backend.initialize();
            backend.drainEdges();
            backend.acceptRawKey(VK_W, 0x11, false, true, 1L);
            List<InputEdge> edges = backend.drainEdges();
            assertFalse(edges.isEmpty(), "an unattached backend must not gate focal events");
            assertEquals(InputEdge.key(Key.W, true, 1L), edges.get(0));
        } finally {
            backend.close();
        }
    }

    // ---- Win32 scratch window --------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;

    /** A top-level window that is never shown, so it can never be the foreground window. */
    @SuppressWarnings("restricted")
    private static long createHiddenWindow() throws Throwable {
        MethodHandle create = LINKER.downcallHandle(USER32.findOrThrow("CreateWindowExW"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hwnd = (MemorySegment) create.invokeExact(
                    WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, wide(a, "BUTTON"), wide(a, "tactroller-key-focus-test"),
                    WS_POPUP, 0, 0, 100, 100,
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
