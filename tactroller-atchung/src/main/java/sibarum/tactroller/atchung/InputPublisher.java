package sibarum.tactroller.atchung;

import sibarum.atchung.Atchung;
import sibarum.atchung.Committer;
import sibarum.atchung.State;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.InputFrame;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.PointerState;
import sibarum.tactroller.api.ScrollDelta;

import java.util.Objects;
import java.util.Set;

/**
 * Turns Tactroller {@link InputFrame}s into Atchung traffic, applying the two-shapes split:
 *
 * <ul>
 *   <li><b>Discrete edges</b> (key/button press &amp; release, scroll, focus change) → {@link #events()}
 *       {@link Topic} events, lossless.</li>
 *   <li><b>Pointer position</b> (latest-only) → {@link #pointer()} {@link State}, coalesced &amp; versioned.</li>
 * </ul>
 *
 * <p>Pure with respect to input acquisition — it knows nothing about where frames come from, so it is
 * trivially testable. {@link TactrollerInputBridge} wraps it to pull frames from a live Tactroller.
 */
public final class InputPublisher {

    private final Atchung bus;
    private final Topic<InputEvent> events;
    private final State<PointerState> pointer;
    private final Committer<PointerState, PointerState> setPointer;

    private boolean prevFocused = true;

    public InputPublisher(Atchung bus) {
        this(bus, "tactroller.input");
    }

    public InputPublisher(Atchung bus, String eventsTopicName) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.events = Topic.of(eventsTopicName, InputEvent.class);

        State.Builder<PointerState> b = State.of(new PointerState(0, 0, Set.of()));
        this.setPointer = b.mutation("set", (current, next) -> next);
        this.pointer = b.build();
    }

    /** The discrete input-event channel (key/button/scroll/focus edges). */
    public Topic<InputEvent> events() {
        return events;
    }

    /** The synchronized pointer position + pressed buttons (latest coherent version). */
    public State<PointerState> pointer() {
        return pointer;
    }

    /** Publish one frame's derived edges and pointer state onto the bus. */
    public void publish(InputFrame f) {
        long ts = f.timestampNanos();

        for (Key k : f.pressedKeys()) {
            bus.publish(events, new InputEvent.KeyPressed(k, ts));
        }
        for (Key k : f.releasedKeys()) {
            bus.publish(events, new InputEvent.KeyReleased(k, ts));
        }
        for (MouseButton btn : f.pressedButtons()) {
            bus.publish(events, new InputEvent.ButtonPressed(btn, f.pointerX(), f.pointerY(), ts));
        }
        for (MouseButton btn : f.releasedButtons()) {
            bus.publish(events, new InputEvent.ButtonReleased(btn, f.pointerX(), f.pointerY(), ts));
        }

        ScrollDelta scroll = f.scroll();
        if (!scroll.isZero()) {
            bus.publish(events, new InputEvent.Scrolled(scroll.x(), scroll.y(), f.pointerX(), f.pointerY(), ts));
        }

        if (f.focused() != prevFocused) {
            bus.publish(events, new InputEvent.FocusChanged(f.focused(), ts));
            prevFocused = f.focused();
        }

        PointerState next = new PointerState(f.pointerX(), f.pointerY(), f.heldButtons());
        if (!pointer.value().equals(next)) {
            pointer.commit(setPointer, next);
        }
    }
}
