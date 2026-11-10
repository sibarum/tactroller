package sibarum.tactroller.api;

/**
 * What the pointer is over, as far as one window's <em>positional</em> input is concerned: the backend's whole answer
 * to "is this pointer event mine?", which has three outcomes rather than two.
 *
 * <p>The middle one is the reason this is not a boolean. A window that draws its own frame draws its resize controls
 * too, and those controls are the window manager's to drag but the window's to show: they light up under the pointer
 * like any other control. So over one, the pointer's position and motion belong to the window (or the control never
 * sees hover), while a press belongs to the window manager (or the press that starts a resize is also a click, and the
 * resize a drag, on whatever the GUI drew there). One flag could only drop both or deliver both.
 */
public enum PointerTarget {

    /** Over another window, or no window: nothing positional is this window's. */
    ELSEWHERE(false, false),

    /**
     * Over this window, on a part the window manager owns: a resize band, or a resize grip the window draws. Position,
     * motion and the wheel are the window's; a press is the window manager's.
     */
    WINDOW_MANAGER(true, false),

    /** Over this window's own content: everything positional is the window's. */
    CONTENT(true, true);

    private final boolean over;
    private final boolean presses;

    PointerTarget(boolean over, boolean presses) {
        this.over = over;
        this.presses = presses;
    }

    /** Whether the pointer is over this window, unoccluded: its position, motion and wheel are this window's. */
    public boolean over() {
        return over;
    }

    /** Whether a button pressed here is this window's to deliver, rather than another window's or the window manager's. */
    public boolean presses() {
        return presses;
    }

    /** The two-outcome answer a backend gave before there was a third: over this window means its content. */
    public static PointerTarget of(boolean pointerTarget) {
        return pointerTarget ? CONTENT : ELSEWHERE;
    }
}
