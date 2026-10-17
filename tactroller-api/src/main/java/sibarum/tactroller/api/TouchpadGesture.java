package sibarum.tactroller.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recognizes multi-finger touchpad gestures — pans, pinches (with rotation) and taps, for any number of fingers
 * from two up — in raw touchpad contacts, emitting {@link GestureEvent}s.
 *
 * <pre>{@code
 * TouchpadGesture gestures = new TouchpadGesture();
 *
 * InputFrame f = t.snapshot();                         // once per frame
 * for (GestureEvent g : gestures.feed(f)) {
 *     switch (g) {
 *         case GestureEvent.GestureUpdated u when u.kind() == GestureEvent.Kind.PINCH && u.fingers() == 2 ->
 *                 view.zoomAbout(u.x(), u.y(), u.scale());
 *         case GestureEvent.GestureUpdated u when u.fingers() == 2 -> view.panBy(u.dx() * PX_PER_MM, u.dy() * PX_PER_MM);
 *         case GestureEvent.GestureEnded e when e.fingers() == 3 && e.velocityX() > 150 -> nextPage();
 *         case GestureEvent.Tapped tap when tap.fingers() == 3 -> showOverview();
 *         default -> { }
 *     }
 * }
 * }</pre>
 *
 * <p>It can equally be fed {@link InputEvent}s ({@link #feed(InputEvent)}), so an event-loop or bus consumer gets the
 * same gestures as a frame consumer.
 *
 * <h2>One finger is the pointer</h2>
 *
 * <p>Gestures need two fingers or more. One finger on a touchpad is the pointer, and the OS already turns it into
 * motion and clicks; a recognizer that also read it would report every pointer movement a second time.
 *
 * <h2>The kind is decided once</h2>
 *
 * <p>Fingers moving together are never perfectly together, and fingers pinching never perfectly symmetric. A
 * recognizer that reported every component of every motion would make each pan zoom a little and each zoom drift.
 * So nothing is reported until the fingers have moved {@code thresholdMm}, at which point the gesture is classified
 * as a {@link GestureEvent.Kind#PAN} or a {@link GestureEvent.Kind#PINCH} and stays that until it ends; a pan's
 * updates then carry translation alone. A pinch is chosen when the fingers' spread or twist has changed by the
 * threshold and by at least half as much as their centre has moved — "at least half" because a pinch with one
 * finger held still moves the centre exactly as far as it changes the spread, and that is a pinch.
 *
 * <h2>Finger-count changes end a gesture</h2>
 *
 * <p>See {@link GestureEvent.GestureEnded}. While fingers are landing (before a gesture has been decided) more
 * fingers simply join it, since a hand never lands all at once. After a finger lifts, nothing new begins until one
 * finger or none is left — otherwise the trailing fingers of a three-finger swipe would start a two-finger one.
 *
 * <h2>The clock</h2>
 *
 * <p>Like {@link DragGesture} this class has no clock of its own; it advances on the timestamps it is fed. Taps are
 * resolved when the fingers lift, so nothing needs a tick.
 *
 * <h2>Why this is not in the backend</h2>
 *
 * <p>The backend reports contacts — raw state — and the recognizer lives here, so that a pinch is the same pinch on
 * every OS: the same thresholds, the same classification, the same end rules. Platform gesture recognizers differ
 * in all three, and an application targeting several platforms would inherit every difference.
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe: a per-consumer state machine over one ordered stream.
 */
public final class TouchpadGesture {

    /** How far the fingers must move, in millimetres on the surface, before they are a gesture. */
    public static final double DEFAULT_THRESHOLD_MM = 2.0;

    /** How long fingers may stay down and still be a tap. */
    public static final long DEFAULT_TAP_MILLIS = 250L;

    /** How far any finger may move and still be part of a tap, in millimetres. */
    public static final double DEFAULT_TAP_SLOP_MM = 2.0;

    /** The window over which the fingers' release velocity is measured. */
    private static final long VELOCITY_WINDOW_NANOS = 100_000_000L;

    /** Below this spread, in millimetres, a scale factor is noise over a vanishing denominator. */
    private static final double MIN_SPREAD_MM = 0.5;

    private enum Phase {
        /** No fingers down. */
        IDLE,
        /** Fingers down, not yet a gesture. */
        PENDING,
        /** A gesture is in flight. */
        ACTIVE,
        /** A finger lifted from a group: wait until at most one is left before anything new can begin. */
        LIFTING
    }

    private final double thresholdMm;
    private final long tapNanos;
    private final double tapSlopMm;

    private Phase phase = Phase.IDLE;

    // The touch sequence: first finger down until the last one lifts.
    private long sequenceStartNanos;
    private int maxFingers;
    private boolean moved;
    private boolean clicked;
    private boolean gestured;
    private final Map<Integer, double[]> landing = new HashMap<>();

    // The gesture: the contacts it started from, the previous update's contacts.
    private Map<Integer, double[]> baseline = Map.of();
    private Map<Integer, double[]> previous = Map.of();
    private long baselineNanos;
    private GestureEvent.Kind kind;
    private int fingers;

    // Centre positions for the release velocity, a ring of the most recent updates.
    private final long[] sampleNanos = new long[32];
    private final double[] sampleX = new double[32];
    private final double[] sampleY = new double[32];
    private int sampleCount;
    private int sampleNext;

    // Pointer and buttons, for the event-fed path.
    private int pointerX;
    private int pointerY;
    private final Set<MouseButton> buttonsHeld = EnumSet.noneOf(MouseButton.class);
    private List<TouchpadContact> lastContacts = List.of();

    /** {@link #DEFAULT_THRESHOLD_MM}, {@link #DEFAULT_TAP_MILLIS}, {@link #DEFAULT_TAP_SLOP_MM}. */
    public TouchpadGesture() {
        this(DEFAULT_THRESHOLD_MM, DEFAULT_TAP_MILLIS, DEFAULT_TAP_SLOP_MM);
    }

    /**
     * @param thresholdMm how far the fingers must move before they are a gesture; must be positive
     * @param tapMillis   the longest a tap may last; 0 disables taps
     * @param tapSlopMm   how far a finger may move during a tap
     */
    public TouchpadGesture(double thresholdMm, long tapMillis, double tapSlopMm) {
        if (!(thresholdMm > 0)) {
            throw new IllegalArgumentException("thresholdMm must be positive: " + thresholdMm);
        }
        if (tapMillis < 0) {
            throw new IllegalArgumentException("tapMillis must be >= 0: " + tapMillis);
        }
        if (!(tapSlopMm >= 0)) {
            throw new IllegalArgumentException("tapSlopMm must be >= 0: " + tapSlopMm);
        }
        this.thresholdMm = thresholdMm;
        this.tapNanos = tapMillis * 1_000_000L;
        this.tapSlopMm = tapSlopMm;
    }

    /** True between a {@link GestureEvent.GestureStarted} and its {@link GestureEvent.GestureEnded}. */
    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    /**
     * Advance by one frame. Feed every frame, including those with no contacts — the empty frame is how the
     * recognizer learns the fingers lifted.
     */
    public List<GestureEvent> feed(InputFrame frame) {
        Objects.requireNonNull(frame, "frame");
        pointerX = frame.pointerX();
        pointerY = frame.pointerY();
        boolean button = !frame.heldButtons().isEmpty() || !frame.pressedButtons().isEmpty();
        if (button && !frame.touchpadContacts().isEmpty()) {
            clicked = true;   // pressing the pad down is a click, whatever the fingers did
        }
        return advance(frame.touchpadContacts(), frame.timestampNanos());
    }

    /**
     * Advance by one event. Only {@link InputEvent.TouchpadChanged} can produce gestures; pointer and button events
     * are read for the position to report and to tell a tap from a click, and everything else is ignored, so it is
     * safe to feed the whole stream.
     */
    public List<GestureEvent> feed(InputEvent event) {
        Objects.requireNonNull(event, "event");
        switch (event) {
            case InputEvent.TouchpadChanged t -> {
                if (!buttonsHeld.isEmpty() && !t.contacts().isEmpty()) {
                    clicked = true;
                }
                lastContacts = t.contacts();
                return advance(t.contacts(), t.timestampNanos());
            }
            case InputEvent.PointerMoved m -> {
                pointerX = m.x();
                pointerY = m.y();
            }
            case InputEvent.ButtonPressed b -> {
                pointerX = b.x();
                pointerY = b.y();
                buttonsHeld.add(b.button());
                if (!lastContacts.isEmpty()) {
                    clicked = true;   // pressing the pad down is a click, whatever the fingers did
                }
            }
            case InputEvent.ButtonReleased b -> {
                pointerX = b.x();
                pointerY = b.y();
                buttonsHeld.remove(b.button());
            }
            case InputEvent.Scrolled s -> {
                pointerX = s.x();
                pointerY = s.y();
            }
            default -> { }
        }
        return List.of();
    }

    /**
     * Abandon any gesture in flight without emitting anything, for a consumer tearing down what the gesture refers
     * to. The fingers still down are ignored until they lift (all but one of them, as after any lift).
     */
    public void reset() {
        if (phase != Phase.IDLE) {
            phase = Phase.LIFTING;
            gestured = true;   // and no tap either
        }
    }

    private List<GestureEvent> advance(List<TouchpadContact> contacts, long now) {
        if (contacts.isEmpty()) {
            return report(lifted(now));
        }
        List<GestureEvent> out = new ArrayList<>(2);
        Map<Integer, double[]> current = positions(contacts);
        if (phase == Phase.IDLE) {
            phase = Phase.PENDING;
            sequenceStartNanos = now;
            maxFingers = 0;
            moved = false;
            gestured = false;
            landing.clear();
            rebase(current, now);
        }
        trackTap(current);

        boolean sameFingers = current.keySet().equals(baseline.keySet());
        boolean onlyAdded = current.keySet().containsAll(baseline.keySet());
        switch (phase) {
            case PENDING -> {
                if (!sameFingers) {
                    if (onlyAdded) {
                        rebase(current, now);           // another finger landing: the hand is still arriving
                    } else {
                        phase = Phase.LIFTING;
                        afterLift(current, now);
                    }
                } else if (current.size() >= 2) {
                    GestureEvent.Kind decided = classify(current);
                    if (decided != null) {
                        phase = Phase.ACTIVE;
                        gestured = true;
                        kind = decided;
                        fingers = current.size();
                        sampleCount = 0;
                        sample(centre(baseline), baselineNanos);
                        out.add(new GestureEvent.GestureStarted(kind, fingers, pointerX, pointerY, now));
                        update(current, now, out);
                    }
                }
            }
            case ACTIVE -> {
                if (sameFingers) {
                    update(current, now, out);
                } else {
                    out.add(end(now));
                    if (onlyAdded) {
                        phase = Phase.PENDING;
                        rebase(current, now);
                    } else {
                        phase = Phase.LIFTING;
                        afterLift(current, now);
                    }
                }
            }
            case LIFTING -> afterLift(current, now);
            case IDLE -> throw new AssertionError("left IDLE above");
        }
        return report(out);
    }

    /** In LIFTING: once at most one finger is left, the next fingers to land can start a fresh gesture. */
    private void afterLift(Map<Integer, double[]> current, long now) {
        if (current.size() <= 1) {
            phase = Phase.PENDING;
            rebase(current, now);
        }
    }

    /** Every finger is off the surface: end what was in flight, and see whether the whole touch was a tap. */
    private List<GestureEvent> lifted(long now) {
        if (phase == Phase.IDLE) {
            return List.of();
        }
        List<GestureEvent> out = new ArrayList<>(1);
        if (phase == Phase.ACTIVE) {
            out.add(end(now));
        }
        if (!gestured && !moved && !clicked && maxFingers >= 2 && now - sequenceStartNanos <= tapNanos) {
            out.add(new GestureEvent.Tapped(maxFingers, pointerX, pointerY, now));
        }
        phase = Phase.IDLE;
        clicked = false;
        baseline = Map.of();
        previous = Map.of();
        return out;
    }

    private void trackTap(Map<Integer, double[]> current) {
        maxFingers = Math.max(maxFingers, current.size());
        for (Map.Entry<Integer, double[]> c : current.entrySet()) {
            double[] at = landing.putIfAbsent(c.getKey(), c.getValue());
            if (at != null && Math.hypot(c.getValue()[0] - at[0], c.getValue()[1] - at[1]) > tapSlopMm) {
                moved = true;
            }
        }
    }

    private void rebase(Map<Integer, double[]> current, long now) {
        baselineNanos = now;
        baseline = current;
        previous = current;
    }

    /** The kind of gesture the fingers have made since the baseline, or null while they have not moved enough. */
    private GestureEvent.Kind classify(Map<Integer, double[]> current) {
        double[] c0 = centre(baseline);
        double[] c = centre(current);
        double translation = Math.hypot(c[0] - c0[0], c[1] - c0[1]);
        double spread = Math.abs(spread(current, c) - spread(baseline, c0));
        double twist = Math.abs(twistArc(baseline, c0, current, c));
        double relative = Math.max(spread, twist);
        if (relative >= thresholdMm && relative >= translation / 2) {
            return GestureEvent.Kind.PINCH;
        }
        if (translation >= thresholdMm) {
            return GestureEvent.Kind.PAN;
        }
        return null;
    }

    private void update(Map<Integer, double[]> current, long now, List<GestureEvent> out) {
        double[] c0 = centre(previous);
        double[] c = centre(current);
        double dx = c[0] - c0[0];
        double dy = c[1] - c0[1];
        double scale = 1.0;
        double rotation = 0.0;
        if (kind == GestureEvent.Kind.PINCH) {
            double before = spread(previous, c0);
            double after = spread(current, c);
            if (before >= MIN_SPREAD_MM && after >= MIN_SPREAD_MM) {
                scale = after / before;
            }
            rotation = twistAngle(previous, c0, current, c);
        }
        previous = current;
        sample(c, now);
        if (dx != 0 || dy != 0 || scale != 1.0 || rotation != 0) {
            out.add(new GestureEvent.GestureUpdated(kind, fingers, pointerX, pointerY, dx, dy, scale, rotation, now));
        }
    }

    private GestureEvent end(long now) {
        double vx = 0;
        double vy = 0;
        // The oldest and newest centre positions inside the window ending now. If the fingers rested before lifting,
        // no sample is recent and the velocity is zero, which is right: they were not moving when they left.
        int newest = -1;
        int oldest = -1;
        for (int i = 0; i < sampleCount; i++) {
            int k = Math.floorMod(sampleNext - 1 - i, sampleNanos.length);
            if (now - sampleNanos[k] > VELOCITY_WINDOW_NANOS) {
                break;
            }
            if (newest < 0) {
                newest = k;
            }
            oldest = k;
        }
        if (newest >= 0 && newest != oldest) {
            double seconds = (sampleNanos[newest] - sampleNanos[oldest]) / 1e9;
            if (seconds > 0) {
                vx = (sampleX[newest] - sampleX[oldest]) / seconds;
                vy = (sampleY[newest] - sampleY[oldest]) / seconds;
            }
        }
        return new GestureEvent.GestureEnded(kind, fingers, pointerX, pointerY, vx, vy, now);
    }

    private void sample(double[] c, long now) {
        sampleNanos[sampleNext] = now;
        sampleX[sampleNext] = c[0];
        sampleY[sampleNext] = c[1];
        sampleNext = (sampleNext + 1) % sampleNanos.length;
        sampleCount = Math.min(sampleCount + 1, sampleNanos.length);
    }

    private static Map<Integer, double[]> positions(List<TouchpadContact> contacts) {
        Map<Integer, double[]> m = new HashMap<>(contacts.size() * 2);
        for (TouchpadContact c : contacts) {
            m.put(c.id(), new double[] {c.x(), c.y()});
        }
        return m;
    }

    private static double[] centre(Map<Integer, double[]> fingers) {
        double x = 0;
        double y = 0;
        for (double[] p : fingers.values()) {
            x += p[0];
            y += p[1];
        }
        int n = Math.max(1, fingers.size());
        return new double[] {x / n, y / n};
    }

    /** Mean distance of the fingers from their centre. */
    private static double spread(Map<Integer, double[]> fingers, double[] c) {
        double sum = 0;
        for (double[] p : fingers.values()) {
            sum += Math.hypot(p[0] - c[0], p[1] - c[1]);
        }
        return sum / Math.max(1, fingers.size());
    }

    /**
     * The fingers' rotation about their centre from {@code a} to {@code b}, in radians, clockwise positive (y grows
     * downwards). Weighted by each finger's distance from the centre, since a finger near the centre has an angle
     * dominated by noise.
     */
    private static double twistAngle(Map<Integer, double[]> a, double[] ca, Map<Integer, double[]> b, double[] cb) {
        double weighted = 0;
        double weights = 0;
        for (Map.Entry<Integer, double[]> e : b.entrySet()) {
            double[] p0 = a.get(e.getKey());
            double[] p1 = e.getValue();
            double r = Math.hypot(p1[0] - cb[0], p1[1] - cb[1]);
            double turn = angleBetween(p0[0] - ca[0], p0[1] - ca[1], p1[0] - cb[0], p1[1] - cb[1]);
            weighted += r * turn;
            weights += r;
        }
        return weights == 0 ? 0 : weighted / weights;
    }

    /** The same rotation as an arc length in millimetres, comparable with translation and spread. */
    private static double twistArc(Map<Integer, double[]> a, double[] ca, Map<Integer, double[]> b, double[] cb) {
        return twistAngle(a, ca, b, cb) * spread(b, cb);
    }

    private static double angleBetween(double x0, double y0, double x1, double y1) {
        if ((x0 == 0 && y0 == 0) || (x1 == 0 && y1 == 0)) {
            return 0;
        }
        return Math.atan2(x0 * y1 - y0 * x1, x0 * x1 + y0 * y1);
    }

    /** Count what the recognizer decided, by kind — starts and ends that do not balance are the diagnosis. */
    private static List<GestureEvent> report(List<GestureEvent> events) {
        if (sibarum.probe.Probe.ON) {
            for (GestureEvent e : events) {
                sibarum.probe.Probe.count(sibarum.probe.Lane.INPUT,
                        switch (e) {
                            case GestureEvent.GestureStarted s -> "gesture started";
                            case GestureEvent.GestureUpdated u -> "gesture updated";
                            case GestureEvent.GestureEnded x -> "gesture ended";
                            case GestureEvent.Tapped t -> "gesture tapped";
                        });
            }
        }
        return events;
    }
}
