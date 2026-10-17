package sibarum.tactroller.api;

/**
 * The output of {@link TouchpadGesture}: multi-finger touchpad gestures, recognized from raw contacts
 * ({@link InputEvent.TouchpadChanged}). Like {@link DragEvent} they exist alongside the events they are derived from,
 * never instead of them.
 *
 * <p>A continuous gesture is a {@link GestureStarted}, any number of {@link GestureUpdated}s and exactly one
 * {@link GestureEnded}, all with the same {@link Kind} and finger count. A {@link Tapped} stands alone.
 *
 * <p>Every event carries the pointer position {@code x}/{@code y} (in the active {@link CoordinateSpace}) at that
 * moment: a touchpad has no position on the screen, so this is where a gesture <em>happens</em> — the point to zoom
 * about, the view to pan. With two or more fingers down the OS does not move the pointer, so it holds still for the
 * length of a gesture.
 *
 * <p>Motion is in <b>millimetres on the touchpad's surface</b>, not pixels. How far a view moves per millimetre is a
 * sensitivity the consumer chooses; a physical unit is what keeps that choice the same across devices.
 */
public sealed interface GestureEvent {

    /** What the fingers are doing, decided once per gesture and then held, so a pan never jitters into a zoom. */
    enum Kind {
        /** The fingers move together: scroll, pan, swipe. Updates carry only translation. */
        PAN,
        /**
         * The fingers move relative to each other: spread, pinch, twist. Updates carry scale and rotation, and also
         * the translation of the fingers' centre, since a real pinch drifts and zooming about a moving point is
         * how the platform toolkits behave.
         */
        PINCH
    }

    /** How many fingers are on the surface for this gesture. */
    int fingers();

    /** Pointer x at this moment, in the active coordinate space. */
    int x();

    /** Pointer y at this moment, in the active coordinate space. */
    int y();

    long timestampNanos();

    /**
     * The fingers have moved far enough to be a gesture, and what kind has been decided. Emitted exactly once per
     * gesture; the motion that decided it is delivered in the {@link GestureUpdated} that follows immediately.
     */
    record GestureStarted(Kind kind, int fingers, int x, int y, long timestampNanos) implements GestureEvent {}

    /**
     * The fingers moved. All quantities are <b>since the previous event of this gesture</b>, so they compose: add up
     * {@code dx}/{@code dy}, multiply {@code scale} into a zoom factor, add {@code rotation} to an angle.
     *
     * @param dx       horizontal motion of the fingers' centre, in millimetres (right positive)
     * @param dy       vertical motion of the fingers' centre, in millimetres (towards the user positive, as screen y)
     * @param scale    change in the fingers' spread as a factor: above 1 spreading apart (zoom in), below 1 pinching
     *                 together. Always exactly 1 for {@link Kind#PAN}.
     * @param rotation change in the fingers' angle about their centre, in radians, clockwise positive as seen on the
     *                 screen. Always exactly 0 for {@link Kind#PAN}.
     */
    record GestureUpdated(Kind kind, int fingers, int x, int y, double dx, double dy, double scale, double rotation,
                          long timestampNanos) implements GestureEvent {}

    /**
     * The gesture is over. Exactly one of these follows every {@link GestureStarted}.
     *
     * <p>A gesture ends when the finger count changes, not only when every finger lifts: fingers rarely leave a
     * surface together, and a three-finger swipe that turned into a two-finger one as its fingers lifted would be
     * a gesture the user never made. So the first finger to lift ends it, and no new gesture begins until the
     * surface is clear. A finger <em>added</em> mid-gesture also ends it, and a gesture with the new count may follow.
     *
     * @param velocityX the fingers' centre velocity as they left, in millimetres per second; 0 if they had come to
     *                  rest first. This is what a consumer needs for kinetic scrolling or to tell a swipe from a slow
     *                  drag.
     * @param velocityY the vertical counterpart of {@code velocityX}
     */
    record GestureEnded(Kind kind, int fingers, int x, int y, double velocityX, double velocityY,
                        long timestampNanos) implements GestureEvent {}

    /**
     * Fingers touched down together and lifted again quickly, without moving and without clicking the pad.
     * {@code fingers} is the most that were down at once.
     */
    record Tapped(int fingers, int x, int y, long timestampNanos) implements GestureEvent {}
}
