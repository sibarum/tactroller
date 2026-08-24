package sibarum.tactroller.atchung;

import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.InputFrame;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.PointerDelta;
import sibarum.tactroller.api.ScrollDelta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputPublisherTest {

    private static InputFrame frame(
            Set<Key> held, Set<Key> pressed, Set<Key> released,
            Set<MouseButton> heldBtn, Set<MouseButton> pressedBtn, Set<MouseButton> releasedBtn,
            int x, int y, ScrollDelta scroll, boolean focused) {
        return new InputFrame(held, pressed, released, heldBtn, pressedBtn, releasedBtn,
                Modifier.from(held), x, y, PointerDelta.ZERO, scroll, focused, 1L);
    }

    /** A frame with both routing gates set explicitly — the multi-window shape. */
    private static InputFrame routed(Set<Key> pressed, Set<MouseButton> pressedBtn, ScrollDelta scroll,
                                     int[] typed, boolean focused, boolean pointerInClient) {
        return new InputFrame(Set.of(), pressed, Set.of(), Set.of(), pressedBtn, Set.of(),
                Set.of(), 5, 6, PointerDelta.ZERO, scroll, focused, 1L, typed, pointerInClient);
    }

    /**
     * The focal gate. Keys and typed text carry no position, so an unfocused window must not receive them —
     * without this, one keystroke lands on every window's bus in a multi-window process (the OS delivers
     * these process-wide), so typing in one window also types in another.
     */
    @Test
    void keysAndTextGoOnlyToTheFocusedWindow() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        // Unfocused, but the pointer is over this window: keys/text suppressed, wheel still delivered.
        pub.publish(routed(Set.of(Key.A), Set.of(), new ScrollDelta(0, 1.0), new int[]{'a'}, false, true));

        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.KeyPressed),
                "an unfocused window must not receive key edges");
        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.CharTyped),
                "an unfocused window must not receive typed text");
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.Scrolled),
                "the wheel is positional, not focal — it still belongs to the window under the cursor");

        got.clear();
        pub.publish(routed(Set.of(Key.A), Set.of(), ScrollDelta.ZERO, new int[]{'a'}, true, true));
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.KeyPressed k && k.key() == Key.A));
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.CharTyped c && c.codepoint() == 'a'));
    }

    /**
     * The positional gate. Wheel and pointer events belong to the window under the cursor, so a window the
     * pointer has left must not receive them even while it holds focus — otherwise a wheel notch scrolls
     * two windows at once.
     */
    @Test
    void wheelAndButtonsGoOnlyToTheWindowUnderThePointer() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        // Focused, but the pointer is over a different window: wheel/buttons suppressed, keys still delivered.
        pub.publish(routed(Set.of(Key.B), Set.of(MouseButton.LEFT), new ScrollDelta(0, 3.0),
                new int[0], true, false));

        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.Scrolled),
                "the wheel must not reach a window the pointer is not over");
        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.ButtonPressed),
                "button edges must not reach a window the pointer is not over");
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.KeyPressed),
                "keys are focal, not positional — they still belong to the focused window");
    }

    /** A pointer frame: what is held, the edges, and whether the pointer is over this window. */
    private static InputFrame buttons(Set<MouseButton> heldBtn, Set<MouseButton> pressedBtn,
                                      Set<MouseButton> releasedBtn, boolean pointerInClient) {
        return new InputFrame(Set.of(), Set.of(), Set.of(), heldBtn, pressedBtn, releasedBtn,
                Set.of(), 5, 6, PointerDelta.ZERO, new ScrollDelta(0, 0), true, 1L,
                new int[0], pointerInClient);
    }

    /**
     * The positional gate must not swallow a release. Press inside, drag out past the window's edge, let go:
     * the backend polls buttons wherever the pointer is, so the release edge is in the frame — but gating it
     * away leaves the consumer believing the button is still down, which in a GUI is a drag that never ends.
     *
     * <p>This is the button half of what {@link #losingFocusReleasesTheKeysItStillBelievesAreHeld} does for
     * keys, and it was missed for the same reason it is easy to miss: the gate is right for presses.
     */
    @Test
    void aReleaseReachesTheWindowThatSawThePressEvenOutsideIt() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(buttons(Set.of(MouseButton.LEFT), Set.of(MouseButton.LEFT), Set.of(), true));
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.ButtonPressed),
                "the press happened inside the window, so it is this window's");

        got.clear();
        // The pointer has left, and the button comes up out there.
        pub.publish(buttons(Set.of(), Set.of(), Set.of(MouseButton.LEFT), false));
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.ButtonReleased),
                "the release must arrive, or whatever the press started never ends");
    }

    /**
     * And it must not invent one. Only a button whose press this publisher delivered may be released by it —
     * otherwise pressing in one window and releasing over another would end a gesture in both.
     */
    @Test
    void aReleaseIsNotInventedForAPressThisWindowNeverSaw() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        // Pressed while the pointer was over some other window: gated away, so this window saw nothing.
        pub.publish(buttons(Set.of(MouseButton.LEFT), Set.of(MouseButton.LEFT), Set.of(), false));
        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.ButtonPressed));

        pub.publish(buttons(Set.of(), Set.of(), Set.of(MouseButton.LEFT), false));
        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.ButtonReleased),
                "a release with no matching press is a message about somebody else's gesture");
    }

    /** A release is delivered once: the belief is updated with it, so a later frame has nothing left to end. */
    @Test
    void aReleaseDoesNotRepeat() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(buttons(Set.of(MouseButton.LEFT), Set.of(MouseButton.LEFT), Set.of(), true));
        pub.publish(buttons(Set.of(), Set.of(), Set.of(MouseButton.LEFT), false));
        got.clear();
        pub.publish(buttons(Set.of(), Set.of(), Set.of(MouseButton.LEFT), false));
        assertTrue(got.stream().noneMatch(e -> e instanceof InputEvent.ButtonReleased));
    }

    /** Focus changes are the gate's own signal, so they must never be gated away. */
    @Test
    void focusChangesArePublishedRegardlessOfGates() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(routed(Set.of(), Set.of(), ScrollDelta.ZERO, new int[0], false, false));

        InputEvent.FocusChanged fc = (InputEvent.FocusChanged) got.stream()
                .filter(e -> e instanceof InputEvent.FocusChanged).findFirst()
                .orElseThrow(() -> new AssertionError("focus change was gated away"));
        assertEquals(false, fc.focused());
    }

    /** A frame with explicit held keys, for the focus-transition cases. */
    private static InputFrame held(Set<Key> heldKeys, boolean focused) {
        return new InputFrame(heldKeys, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Modifier.from(heldKeys), 0, 0, PointerDelta.ZERO, ScrollDelta.ZERO, focused, 1L,
                new int[0], true);
    }

    /**
     * Losing focus must unwind whatever this window believed was held. Otherwise the focal gate suppresses
     * the release edges and the modifier stays latched forever -- after which every global shortcut whose
     * modifier set is compared exactly (Ctrl+W, Ctrl+=) silently stops matching, while a focused widget's
     * own chords, which merely ask whether CONTROL is present, keep working.
     */
    @Test
    void losingFocusReleasesTheKeysItStillBelievesAreHeld() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        // Ctrl+Shift held while focused (as when a chord opens a dialog), then focus leaves.
        pub.publish(held(Set.of(Key.LEFT_CONTROL, Key.LEFT_SHIFT), true));
        got.clear();
        pub.publish(held(Set.of(Key.LEFT_CONTROL, Key.LEFT_SHIFT), false));

        Set<Key> released = got.stream()
                .filter(e -> e instanceof InputEvent.KeyReleased)
                .map(e -> ((InputEvent.KeyReleased) e).key())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(Key.LEFT_CONTROL, Key.LEFT_SHIFT), released,
                "both held modifiers must be released when focus leaves, or they latch");
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.FocusChanged fc && !fc.focused()));
    }

    /** Regaining focus re-asserts physically-held modifiers, so a chord begun elsewhere still works. */
    @Test
    void regainingFocusReassertsHeldModifiersButNotCommands() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(held(Set.of(), false));   // start unfocused
        got.clear();
        // Focus arrives while Ctrl and the W key are both physically down.
        pub.publish(held(Set.of(Key.LEFT_CONTROL, Key.W), true));

        Set<Key> pressed = got.stream()
                .filter(e -> e instanceof InputEvent.KeyPressed)
                .map(e -> ((InputEvent.KeyPressed) e).key())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(Key.LEFT_CONTROL), pressed,
                "modifiers are state and must be re-asserted; W is a command and must not be replayed");
    }

    /** No transition, no synthetic traffic: the steady state stays exactly as before. */
    @Test
    void steadyFocusPublishesNoSyntheticKeyEvents() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(held(Set.of(Key.LEFT_CONTROL), true));
        got.clear();
        pub.publish(held(Set.of(Key.LEFT_CONTROL), true));

        assertTrue(got.isEmpty(), "a steady focused frame with no edges must publish nothing, got " + got);
    }

    @Test
    void publishesKeyAndButtonEdgesAsEvents() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(frame(
                Set.of(Key.W), Set.of(Key.W), Set.of(),
                Set.of(MouseButton.LEFT), Set.of(MouseButton.LEFT), Set.of(),
                10, 20, ScrollDelta.ZERO, true));

        assertEquals(2, got.size());
        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.KeyPressed k && k.key() == Key.W));
        InputEvent.ButtonPressed bp = (InputEvent.ButtonPressed) got.stream()
                .filter(e -> e instanceof InputEvent.ButtonPressed).findFirst().orElseThrow();
        assertEquals(MouseButton.LEFT, bp.button());
        assertEquals(10, bp.x());
        assertEquals(20, bp.y());
    }

    @Test
    void publishesScrollAndFocusChange() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(frame(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                0, 0, new ScrollDelta(0, 2.0), false)); // focus was true by default → change to false

        assertTrue(got.stream().anyMatch(e -> e instanceof InputEvent.Scrolled s && s.yOffset() == 2.0));
        InputEvent.FocusChanged fc = (InputEvent.FocusChanged) got.stream()
                .filter(e -> e instanceof InputEvent.FocusChanged).findFirst().orElseThrow();
        assertEquals(false, fc.focused());
    }

    @Test
    void pointerPositionFlowsToStateAndCoalesces() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);

        assertEquals(0L, pub.pointer().version());

        pub.publish(frame(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                100, 200, ScrollDelta.ZERO, true));
        assertEquals(100, pub.pointer().value().x());
        assertEquals(200, pub.pointer().value().y());
        assertEquals(1L, pub.pointer().version());

        pub.publish(frame(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                100, 200, ScrollDelta.ZERO, true));
        assertEquals(1L, pub.pointer().version(), "unchanged position does not bump version");

        pub.publish(frame(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                101, 200, ScrollDelta.ZERO, true));
        assertEquals(2L, pub.pointer().version());
    }

    @Test
    void heldWithoutEdgeProducesNoEvents() {
        Atchung bus = Atchung.create();
        InputPublisher pub = new InputPublisher(bus);
        List<InputEvent> got = new ArrayList<>();
        bus.subscribe(pub.events(), got::add);

        pub.publish(frame(Set.of(Key.W), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                5, 5, ScrollDelta.ZERO, true));

        assertTrue(got.isEmpty(), "held-without-edge produces no discrete events");
    }
}
