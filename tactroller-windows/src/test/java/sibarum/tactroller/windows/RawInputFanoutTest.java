package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import sibarum.tactroller.api.ScrollDelta;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the process-scoped RawInput channel against the "second backend steals the first's input" bug.
 *
 * <p>Windows delivers {@code WM_INPUT} for a usage pair to the <b>single most recently registered</b> target
 * window in the process. A backend that registers per instance therefore silences every earlier instance's
 * wheel and typed text the moment a second window opens — with no error anywhere, because the OS considers
 * the later registration perfectly legal. {@link WindowsInputBackend} answers this by running one shared
 * pump and fanning what arrives into every live instance; these tests hold that invariant down.
 *
 * <p>Real input is injected with {@code SendInput}, so this exercises the actual OS delivery path rather
 * than a stub. That injection is <b>desktop-wide</b>: it goes to whatever window has focus, not to this
 * process, so an ordinary build must not run it -- a stray wheel notch (or worse, a stray chord) lands in
 * whatever the developer is using. Hence opt-in via {@code -Dtactroller.injectInput=true}, ideally on an
 * idle machine or CI box. Run it after touching the hub, the pump, or the fan-out.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "tactroller.injectInput", matches = "true",
        disabledReason = "injects real input into the desktop; opt in with -Dtactroller.injectInput=true")
class RawInputFanoutTest {

    private static final int INPUT_MOUSE = 0;
    private static final int MOUSEEVENTF_WHEEL = 0x0800;
    private static final int INPUT_STRUCT_BYTES = 40;   // x64: type@0 + padding, MOUSEINPUT@8
    private static final int WHEEL_DELTA = 120;

    /** Give the pump thread time to receive and fan out the injected message before draining. */
    private static final long PUMP_SETTLE_MS = 400;

    @Test
    void everyLiveBackendReceivesTheWheel() throws Throwable {
        WindowsInputBackend first = new WindowsInputBackend();
        WindowsInputBackend second = new WindowsInputBackend();
        try {
            first.initialize();
            second.initialize();      // the registration that used to steal the wheel from `first`
            drainBoth(first, second); // clear anything accumulated during setup

            sendWheel(WHEEL_DELTA);
            Thread.sleep(PUMP_SETTLE_MS);

            ScrollDelta a = first.drainScroll();
            ScrollDelta b = second.drainScroll();
            assertNotEquals(0.0, a.y(), "the first backend lost the wheel — RawInput registration was stolen");
            assertNotEquals(0.0, b.y(), "the second backend did not receive the wheel");
        } finally {
            second.close();
            first.close();
        }
    }

    /**
     * Closing the pump owner must hand the pump to a survivor. Without the transfer the process goes
     * permanently deaf to wheel and typed text as soon as the first-opened window closes.
     */
    @Test
    void closingThePumpOwnerTransfersOwnershipToASurvivor() throws Throwable {
        WindowsInputBackend owner = new WindowsInputBackend();
        WindowsInputBackend survivor = new WindowsInputBackend();
        try {
            owner.initialize();       // becomes the pump owner (first to initialize)
            survivor.initialize();
            owner.close();            // ownership must transfer to `survivor`
            survivor.drainScroll();   // clear

            sendWheel(WHEEL_DELTA);
            Thread.sleep(PUMP_SETTLE_MS);

            assertNotEquals(0.0, survivor.drainScroll().y(),
                    "the surviving backend lost the wheel — pump ownership did not transfer on close");
        } finally {
            survivor.close();
        }
    }

    /** A windowless backend reports both routing gates open, so single-window setups are unaffected. */
    @Test
    void windowlessBackendPassesBothRoutingGates() throws Exception {
        WindowsInputBackend backend = new WindowsInputBackend();
        try {
            backend.initialize();
            assertTrue(backend.isFocused(), "an unattached backend must not gate focal events");
            assertTrue(backend.isPointerTarget(), "an unattached backend must not gate positional events");
        } finally {
            backend.close();
        }
    }

    private static void drainBoth(WindowsInputBackend a, WindowsInputBackend b) {
        a.drainScroll();
        b.drainScroll();
    }

    @SuppressWarnings("restricted")
    private static void sendWheel(int delta) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
        MethodHandle sendInput = linker.downcallHandle(user32.findOrThrow("SendInput"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(INPUT_STRUCT_BYTES);
            input.set(JAVA_INT, 0, INPUT_MOUSE);
            input.set(JAVA_INT, 16, delta);              // MOUSEINPUT.mouseData
            input.set(JAVA_INT, 20, MOUSEEVENTF_WHEEL);  // MOUSEINPUT.dwFlags
            int sent = (int) sendInput.invokeExact(1, input, INPUT_STRUCT_BYTES);
            if (sent != 1) {
                throw new IllegalStateException("SendInput did not inject the wheel event");
            }
        }
    }
}
