package sibarum.tactroller.api;

/**
 * The output of {@link DragGesture}: three events that exist alongside the raw button events, never
 * instead of them. {@link InputEvent.ButtonPressed}, {@link InputEvent.ButtonReleased} and whatever a
 * consumer derives from them are untouched — a drag is an <em>additional</em> reading of the same
 * physical gesture, so existing click handling keeps working unchanged and does not need to learn about
 * drags to stay correct.
 *
 * <p>Every drag event carries the pointer position two ways, and which one a consumer wants differs:
 *
 * <ul>
 *   <li>{@code x}/{@code y} — the pointer's current absolute position, in whatever
 *       {@link CoordinateSpace} the snapshot used. Use it to hit-test a drop target.</li>
 *   <li>{@code offsetX}/{@code offsetY} — motion accumulated since the press. Use it to move the thing
 *       being dragged. This is the one that stays correct under {@link PointerLockMode#RAW}, where the
 *       absolute position is pinned and only deltas move.</li>
 * </ul>
 *
 * <p>The two agree while unlocked, because an unlocked frame derives its motion by differencing
 * consecutive absolute positions.
 */
public sealed interface DragEvent {

    /** The button carrying the gesture. */
    MouseButton button();

    /** Pointer x when the button went down, in the active coordinate space. */
    int startX();

    /** Pointer y when the button went down, in the active coordinate space. */
    int startY();

    /** Pointer x at this moment, in the active coordinate space. */
    int x();

    /** Pointer y at this moment, in the active coordinate space. */
    int y();

    /** Motion accumulated since the press, horizontal. */
    int offsetX();

    /** Motion accumulated since the press, vertical. */
    int offsetY();

    long timestampNanos();

    /**
     * A drag is now in flight: the pointer travelled past the distance threshold while {@code button}
     * was held, and the hold time has elapsed. Emitted exactly once per gesture. This is where a
     * consumer decides what is being dragged and, for a drag-and-drop, builds the payload.
     *
     * <p>{@code offsetX}/{@code offsetY} are already at least the distance threshold, never zero.
     */
    record DragStarted(MouseButton button, int startX, int startY, int x, int y,
                       int offsetX, int offsetY, long timestampNanos) implements DragEvent {}

    /**
     * The pointer moved with a drag in flight. Arrives even once the pointer has left the window, since
     * the gesture is owned by the window that saw the press.
     *
     * @param dx this move's horizontal delta, as distinct from the accumulated {@code offsetX}
     * @param dy this move's vertical delta
     */
    record DragOver(MouseButton button, int startX, int startY, int x, int y,
                    int offsetX, int offsetY, int dx, int dy, long timestampNanos) implements DragEvent {}

    /**
     * The drag ended. Exactly one of these follows every {@link DragStarted}, so a consumer has one
     * place to tear down whatever the start set up.
     *
     * <p><b>Check {@code cancelled} before acting on the position.</b> When it is false the button was
     * released and {@code x}/{@code y} is the drop point — hit-test it against drop targets. When it is
     * true the user pressed Escape and there is no drop: revert. Treating a cancel as a drop is the one
     * way to lose data here, and it is why the flag is worth reading even though the two cases share a
     * record.
     *
     * <p>A cancel is terminal: the button's eventual release produces nothing further.
     */
    record DragEnded(MouseButton button, int startX, int startY, int x, int y,
                     int offsetX, int offsetY, boolean cancelled, long timestampNanos)
            implements DragEvent {}
}
