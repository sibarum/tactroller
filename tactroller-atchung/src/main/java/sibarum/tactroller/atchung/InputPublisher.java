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
 *   <li><b>Positional</b> channels (wheel, button presses, pointer motion) belong to the window under the
 *       cursor: gated on {@link InputFrame#pointerInClient()}. Overlapping windows may both pass this gate;
 *       the consumer's own hit-testing resolves that.</li>
 *   <li><b>Held by the gesture:</b> once a window's press has been delivered, that window owns the gesture
 *       until it ends, so the pointer <em>motion</em> and the eventual <em>release</em> go to it wherever the
 *       pointer has wandered — this is pointer capture, and without it a drag freezes and then lurches the
 *       moment it crosses the window's edge.</li>
 *   <li><b>Ungated:</b> focus changes (the gate's own signal) and pointer <em>position</em> State, which is
 *       a passive latest-value read rather than a delivered event.</li>
 * </ul>
 *
 * <p>The second and third bullets are the same rule twice: <b>a gate on delivery must not desynchronize
 * state.</b> A gate decides whose window an event belongs to, and it is right about that — but a consumer's
 * belief about what is held, and a gesture already in flight, are state rather than delivery, and dropping the
 * events that would have ended them leaves that state wrong forever.
 *
 * <p>A windowless backend reports {@code true} for both gates, so single-window and headless setups behave
 * exactly as they did before the gates existed.
 */
public final class InputPublisher {

    private final Atchung bus;
    private final Topic<InputEvent> events;
    private final State<PointerState> pointer;
    private final Committer<PointerState, PointerState> setPointer;

    /** The keys that are modifiers -- the state half of the keyboard, safe to re-assert on focus gain. */
    private static final Set<Key> MODIFIER_KEYS = Set.of(
            Key.LEFT_SHIFT, Key.RIGHT_SHIFT, Key.LEFT_CONTROL, Key.RIGHT_CONTROL,
            Key.LEFT_ALT, Key.RIGHT_ALT, Key.LEFT_SUPER, Key.RIGHT_SUPER);

    private boolean prevFocused = true;
    /** Keys last published as held while focused -- what a consumer currently believes is down. */
    private Set<Key> heldWhileFocused = Set.of();
    /** Buttons whose press this publisher delivered -- what a consumer currently believes is down. */
    private Set<MouseButton> heldSincePress = Set.of();

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
        }
        // A button RELEASE is deliberately not behind the positional gate, and this is the same rule the focus
        // unwind below states for keys: a gate on *delivery* must not desynchronize *state*. Press the pointer
        // inside this window, drag out past its edge, let go -- the release edge is in the frame (the backend
        // polls buttons whatever the pointer is over), but the gate would drop it, and the consumer would keep
        // the button latched forever. In a GUI that means a drag that never ends: the plot stays stuck to the
        // pointer until a fresh click somewhere inside resets it.
        //
        // Ungating is safe for the reason a release is always safe -- it is inert, it can only end things. What
        // it must not do is invent one, so a release is delivered only for a button whose PRESS this publisher
        // delivered. That is also what keeps two windows out of each other's business: press inside window A
        // and release over window B, and only A ever published the press, so only A publishes the release.
        for (MouseButton btn : f.releasedButtons()) {
            if (heldSincePress.contains(btn)) {
                bus.publish(events, new InputEvent.ButtonReleased(btn, f.pointerX(), f.pointerY(), ts));
            }
        }
        heldSincePress = nowHeld(heldSincePress, f, positional);

        if (positional) {
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
        // Motion follows the press, not the pointer. While this window owns a held button it owns the gesture,
        // so the moves keep coming even once the pointer has left -- which is what pointer capture means, and
        // what every drag contract above this already promises ("MOVE events keep arriving while the button is
        // held even if the pointer leaves"). Gating them at the window edge instead freezes a drag the moment
        // it overshoots, and then the whole frozen excursion arrives at once as the release's delta: the thing
        // being dragged sits still and then jumps. Owning the release without owning the motion fixed half a
        // gesture and made the other half worse.
        PointerDelta motion = f.motion();
        if ((positional || !heldSincePress.isEmpty()) && !motion.isZero()) {
            bus.publish(events, new InputEvent.PointerMoved(
                    f.pointerX(), f.pointerY(), motion.dx(), motion.dy(), ts));
        }

        if (f.focused() != prevFocused) {
            if (!f.focused()) {
                // Focus is leaving: from here on the focal gate suppresses this window's key events, so the
                // release edges for anything still held would never arrive and the consumer would keep it
                // latched forever. A gate on *delivery* must not desynchronize *state*, so unwind now, while
                // what is held is still known. Releases are inert -- they cannot fire a command.
                //
                // This is not hypothetical: opening a modal dialog with Ctrl+Shift+O and landing focus on
                // another window left CONTROL and SHIFT latched here, and every Ctrl-only global shortcut
                // then failed to match (a shortcut compares its modifier set exactly, while a focused
                // widget's own chords ask "is CONTROL among them?" and kept working -- which is what made
                // the symptom so lopsided).
                for (Key k : heldWhileFocused) {
                    bus.publish(events, new InputEvent.KeyReleased(k, ts));
                }
            }
            bus.publish(events, new InputEvent.FocusChanged(f.focused(), ts));
            prevFocused = f.focused();
            if (f.focused()) {
                // Focus is arriving: presses that happened while this window was unfocused were suppressed,
                // so re-assert the modifiers physically held now -- otherwise a chord begun in another window
                // ("hold Ctrl, click here, press W") sees no CONTROL. Modifiers only: a non-modifier press is
                // a command, and replaying it would fire an action the user aimed somewhere else.
                for (Key k : f.heldKeys()) {
                    if (MODIFIER_KEYS.contains(k)) {
                        bus.publish(events, new InputEvent.KeyPressed(k, ts));
                    }
                }
            }
        }
        heldWhileFocused = f.focused() ? f.heldKeys() : Set.of();

        PointerState next = new PointerState(f.pointerX(), f.pointerY(), f.heldButtons());
        if (!pointer.value().equals(next)) {
            pointer.commit(setPointer, next);
        }
    }

    /**
     * What a consumer believes is down after this frame: whatever it believed, less anything released, plus
     * anything pressed <em>inside the client</em>. A press outside belongs to another window, so it is not
     * added — which is what makes the ungated release above unable to fire for a press this publisher never
     * delivered.
     */
    private static Set<MouseButton> nowHeld(Set<MouseButton> before, InputFrame f, boolean positional) {
        if (before.isEmpty() && (!positional || f.pressedButtons().isEmpty())) {
            return Set.of();
        }
        java.util.EnumSet<MouseButton> held = java.util.EnumSet.noneOf(MouseButton.class);
        held.addAll(before);
        held.removeAll(f.releasedButtons());
        if (positional) {
            held.addAll(f.pressedButtons());
        }
        return held.isEmpty() ? Set.of() : Set.copyOf(held);
    }
}
