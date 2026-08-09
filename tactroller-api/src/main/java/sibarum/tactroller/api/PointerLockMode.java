package sibarum.tactroller.api;

/**
 * How the pointer is captured while locked for relative-motion input (mouselook). Both modes hide
 * the OS cursor; they differ in how the delta is produced. Each has trade-offs, so the framework
 * exposes both rather than choosing for you.
 */
public enum PointerLockMode {

    /**
     * Hide the cursor and warp it back to a fixed center point on every drain; the delta is the
     * movement away from center since the last drain. Uses only ordinary cursor calls
     * ({@code GetCursorPos}/{@code SetCursorPos}), so it works without an OS raw-input stream and
     * keeps the cursor physically confined near the center.
     *
     * <p>Trade-off: the reported motion passes through the OS pointer-acceleration ("ballistics")
     * curve, and sub-pixel movement can be quantized, giving slight jitter.
     */
    RECENTER,

    /**
     * Hide the cursor and read raw device deltas straight from the OS input stack (Windows
     * RawInput / macOS associated-cursor deltas / X11 XInput2 raw events). No warping.
     *
     * <p>Trade-off: needs the platform raw-input plumbing (a message pump on Windows), but the
     * motion is unfiltered by pointer acceleration and never clips at screen edges — the higher
     * fidelity choice for first-person camera control.
     */
    RAW
}
