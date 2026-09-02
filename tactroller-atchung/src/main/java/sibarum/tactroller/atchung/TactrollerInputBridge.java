package sibarum.tactroller.atchung;

import sibarum.atchung.Atchung;
import sibarum.atchung.State;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.BackendException;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.InputFrame;
import sibarum.tactroller.api.PointerState;
import sibarum.tactroller.api.Tactroller;

import java.util.Objects;

/**
 * Makes Tactroller "just another producer" on an {@link Atchung} bus. Construct once, then call
 * {@link #pump()} exactly once per frame on the loop thread (right after the window pumps OS events):
 * it takes a Tactroller snapshot and hands it to an {@link InputPublisher}, which publishes this
 * frame's discrete edges ({@link #events()}) and pointer state ({@link #pointer()}).
 *
 * <p>Render-thread polling model: no background daemon, no cross-thread hand-off. Downstream consumers
 * (the sim, the GUI dispatcher, a recorder, a network bridge) subscribe to {@link #events()} /
 * {@link #pointer()} and never couple to Tactroller directly.
 */
public final class TactrollerInputBridge {

    private final Tactroller tactroller;
    private final InputPublisher publisher;

    public TactrollerInputBridge(Tactroller tactroller, Atchung bus) {
        this.tactroller = Objects.requireNonNull(tactroller, "tactroller");
        this.publisher = new InputPublisher(bus);
    }

    /** The discrete input-event channel (key/button/scroll/focus edges). */
    public Topic<InputEvent> events() {
        return publisher.events();
    }

    /** The synchronized pointer position + pressed buttons (latest coherent version). */
    public State<PointerState> pointer() {
        return publisher.pointer();
    }

    /** Snapshot Tactroller and publish this frame's edges and pointer state. Call once per frame. */
    public void pump() throws BackendException {
        // Two spans, not one, and the split is the point: "snapshot" (inside Tactroller) is what the OS cost,
        // "publish" is what the bus fan-out cost. An input frame that ran long is one or the other, and the
        // fix for each is nothing like the fix for the other.
        InputFrame frame = tactroller.snapshot();
        try (Zone z = Probe.zone(Lane.INPUT, "publish")) {
            publisher.publish(frame);
        }
    }
}
