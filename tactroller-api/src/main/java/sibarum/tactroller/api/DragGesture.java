package sibarum.tactroller.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Recognizes press-move-release drags in a stream of {@link InputEvent}s, emitting {@link DragEvent}s.
 *
 * <p>Purely additive: it observes the event stream and never alters it. Button presses and releases
 * still arrive exactly as before, so existing click handling is unaffected and does not need to know
 * that drags exist.
 *
 * <pre>{@code
 * DragGesture drags = new DragGesture();          // LEFT, 2px, 100ms
 *
 * for (InputEvent e : incoming) {
 *     for (DragEvent d : drags.feed(e)) {
 *         switch (d) {
 *             case DragEvent.DragStarted s -> payload = pick(s.startX(), s.startY());
 *             case DragEvent.DragOver o    -> ghost.moveTo(o.offsetX(), o.offsetY());
 *             case DragEvent.DragEnded x   -> {
 *                 if (x.cancelled()) ghost.hide();
 *                 else dropTargetAt(x.x(), x.y()).accept(payload);
 *             }
 *         }
 *     }
 * }
 * drags.tick(System.nanoTime());                  // once per frame; see "The clock" below
 * }</pre>
 *
 * <h2>The two thresholds do different jobs</h2>
 *
 * <p>A drag is separated from a click by distance, and separated from a twitch by time — and collapsing
 * those into one "distance AND time" test gets a common gesture wrong. Press, flick 50px in 40ms,
 * release: distance met, hold time not, and a strict conjunction calls that a click, reported 50px from
 * where the button went down. So the two are split by what each is actually for:
 *
 * <ul>
 *   <li><b>Distance decides <em>whether</em> it was a drag.</b> Evaluated at release too, whatever the
 *       elapsed time, so a fast flick is a drag.</li>
 *   <li><b>Time decides <em>when</em> the drag becomes visible.</b> It debounces {@link
 *       DragEvent.DragStarted} so hand tremor during a click does not visibly begin one — it does not
 *       reclassify what the gesture was.</li>
 * </ul>
 *
 * <p>A release that met the distance but not the hold therefore emits {@link DragEvent.DragStarted}
 * immediately followed by {@link DragEvent.DragEnded}: the consumer still gets the start it needs to
 * build a payload before the drop lands. That is the one case where {@link #feed} returns two events.
 *
 * <p>The cost of the hold is latency, and it is worth sizing before tuning it: at the default 125 Hz
 * event loop, 100ms is about twelve frames between the press and any visible sign of a drag. Set
 * {@code holdMillis} to 0 for distance-only recognition, which is what the platform toolkits do.
 *
 * <h2>The clock</h2>
 *
 * <p>A hold threshold needs a clock, and this class has none of its own — it spawns no thread and reads
 * no wall clock, matching the rest of the library, where the consumer drives timing. It advances on the
 * timestamps of the events it is fed, plus {@link #tick(long)}.
 *
 * <p>{@code tick} matters because the stream can go silent: cross the distance threshold at 20ms, then
 * hold the pointer perfectly still. No further motion event arrives, so without a tick the drag is not
 * promoted until the pointer moves again — or, if it never does, until release, which still resolves
 * correctly but reports the whole gesture at once. Calling {@code tick} once per frame makes {@link
 * DragEvent.DragStarted} land on time. It is optional, and everything remains correct without it.
 *
 * <h2>Why this is not in the backend</h2>
 *
 * <p>A drag is a <em>gesture</em>, not an input channel: it is derived entirely from events the API
 * already produces, so it needs no native code and behaves identically on every platform for the same
 * reason the shared event loop does. It stops one step short of drag-and-<em>drop</em> on purpose.
 * Resolving a drop target means hit-testing a scene graph, and this library cannot see one — the same
 * reason {@code isPointerTarget()} has to be answered by the OS rather than by a consumer. So the
 * recognizer hands over the drop point and the consumer resolves it. Payload, drop-target highlighting
 * and revert semantics likewise belong to the consumer.
 *
 * <h2>What it relies on, and what it therefore cannot fix</h2>
 *
 * <p>A drag necessarily leaves the window it began in, so the recognizer depends on pointer capture:
 * once a window's press is delivered, motion and the eventual release belong to that window wherever the
 * pointer has gone. That is guaranteed by the delivery gate, not here — feed this recognizer a stream
 * whose motion stops at the window edge and it will faithfully report a drag that freezes and then
 * lurches, because that is what the stream said happened.
 *
 * <p>Focus loss does <b>not</b> cancel a drag. Alt-tabbing mid-gesture suppresses this window's key
 * events, but motion and release are positional and keep arriving, so the gesture still completes
 * correctly; cancelling would discard a drop the user is in the middle of making. Escape is the cancel
 * path — and it reaches a drag that has wandered off-window, because focus follows the window, not the
 * pointer.
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe, and deliberately so: it is a per-consumer state machine over one ordered stream,
 * so it belongs to whichever thread drains that stream.
 */
public final class DragGesture {

    /** Distance below which a press-and-release is a click, not a drag. */
    public static final int DEFAULT_DISTANCE_PX = 2;

    /** How long a button must be held before a drag becomes visible. */
    public static final long DEFAULT_HOLD_MILLIS = 100L;

    private final int distancePx;
    private final long holdNanos;
    private final Set<MouseButton> buttons;

    private MouseButton active;
    private long pressNanos;
    private int startX;
    private int startY;
    private int x;
    private int y;
    private int offsetX;
    private int offsetY;
    /** Whether the pointer has travelled far enough -- latched, so returning to the press point keeps it. */
    private boolean farEnough;
    private boolean dragging;
    /** Set when a gesture ends before its button does, so the release cannot resurrect it. */
    private boolean spent;

    /** Left-button drags past {@link #DEFAULT_DISTANCE_PX} held for {@link #DEFAULT_HOLD_MILLIS}. */
    public DragGesture() {
        this(DEFAULT_DISTANCE_PX, DEFAULT_HOLD_MILLIS, Set.of(MouseButton.LEFT));
    }

    /**
     * @param distancePx per-axis distance the pointer must travel for the gesture to be a drag at all;
     *                   0 makes any motion a drag
     * @param holdMillis how long after the press a drag may first become visible; 0 promotes as soon as
     *                   the distance is met, which is what the platform toolkits do
     * @param buttons    which buttons can begin a drag; a press of any other is ignored entirely
     */
    public DragGesture(int distancePx, long holdMillis, Set<MouseButton> buttons) {
        if (distancePx < 0) {
            throw new IllegalArgumentException("distancePx must be >= 0: " + distancePx);
        }
        if (holdMillis < 0) {
            throw new IllegalArgumentException("holdMillis must be >= 0: " + holdMillis);
        }
        Objects.requireNonNull(buttons, "buttons");
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("buttons must not be empty");
        }
        this.distancePx = distancePx;
        this.holdNanos = holdMillis * 1_000_000L;
        this.buttons = Set.copyOf(buttons);
    }

    /** True between a {@link DragEvent.DragStarted} and its {@link DragEvent.DragEnded}. */
    public boolean isDragging() {
        return dragging;
    }

    /** The button carrying the gesture in flight, or null when none is. */
    public MouseButton activeButton() {
        return active;
    }

    /**
     * Advance the state machine by one input event, returning the drag events it produced — usually
     * none or one, and two only for the flick described above. Events the gesture does not care about
     * are ignored, so it is safe to feed the whole stream rather than filtering first.
     */
    public List<DragEvent> feed(InputEvent event) {
        Objects.requireNonNull(event, "event");
        return report(switch (event) {
            case InputEvent.ButtonPressed p -> onPress(p);
            case InputEvent.PointerMoved m -> onMove(m);
            case InputEvent.ButtonReleased r -> onRelease(r);
            case InputEvent.KeyPressed k -> onKey(k);
            default -> List.of();
        });
    }

    /**
     * Count what the recognizer decided, by kind, and hand the list straight back.
     *
     * <p>A drag that "does not work" is nearly always a recognizer that never emitted a DragStarted - the
     * hold elapsed on a frame nobody ticked, or the distance was met after the button came up. Counting the
     * kinds turns that from a debugging session into a line: starts, overs and ends that do not balance are
     * the whole diagnosis.
     */
    private static List<DragEvent> report(List<DragEvent> events) {
        if (sibarum.probe.Probe.ON) {
            for (DragEvent e : events) {
                sibarum.probe.Probe.count(sibarum.probe.Lane.INPUT,
                        switch (e) {
                            case DragEvent.DragStarted s -> "drag started";
                            case DragEvent.DragOver o -> "drag over";
                            case DragEvent.DragEnded x -> "drag ended";
                        });
            }
        }
        return events;
    }

    /**
     * Advance the clock without an input event, returning a {@link DragEvent.DragStarted} if the hold
     * threshold has now elapsed on a gesture that already met the distance. Call once per frame; see
     * "The clock" above for why a purely event-driven recognizer needs it.
     *
     * @param nowNanos current time on the same clock as {@link InputEvent} timestamps
     *                 ({@link System#nanoTime()})
     */
    public List<DragEvent> tick(long nowNanos) {
        return report(promote(nowNanos));
    }

    /**
     * Abandon any gesture in flight without emitting anything. For a consumer tearing down the window or
     * the scene the drag refers to, where a {@link DragEvent.DragEnded} would be delivered to something
     * already gone.
     */
    public void reset() {
        active = null;
        farEnough = false;
        dragging = false;
        spent = false;
        offsetX = 0;
        offsetY = 0;
    }

    private List<DragEvent> onPress(InputEvent.ButtonPressed p) {
        // A second button pressed mid-gesture does not start a competing one: a gesture is owned by the
        // button that began it until that button comes back up.
        if (active != null || !buttons.contains(p.button())) {
            return List.of();
        }
        active = p.button();
        pressNanos = p.timestampNanos();
        startX = p.x();
        startY = p.y();
        x = p.x();
        y = p.y();
        offsetX = 0;
        offsetY = 0;
        farEnough = false;
        dragging = false;
        spent = false;
        return List.of();   // a press is not yet anything; it may become a click or a drag
    }

    private List<DragEvent> onMove(InputEvent.PointerMoved m) {
        x = m.x();
        y = m.y();
        if (active == null || spent) {
            return List.of();
        }
        offsetX += m.dx();
        offsetY += m.dy();
        if (dragging) {
            return List.of(new DragEvent.DragOver(
                    active, startX, startY, x, y, offsetX, offsetY, m.dx(), m.dy(), m.timestampNanos()));
        }
        return promote(m.timestampNanos());
    }

    private List<DragEvent> onRelease(InputEvent.ButtonReleased r) {
        if (active == null || r.button() != active) {
            return List.of();
        }
        // Trust the release's own position over the last move's: a release can carry a position no move
        // reported, and the drop point is the one coordinate in the gesture that has to be exact.
        x = r.x();
        y = r.y();
        MouseButton button = active;
        boolean wasDragging = dragging;
        boolean wasFarEnough = farEnough;
        boolean wasSpent = spent;
        active = null;
        farEnough = false;
        dragging = false;
        spent = false;

        if (wasSpent) {
            return List.of();       // already cancelled; this release is the tail of a finished gesture
        }
        DragEvent end = new DragEvent.DragEnded(
                button, startX, startY, x, y, offsetX, offsetY, false, r.timestampNanos());
        if (wasDragging) {
            return List.of(end);
        }
        if (wasFarEnough) {
            // Distance was met but the hold had not elapsed -- a flick. It was a drag; it simply ended
            // before it ever became visible. Synthesize the start rather than downgrading it to nothing,
            // so the consumer still gets the one event where it builds what is being dropped.
            return List.of(
                    new DragEvent.DragStarted(
                            button, startX, startY, x, y, offsetX, offsetY, r.timestampNanos()),
                    end);
        }
        return List.of();           // never travelled: a click, which the raw button events already cover
    }

    private List<DragEvent> onKey(InputEvent.KeyPressed k) {
        if (k.key() != Key.ESCAPE || active == null || spent) {
            return List.of();
        }
        // The gesture is over but the button is still down, so stay latched on it: re-crossing the
        // threshold must not restart the drag, and the release must not then complete one. Escape ends
        // the gesture; only lifting the button can begin another.
        boolean wasDragging = dragging;
        dragging = false;
        spent = true;
        if (!wasDragging) {
            return List.of();       // nothing had started, so there is nothing to tell the consumer
        }
        return List.of(new DragEvent.DragEnded(
                active, startX, startY, x, y, offsetX, offsetY, true, k.timestampNanos()));
    }

    /** Start the drag if the distance is met and the hold has elapsed. The single promotion point. */
    private List<DragEvent> promote(long nowNanos) {
        if (active == null || spent || dragging) {
            return List.of();
        }
        if (!farEnough) {
            // The zero check is not redundant with the distance: at distancePx == 0 the comparison is
            // vacuously satisfied, and a frame reporting no motion at all would otherwise start a drag.
            boolean moved = offsetX != 0 || offsetY != 0;
            farEnough = moved
                    && (Math.abs(offsetX) >= distancePx || Math.abs(offsetY) >= distancePx);
        }
        if (!farEnough || nowNanos - pressNanos < holdNanos) {
            return List.of();
        }
        dragging = true;
        return List.of(new DragEvent.DragStarted(
                active, startX, startY, x, y, offsetX, offsetY, nowNanos));
    }
}
