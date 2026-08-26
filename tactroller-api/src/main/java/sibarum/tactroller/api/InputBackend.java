package sibarum.tactroller.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Service-provider interface implemented once per target OS. A backend is the thin, stateful
 * bridge between the platform's native input APIs (bound via Panama in the platform modules)
 * and Tactroller's OS-agnostic types.
 *
 * <p>Implementations are discovered at runtime through {@link java.util.ServiceLoader}; each
 * platform module ships a {@code META-INF/services/sibarum.tactroller.api.InputBackend} entry.
 * Exactly one backend is expected to be present on the classpath, selected at build time by the
 * OS-activated Maven profile that pulled in the matching platform module.
 *
 * <p>Backends are polling-based: {@link #pollPointer()} and {@link #isKeyDown(Key)} read the
 * current hardware state on demand rather than delivering an event stream. This keeps the SPI
 * trivial to bind natively and friendly to GraalVM native-image, where callback registration
 * across the FFM boundary is more involved.
 *
 * <p>Instances are not required to be thread-safe; callers should confine a backend to a single
 * polling thread unless the implementation documents otherwise.
 */
public interface InputBackend extends AutoCloseable {

    /**
     * A short, stable identifier for this backend, e.g. {@code "windows-user32"}. Useful for
     * logging and diagnostics; not parsed by the framework.
     */
    String name();

    /**
     * Acquire any native resources (open library handles, event nodes, sources). Called once by
     * {@link Tactroller} before the first poll. Implementations must tolerate being initialised
     * exactly once.
     *
     * @throws BackendException if the platform's input subsystem could not be reached
     */
    void initialize() throws BackendException;

    /**
     * @return a fresh snapshot of the pointer's position and pressed buttons.
     * @throws BackendException if the native query failed
     */
    PointerState pollPointer() throws BackendException;

    /**
     * @param key the logical key to test
     * @return {@code true} if the key is currently held down.
     * @throws BackendException if the native query failed
     */
    boolean isKeyDown(Key key) throws BackendException;

    /**
     * Test a single mouse button without allocating. The default reads {@link #pollPointer()} (which
     * allocates a {@link PointerState}); backends should override with a direct native query to keep
     * per-frame polling allocation-free.
     */
    default boolean isButtonDown(MouseButton button) throws BackendException {
        return pollPointer().isPressed(button);
    }

    /**
     * Snapshot every key currently held down in a single query. The default polls each key via
     * {@link #isKeyDown(Key)}; backends whose native API can report the whole keyboard at once
     * (e.g. X11's {@code XQueryKeymap}) should override this for efficiency and to keep every key
     * in the snapshot consistent to the same instant.
     *
     * <p>Used by the shared event loop to derive {@code KeyPressed}/{@code KeyReleased} events, so
     * the semantics of this method are identical across platforms by construction.
     *
     * @return the set of pressed keys; never {@code null}
     * @throws BackendException if the native query failed
     */
    default Set<Key> pollKeys() throws BackendException {
        EnumSet<Key> down = EnumSet.noneOf(Key.class);
        for (Key key : Key.values()) {
            if (isKeyDown(key)) {
                down.add(key);
            }
        }
        return down;
    }

    // ---- Window attachment, focus and coordinate conversion --------------
    //
    // Default implementations make these features opt-in: a backend that has not implemented window
    // integration behaves as a windowless, always-focused, screen-space device. The Windows backend
    // overrides them; macOS/Linux inherit the defaults until implemented.

    /**
     * Bind to a host-created window for focus gating and client-relative coordinates.
     *
     * @throws BackendException if the window kind is not supported by this backend
     * @throws UnsupportedOperationException if this backend has no window integration
     */
    default void attach(NativeWindow window) throws BackendException {
        throw new UnsupportedOperationException(name() + " does not support window attachment yet");
    }

    /** Release a previously attached window. No-op if none attached. */
    default void detach() {
        // no-op by default
    }

    /** @return whether a window is currently attached. */
    default boolean isWindowAttached() {
        return false;
    }

    /**
     * @return whether the attached window currently holds input focus. Windowless backends report
     *         {@code true} (nothing to gate against).
     *
     * <p><b>Routing scope:</b> this is the gate for <em>focal</em> channels — keys and typed characters,
     * which carry no position and so belong to whichever window has focus. See the channel-scope table in
     * the module README.
     */
    default boolean isFocused() {
        return true;
    }

    /**
     * @return whether this window is the pointer's <b>target</b>: the cursor lies within its client area
     *         <em>and</em> no window covers it there. Windowless backends report {@code true} (nothing to
     *         gate against).
     *
     * <p><b>Routing scope:</b> this is the gate for <em>positional</em> channels — wheel and pointer
     * events, which belong to the window under the cursor rather than the focused one. Several OS input
     * channels are process-wide, so a multi-window process receives every window's share of them on every
     * backend; without this gate a wheel notch scrolls two windows at once.
     *
     * <p><b>Occlusion is the backend's question, not a consumer's.</b> At most one window is the pointer's
     * target at any moment, and what decides it is the OS's stacking order — which nothing above this layer
     * can see: a GUI hit-tests its <em>own</em> tree, and no amount of hit-testing there reveals that
     * another window is drawn over it. A gate that only asks "is the cursor inside my rectangle?" therefore
     * opens on every window of an overlapping stack, and one wheel notch scrolls all of them. A backend
     * with a window attached must answer the whole question.
     */
    default boolean isPointerTarget() {
        return true;
    }

    /**
     * Convert a screen-space point to the attached window's client area (OS logical pixels).
     *
     * @return the converted point as {@code [clientX, clientY]}; identity if no window is attached
     */
    default int[] toClient(int screenX, int screenY) {
        return new int[] {screenX, screenY};
    }

    /**
     * @return the attached window's content scale (DPI / 96 on Windows): 1.0 at 100%, 1.5 at 150%.
     *         Used to convert {@code CLIENT} logical pixels to {@code FRAMEBUFFER} physical pixels.
     *         Returns 1.0 when no window is attached or the backend cannot query DPI.
     */
    default double contentScale() {
        return 1.0;
    }

    // ---- Pointer lock (relative motion / mouselook) ----------------------

    /** @return whether this backend can capture the pointer for relative motion. */
    default boolean supportsPointerLock() {
        return false;
    }

    /**
     * Capture the pointer in the given mode and hide the cursor. Idempotent; changing mode while
     * locked is allowed.
     *
     * @throws UnsupportedOperationException if {@link #supportsPointerLock()} is false
     */
    default void setPointerLock(PointerLockMode mode) throws BackendException {
        throw new UnsupportedOperationException(name() + " does not support pointer lock");
    }

    /**
     * Release the pointer and restore the cursor, to where it was when the lock was taken. No-op if not locked.
     *
     * <p>Restoring the position is part of the contract and not a nicety: a lock that lasts one gesture must be
     * invisible either side of it.
     */
    default void clearPointerLock() {
        // no-op by default
    }

    /** @return whether the pointer is currently locked. */
    default boolean isPointerLocked() {
        return false;
    }

    /**
     * Drain accumulated relative pointer motion since the last call, resetting the accumulator.
     * Meaningful while locked; returns {@link PointerDelta#ZERO} otherwise.
     */
    default PointerDelta drainPointerDelta() {
        return PointerDelta.ZERO;
    }

    // ---- Scroll ----------------------------------------------------------

    /**
     * Drain accumulated scroll-wheel motion since the last call, resetting the accumulator. A
     * backend that is not an event source (no wheel plumbing) always returns
     * {@link ScrollDelta#ZERO}.
     */
    default ScrollDelta drainScroll() {
        return ScrollDelta.ZERO;
    }

    // ---- Typed characters -------------------------------------------------

    /** Shared empty result so the common "nothing typed" path allocates nothing. */
    int[] NO_CHARS = new int[0];

    /**
     * Drain the Unicode code points typed since the last call, resetting the accumulator. These are
     * layout-/dead-key-resolved characters (from the platform's text path, e.g. Win32 {@code WM_CHAR}
     * or {@code ToUnicodeEx}), <b>not</b> key codes — see {@link InputEvent.CharTyped}. Order-preserving.
     *
     * <p>Like {@link #drainScroll()} this is an accumulator filled on the backend's native pump thread
     * and drained (via an atomic/locked swap) by the loop/poll thread, so it is safe to drain from a
     * different thread than the one filling it. A backend without a text path returns {@link #NO_CHARS}.
     */
    default int[] drainChars() {
        return NO_CHARS;
    }

    /** Release native resources. Idempotent. */
    @Override
    void close();
}
