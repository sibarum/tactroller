package sibarum.tactroller.api;

/**
 * Relative pointer motion accumulated since the previous read, in pixels. Produced by pointer-lock
 * polling ({@link Tactroller#pollPointerDelta()}) and by the event loop when locked.
 *
 * @param dx horizontal motion (right positive)
 * @param dy vertical motion (down positive)
 */
public record PointerDelta(int dx, int dy) {

    public static final PointerDelta ZERO = new PointerDelta(0, 0);

    public boolean isZero() {
        return dx == 0 && dy == 0;
    }
}
