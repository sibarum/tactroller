package sibarum.tactroller.api;

/**
 * The frame of reference for pointer coordinates reported by polls and events.
 */
public enum CoordinateSpace {

    /** Virtual-screen pixels; origin is platform-defined (typically the primary display's top-left). */
    SCREEN,

    /**
     * Pixels relative to the attached window's client area, in OS <em>logical</em> units
     * (top-left origin). Requires a window attached via {@link Tactroller#attach(NativeWindow)}.
     */
    CLIENT,

    /**
     * Pixels relative to the attached window's client area, scaled by {@link Tactroller#contentScale()}
     * into <em>physical framebuffer</em> units. This is the space GPU/SDF hit-testing works in on
     * HiDPI displays: on a 150% display a CLIENT coordinate of 100 maps to a FRAMEBUFFER coordinate
     * of 150. Requires an attached window.
     */
    FRAMEBUFFER
}
