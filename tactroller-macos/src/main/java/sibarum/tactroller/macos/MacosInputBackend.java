package sibarum.tactroller.macos;

import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.InputBackend;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.PointerState;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * macOS {@link InputBackend} backed by the CoreGraphics framework via the Panama FFM API.
 *
 * <ul>
 *   <li>{@code CGEventRef CGEventCreate(CGEventSourceRef)} + {@code CGPoint CGEventGetLocation(CGEventRef)}
 *       — pointer location ({@code CGPoint} is two 64-bit doubles, returned by value).</li>
 *   <li>{@code bool CGEventSourceKeyState(int stateID, uint16 keycode)} — key state.</li>
 *   <li>{@code bool CGEventSourceButtonState(int stateID, uint32 button)} — mouse button state.</li>
 *   <li>{@code void CFRelease(CFTypeRef)} — release the transient event.</li>
 * </ul>
 *
 * CoreGraphics and CoreFoundation are system frameworks, resolved by absolute path; nothing is bundled.
 * Note that querying input state on macOS requires the process to hold Accessibility (Input Monitoring)
 * permission, which the OS grants per-app.
 */
public final class MacosInputBackend implements InputBackend {

    private static final String CORE_GRAPHICS =
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";
    private static final String CORE_FOUNDATION =
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    /** CGPoint { CGFloat x; CGFloat y; } — CGFloat is 64-bit on all supported (64-bit) Macs. */
    private static final MemoryLayout CGPOINT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"),
            ValueLayout.JAVA_DOUBLE.withName("y"));

    /** kCGEventSourceStateCombinedSessionState. */
    private static final int COMBINED_SESSION_STATE = 0;

    private Arena arena;
    /**
     * Reusable 16-byte scratch buffer for CGEventGetLocation's by-value CGPoint return.
     * Allocated once from {@link #arena}; the returned struct is copied out immediately in
     * {@link #pollPointer()}, so reusing a single buffer avoids leaking 16 bytes per poll.
     * Safe because pollPointer is confined to a single polling thread.
     */
    private MemorySegment locBuffer;
    private MethodHandle cgEventCreate;
    private MethodHandle cgEventGetLocation;
    private MethodHandle cgEventSourceKeyState;
    private MethodHandle cgEventSourceButtonState;
    private MethodHandle cfRelease;

    private final Map<Key, Integer> keyCodeByKey = buildKeyMap();

    @Override
    public String name() {
        return "macos-coregraphics";
    }

    @Override
    @SuppressWarnings("restricted")
    public void initialize() throws BackendException {
        try {
            arena = Arena.ofShared();
            locBuffer = arena.allocate(CGPOINT);
            Linker linker = Linker.nativeLinker();
            SymbolLookup cg = SymbolLookup.libraryLookup(CORE_GRAPHICS, arena);
            SymbolLookup cf = SymbolLookup.libraryLookup(CORE_FOUNDATION, arena);

            cgEventCreate = linker.downcallHandle(
                    cg.findOrThrow("CGEventCreate"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            // Returns a CGPoint by value: the bound handle takes a SegmentAllocator first.
            cgEventGetLocation = linker.downcallHandle(
                    cg.findOrThrow("CGEventGetLocation"),
                    FunctionDescriptor.of(CGPOINT, ValueLayout.ADDRESS));
            cgEventSourceKeyState = linker.downcallHandle(
                    cg.findOrThrow("CGEventSourceKeyState"),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT));
            cgEventSourceButtonState = linker.downcallHandle(
                    cg.findOrThrow("CGEventSourceButtonState"),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            cfRelease = linker.downcallHandle(
                    cf.findOrThrow("CFRelease"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        } catch (RuntimeException e) {
            throw new BackendException("Failed to bind CoreGraphics/CoreFoundation", e);
        }
    }

    @Override
    public PointerState pollPointer() throws BackendException {
        try {
            MemorySegment event = (MemorySegment) cgEventCreate.invokeExact(MemorySegment.NULL);
            if (event.equals(MemorySegment.NULL)) {
                throw new BackendException("CGEventCreate returned NULL");
            }
            try {
                // Reuse a single scratch buffer for the by-value CGPoint return instead of the
                // growing shared arena, which would leak 16 bytes on every poll. The struct is
                // copied out into x/y immediately below, so it need not outlive this call.
                MemorySegment loc = (MemorySegment) cgEventGetLocation.invokeExact(
                        (java.lang.foreign.SegmentAllocator) (size, align) -> locBuffer, event);
                int x = (int) loc.get(ValueLayout.JAVA_DOUBLE, 0);
                int y = (int) loc.get(ValueLayout.JAVA_DOUBLE, 8);

                Set<MouseButton> buttons = EnumSet.noneOf(MouseButton.class);
                if (buttonDown(0)) buttons.add(MouseButton.LEFT);
                if (buttonDown(1)) buttons.add(MouseButton.RIGHT);
                if (buttonDown(2)) buttons.add(MouseButton.MIDDLE);

                return new PointerState(x, y, buttons);
            } finally {
                cfRelease.invokeExact(event);
            }
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Native pointer poll failed", t);
        }
    }

    @Override
    public boolean isKeyDown(Key key) throws BackendException {
        Integer code = keyCodeByKey.get(key);
        if (code == null) {
            return false; // unobservable key reported as not-down (keeps pollKeys robust)
        }
        try {
            return (boolean) cgEventSourceKeyState.invokeExact(COMBINED_SESSION_STATE, code.shortValue());
        } catch (Throwable t) {
            throw new BackendException("Native key poll failed for " + key, t);
        }
    }

    private boolean buttonDown(int button) throws Throwable {
        return (boolean) cgEventSourceButtonState.invokeExact(COMBINED_SESSION_STATE, button);
    }

    @Override
    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
            locBuffer = null;
        }
    }

    /** Carbon/HIToolbox virtual key codes (Events.h, kVK_*). */
    private static Map<Key, Integer> buildKeyMap() {
        Map<Key, Integer> m = new EnumMap<>(Key.class);
        m.put(Key.A, 0x00); m.put(Key.S, 0x01); m.put(Key.D, 0x02); m.put(Key.F, 0x03);
        m.put(Key.H, 0x04); m.put(Key.G, 0x05); m.put(Key.Z, 0x06); m.put(Key.X, 0x07);
        m.put(Key.C, 0x08); m.put(Key.V, 0x09); m.put(Key.B, 0x0B); m.put(Key.Q, 0x0C);
        m.put(Key.W, 0x0D); m.put(Key.E, 0x0E); m.put(Key.R, 0x0F); m.put(Key.Y, 0x10);
        m.put(Key.T, 0x11); m.put(Key.O, 0x1F); m.put(Key.U, 0x20); m.put(Key.I, 0x22);
        m.put(Key.P, 0x23); m.put(Key.L, 0x25); m.put(Key.J, 0x26); m.put(Key.K, 0x28);
        m.put(Key.N, 0x2D); m.put(Key.M, 0x2E);

        m.put(Key.DIGIT_1, 0x12); m.put(Key.DIGIT_2, 0x13); m.put(Key.DIGIT_3, 0x14);
        m.put(Key.DIGIT_4, 0x15); m.put(Key.DIGIT_6, 0x16); m.put(Key.DIGIT_5, 0x17);
        m.put(Key.DIGIT_9, 0x19); m.put(Key.DIGIT_7, 0x1A); m.put(Key.DIGIT_8, 0x1C);
        m.put(Key.DIGIT_0, 0x1D);

        m.put(Key.ENTER, 0x24); m.put(Key.TAB, 0x30); m.put(Key.SPACE, 0x31);
        m.put(Key.BACKSPACE, 0x33); m.put(Key.ESCAPE, 0x35); m.put(Key.DELETE, 0x75);
        m.put(Key.LEFT, 0x7B); m.put(Key.RIGHT, 0x7C); m.put(Key.DOWN, 0x7D); m.put(Key.UP, 0x7E);

        m.put(Key.LEFT_SHIFT, 0x38); m.put(Key.RIGHT_SHIFT, 0x3C);
        m.put(Key.LEFT_CONTROL, 0x3B); m.put(Key.RIGHT_CONTROL, 0x3E);
        m.put(Key.LEFT_ALT, 0x3A); m.put(Key.RIGHT_ALT, 0x3D);
        m.put(Key.LEFT_SUPER, 0x37); m.put(Key.RIGHT_SUPER, 0x36);
        return m;
    }
}
