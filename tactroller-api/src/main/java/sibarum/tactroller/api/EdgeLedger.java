package sibarum.tactroller.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one poll's worth of input into the edges since the last: every transition a backend queued, in order, then
 * whatever it takes to arrive at what the poll says is down now.
 *
 * <p>Two sources, and each is right about something different. The <b>poll</b> is right about state: what is down at
 * this instant, whatever messages went missing on the way. The <b>queue</b> is right about history: the press and
 * release of a click that both fell between two polls, which the poll cannot see. So the edges published are exactly
 * the transitions that take the reported state from last frame's to this frame's, the queue's included:
 *
 * <ul>
 *   <li>a queued edge that would not change the reported state (a press of what is already down) is dropped, and</li>
 *   <li>whatever still differs from the poll once the queue is spent gets a transition of its own, at the end.</li>
 * </ul>
 *
 * <p>That makes a lagging queue harmless. The poll can run ahead of the queue (the button is up now, its release is
 * still on the way from the OS); the release is published from the poll now, and when the queued one arrives next
 * frame it changes nothing and is dropped, so nothing is published twice. A backend with no queue at all gets the
 * plain diff of two polls, which is what every backend had before there was a queue.
 *
 * <p>Not thread-safe: one ledger per draining thread.
 */
final class EdgeLedger implements InputEdge.Sink {

    /** Screen point to the frame's coordinate space. */
    interface Projection {
        int[] apply(int screenX, int screenY);
    }

    private final EnumSet<Key> keys = EnumSet.noneOf(Key.class);
    private final EnumSet<MouseButton> buttons = EnumSet.noneOf(MouseButton.class);
    private boolean primed;

    // Per-call scratch, set by settle for the Sink methods.
    private List<InputEdge> out;
    private Projection project;

    /**
     * The edges since the last call, in order, ending in the state {@code liveKeys}/{@code liveButtons}. The first
     * call reports none: it is the baseline, and whatever is held then was pressed before anyone was listening.
     *
     * @param queued      the backend's drained edges, button positions in screen pixels
     * @param x           the pointer now, already in the frame's space: where a transition from the poll happens
     * @param y           as {@code x}
     * @param now         when a transition from the poll happens
     * @param project     how a queued button position gets into the frame's space
     */
    List<InputEdge> settle(List<InputEdge> queued, Set<Key> liveKeys, Set<MouseButton> liveButtons,
                           int x, int y, long now, Projection project) {
        if (!primed) {
            primed = true;
            keys.addAll(liveKeys);
            buttons.addAll(liveButtons);
            return List.of();
        }
        if (queued.isEmpty() && keys.equals(liveKeys) && buttons.equals(liveButtons)) {
            return List.of();   // the common frame: nothing happened, and nothing is allocated saying so
        }
        out = new ArrayList<>();
        this.project = project;
        for (InputEdge e : queued) {
            e.to(this);
        }
        for (Key k : liveKeys) {
            if (keys.add(k)) {
                out.add(InputEdge.key(k, true, now));
            }
        }
        for (Key k : List.copyOf(keys)) {
            if (!liveKeys.contains(k)) {
                keys.remove(k);
                out.add(InputEdge.key(k, false, now));
            }
        }
        for (MouseButton b : liveButtons) {
            if (buttons.add(b)) {
                out.add(InputEdge.button(b, true, x, y, now));
            }
        }
        for (MouseButton b : List.copyOf(buttons)) {
            if (!liveButtons.contains(b)) {
                buttons.remove(b);
                out.add(InputEdge.button(b, false, x, y, now));
            }
        }
        List<InputEdge> settled = List.copyOf(out);
        out = null;
        this.project = null;
        return settled;
    }

    @Override
    public void keyPressed(Key key, long timestampNanos) {
        if (keys.add(key)) {
            out.add(InputEdge.key(key, true, timestampNanos));
        }
    }

    @Override
    public void keyReleased(Key key, long timestampNanos) {
        if (keys.remove(key)) {
            out.add(InputEdge.key(key, false, timestampNanos));
        }
    }

    @Override
    public void buttonPressed(MouseButton button, int x, int y, long timestampNanos) {
        if (buttons.add(button)) {
            int[] p = project.apply(x, y);
            out.add(InputEdge.button(button, true, p[0], p[1], timestampNanos));
        }
    }

    @Override
    public void buttonReleased(MouseButton button, int x, int y, long timestampNanos) {
        if (buttons.remove(button)) {
            int[] p = project.apply(x, y);
            out.add(InputEdge.button(button, false, p[0], p[1], timestampNanos));
        }
    }
}
