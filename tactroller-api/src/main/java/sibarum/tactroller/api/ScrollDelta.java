package sibarum.tactroller.api;

/**
 * Scroll-wheel motion accumulated since the previous read, in notches (one physical wheel detent =
 * 1.0). Fractional values occur on high-resolution / touchpad scrolling.
 *
 * @param x horizontal scroll (right positive)
 * @param y vertical scroll (up/away-from-user positive, matching GLFW's convention)
 */
public record ScrollDelta(double x, double y) {

    public static final ScrollDelta ZERO = new ScrollDelta(0.0, 0.0);

    public boolean isZero() {
        return x == 0.0 && y == 0.0;
    }
}
