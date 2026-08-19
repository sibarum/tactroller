package sibarum.tactroller.atchung;

import sibarum.atchung.Atchung;
import sibarum.atchung.Committer;
import sibarum.atchung.State;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.InputFrame;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.PointerDelta;
import sibarum.tactroller.api.PointerState;
import sibarum.tactroller.api.ScrollDelta;

import java.util.Objects;
import java.util.Set;

/**
 * Turns Tactroller {@link InputFrame}s into Atchung traffic, applying the two-shapes split:
 *
 * <ul>
 *   <li><b>Discrete edges</b> (key/button press &amp; release, relative pointer motion, scroll, focus change)
 *       → {@link #events()} {@link Topic} events, lossless. Motion is here, not on the State, because each
 *       frame's delta must be summed — coalescing to "latest" would drop intervening motion.</li>
 *   <li><b>Pointer position</b> (latest-only) → {@link #pointer()} {@link State}, coalesced &amp; versioned.</li>
 * </ul>
 *
 * <p>Pure with respect to input acquisition — it knows nothing about where frames come from, so it is
 * trivially testable. {@link TactrollerInputBridge} wraps it to pull frames from a live Tactroller.
 *
 * <h2>Routing: which events belong to this window</h2>
 *
 * <p>Several OS input channels are <b>process-wide</b>, not per-window: on Windows, RawInput (wheel, typed
 * text) is registered once per process, and key/pointer state is polled globally. A multi-window process
 * therefore sees <em>every</em> window's share of those signals on <em>every</em> backend. Publishing them
 * unfiltered puts one physical keystroke on two windows' buses — which is the bug this gate exists to
 * prevent. This class is the one seam where scope is corrected, so no GUI downstream needs to know:
 *
 * <ul>
 *   <li><b>Focal</b> channels (keys, typed characters) carry no position, so they belong to the focused
 *       window: gated on {@link InputFrame#focused()}.</li>
 *   <li><b>Positional</b> channels (wheel, pointer motion, buttons) belong to the window under the cursor:
 *       gated on {@link InputFrame#pointerInClient()}. Overlapping windows may both pass this gate; the
 *       consumer's own hit-testing resolves that.</li>
 *   <li><b>Ungated:</b> focus changes (the gate's own signal) and pointer <em>position</em> State, which is
 *       a passive latest-value read rather than a delivered event.</li>
 * </ul>
 *
 * <p>A windowless backend reports {@code true} for both gates, so single-window and headless setups behave
 * exactly as they did before the gates existed.
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

    /** Publish one frame's derived edges and pointer state onto the bus, applying the routing gates. */
    public void publish(InputFrame f) {
        long ts = f.timestampNanos();
        boolean focal = f.focused();               // keys/text: the focused window's
        boolean positional = f.pointerInClient();  // wheel/pointer: the window under the cursor

        if (focal) {
            for (Key k : f.pressedKeys()) {
                bus.publish(events, new InputEvent.KeyPressed(k, ts));
            }
            for (Key k : f.releasedKeys()) {
                bus.publish(events, new InputEvent.KeyReleased(k, ts));
            }
        }
        if (positional) {
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
        }

        // Typed text (layout-resolved code points) — the lossless text channel, one event per code point,
        // kept strictly separate from the KeyPressed command channel above. Focal: text goes where focus is.
        if (focal) {
            for (int cp : f.typedChars()) {
                bus.publish(events, new InputEvent.CharTyped(cp, ts));
            }
        }

        // Relative motion is a lossless "must-sum" signal (each frame's delta matters and consumers add them
        // up), so it rides the edge Topic, not the coalesced pointer State. This is also the sole place the
        // snapshot's captured delta is surfaced — publishing it here keeps snapshot() the only drain of the
        // backend's relative-motion accumulator, so consumers must read motion from the bus, never poll
        // pollPointerDelta() in parallel (that second drain steals the delta; see the render-loop contract).
        PointerDelta motion = f.motion();
        if (positional && !motion.isZero()) {
            bus.publish(events, new InputEvent.PointerMoved(
                    f.pointerX(), f.pointerY(), motion.dx(), motion.dy(), ts));
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
