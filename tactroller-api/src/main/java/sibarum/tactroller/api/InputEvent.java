package sibarum.tactroller.api;

/**
 * A single input event, delivered identically on every host OS.
 *
 * <p>Events are produced by the shared event loop in this API module, not by the platform
 * backends: the loop samples a backend's state and diffs consecutive snapshots to derive
 * presses, releases and movement. Because that derivation lives in one place, the event stream
 * has the same semantics regardless of which native backend is underneath.
 *
 * <p>Switch over the permitted subtypes to handle events:
 * <pre>{@code
 * switch (event) {
 *     case InputEvent.KeyPressed k     -> ...
 *     case InputEvent.KeyReleased k    -> ...
 *     case InputEvent.PointerMoved m   -> ...
 *     case InputEvent.ButtonPressed b  -> ...
 *     case InputEvent.ButtonReleased b -> ...
 * }
 * }</pre>
 */
public sealed interface InputEvent {

    /**
     * Monotonic capture time from {@link System#nanoTime()}. Meaningful only for computing
     * deltas between events, not as a wall-clock instant.
     */
    long timestampNanos();

    /** A key transitioned from up to down. */
    record KeyPressed(Key key, long timestampNanos) implements InputEvent {}

    /** A key transitioned from down to up. */
    record KeyReleased(Key key, long timestampNanos) implements InputEvent {}

    /**
     * The pointer changed position.
     *
     * @param x  new absolute position, virtual-screen pixels
     * @param y  new absolute position, virtual-screen pixels
     * @param dx change from the previous sample
     * @param dy change from the previous sample
     */
    record PointerMoved(int x, int y, int dx, int dy, long timestampNanos) implements InputEvent {}

    /** A mouse button transitioned from up to down, with the pointer position at that moment. */
    record ButtonPressed(MouseButton button, int x, int y, long timestampNanos) implements InputEvent {}

    /** A mouse button transitioned from down to up, with the pointer position at that moment. */
    record ButtonReleased(MouseButton button, int x, int y, long timestampNanos) implements InputEvent {}
}
