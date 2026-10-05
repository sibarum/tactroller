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
 *     case InputEvent.Scrolled s       -> ...
 *     case InputEvent.CharTyped c      -> ...
 *     case InputEvent.FocusChanged f   -> ...
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
     * @param x  new absolute position, in the {@link CoordinateSpace} the {@link Tactroller} is set to
     *           (virtual-screen pixels unless it was changed)
     * @param y  new absolute position, in the same space as {@code x}
     * @param dx change from the previous sample
     * @param dy change from the previous sample
     */
    record PointerMoved(int x, int y, int dx, int dy, long timestampNanos) implements InputEvent {}

    /** A mouse button transitioned from up to down, with the pointer position at that moment. */
    record ButtonPressed(MouseButton button, int x, int y, long timestampNanos) implements InputEvent {}

    /** A mouse button transitioned from down to up, with the pointer position at that moment. */
    record ButtonReleased(MouseButton button, int x, int y, long timestampNanos) implements InputEvent {}

    /**
     * The scroll wheel moved.
     *
     * @param xOffset horizontal scroll in notches (right positive)
     * @param yOffset vertical scroll in notches (up/away positive, GLFW convention)
     * @param x       pointer position when the scroll occurred
     * @param y       pointer position when the scroll occurred
     */
    record Scrolled(double xOffset, double yOffset, int x, int y, long timestampNanos) implements InputEvent {}

    /**
     * A character was produced by the keyboard — the <em>text</em> channel, distinct from
     * {@link KeyPressed} (the command channel). Carries a Unicode {@code codepoint} already resolved
     * through the OS keyboard layout, dead keys and (later) IME composition, so consumers insert text
     * without re-deriving characters from key codes. Modifier-only shortcut chords (e.g. Ctrl+C) do
     * <em>not</em> produce a {@code CharTyped}; control characters and text edits keep their separate
     * lanes (the classic "Ctrl+C also inserts a character" bug is thereby impossible).
     *
     * @param codepoint the produced Unicode code point
     */
    record CharTyped(int codepoint, long timestampNanos) implements InputEvent {}

    /**
     * The attached window gained or lost input focus. Only emitted when a window is attached via
     * {@link Tactroller#attach(NativeWindow)}.
     *
     * @param focused {@code true} if the window is now focused
     */
    record FocusChanged(boolean focused, long timestampNanos) implements InputEvent {}
}
