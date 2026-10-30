package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What the hub registers raw input for, read back from Windows ({@code GetRegisteredRawInputDevices}), so no real input
 * is injected and nothing on the desktop is touched. Raw input is delivered for the whole desktop, so a registration
 * nobody needs costs the process a wake and a read for every mouse move and keystroke the person makes in other
 * programs ({@link WindowsRawInputHub#reconsiderNow}).
 *
 * <p>The windows here are never shown: one is never in front, and the other is cloaked by this process, as a VexelRay
 * background run's windows are.
 */
@EnabledOnOs(OS.WINDOWS)
class RawInputRegistrationTest {

    private static final int MOUSE = 0x0102;      // generic desktop / mouse
    private static final int KEYBOARD = 0x0106;   // generic desktop / keyboard
    private static final int DWMWA_CLOAK = 13;
    private static final long SETTLE_MS = 2_000;

    @Test
    void aWindowNobodyCanReachHearsNoDeviceAndAWindowInTheBackgroundHearsOnlyTheMouse() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            Native n = new Native(arena);
            MemorySegment hidden = n.window();
            MemorySegment cloaked = n.window();
            n.cloak(cloaked);
            WindowsInputBackend backend = new WindowsInputBackend();
            try {
                backend.initialize();

                backend.attach(backend.window(cloaked.address()));
                assertEquals(Set.of(), awaitUsages(n, Set::isEmpty),
                        "a cloaked window hears no real device, so nothing should be registered");
                assertEquals(Set.of(), n.registered(), "nor the touchpad");

                backend.detach();
                backend.attach(backend.window(hidden.address()));
                assertEquals(Set.of(MOUSE), awaitUsages(n, u -> u.equals(Set.of(MOUSE))),
                        "a window not in front keeps the mouse (its activating click, the wheel over it) and not the"
                                + " keyboard, whose presses it would drop");

                backend.detach();
                assertEquals(Set.of(MOUSE, KEYBOARD), awaitUsages(n, u -> u.contains(KEYBOARD)),
                        "a backend with no window hears both, as before");
            } finally {
                backend.close();
                n.destroy(hidden);
                n.destroy(cloaked);
            }
        }
    }

    /**
     * The registered mouse and keyboard usages once {@code done} holds of them, or as they are after {@link #SETTLE_MS}.
     * A touchpad, where there is one, is registered with the mouse, and left out here.
     */
    private static Set<Integer> awaitUsages(Native n, java.util.function.Predicate<Set<Integer>> done) throws Throwable {
        long until = System.currentTimeMillis() + SETTLE_MS;
        Set<Integer> usages = mouseAndKeyboard(n);
        while (!done.test(usages) && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            usages = mouseAndKeyboard(n);
        }
        return usages;
    }

    private static Set<Integer> mouseAndKeyboard(Native n) throws Throwable {
        Set<Integer> usages = n.registered();
        usages.retainAll(Set.of(MOUSE, KEYBOARD));
        return usages;
    }

    /** The few user32 and dwmapi calls the test needs. */
    private static final class Native {
        private final Arena arena;
        private final MethodHandle createWindow;
        private final MethodHandle destroyWindow;
        private final MethodHandle registered;
        private final MethodHandle setAttribute;

        @SuppressWarnings("restricted")
        Native(Arena arena) {
            this.arena = arena;
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", arena);
            createWindow = linker.downcallHandle(user32.findOrThrow("CreateWindowExW"), FunctionDescriptor.of(ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            destroyWindow = linker.downcallHandle(user32.findOrThrow("DestroyWindow"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            registered = linker.downcallHandle(user32.findOrThrow("GetRegisteredRawInputDevices"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            setAttribute = linker.downcallHandle(dwmapi.findOrThrow("DwmSetWindowAttribute"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        }

        /** A top-level window of the built-in STATIC class, never shown. */
        MemorySegment window() throws Throwable {
            MemorySegment hwnd = (MemorySegment) createWindow.invokeExact(0, wide("STATIC"), wide("tactroller test"),
                    0, 0, 0, 100, 100, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            assertNotEquals(MemorySegment.NULL, hwnd, "CreateWindowExW failed");
            return hwnd;
        }

        void cloak(MemorySegment hwnd) throws Throwable {
            MemorySegment on = arena.allocate(JAVA_INT);
            on.set(JAVA_INT, 0, 1);
            assertEquals(0, (int) setAttribute.invokeExact(hwnd, DWMWA_CLOAK, on, 4), "DwmSetWindowAttribute failed");
        }

        void destroy(MemorySegment hwnd) throws Throwable {
            int ignored = (int) destroyWindow.invokeExact(hwnd);
        }

        /** Each registered RAWINPUTDEVICE as usage page << 8 | usage. */
        Set<Integer> registered() throws Throwable {
            MemorySegment count = arena.allocate(JAVA_INT);
            int ignored = (int) registered.invokeExact(MemorySegment.NULL, count, 16);
            int n = count.get(JAVA_INT, 0);
            Set<Integer> usages = new HashSet<>();
            if (n == 0) {
                return usages;
            }
            MemorySegment devices = arena.allocate(16L * n, 8);
            int got = (int) registered.invokeExact(devices, count, 16);
            for (int i = 0; i < got; i++) {
                int page = devices.get(JAVA_SHORT, 16L * i) & 0xFFFF;
                int usage = devices.get(JAVA_SHORT, 16L * i + 2) & 0xFFFF;
                usages.add(page << 8 | usage);
            }
            return usages;
        }

        private MemorySegment wide(String s) {
            MemorySegment seg = arena.allocate((s.length() + 1) * 2L);
            for (int i = 0; i < s.length(); i++) {
                seg.set(JAVA_SHORT, i * 2L, (short) s.charAt(i));
            }
            return seg;
        }
    }
}
