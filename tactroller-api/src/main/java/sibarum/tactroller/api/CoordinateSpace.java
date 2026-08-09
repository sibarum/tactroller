package sibarum.tactroller.api;

/**
 * The frame of reference for pointer coordinates reported by polls and events.
 */
public enum CoordinateSpace {

    /** Virtual-screen pixels; origin is platform-defined (typically the primary display's top-left). */
    SCREEN,

    /**
     * Pixels relative to the attached window's client area (top-left origin). Requires a window to
     * be attached via {@link Tactroller#attach(NativeWindow)}; using it with no window attached is
     * an error.
     */
    CLIENT
}
