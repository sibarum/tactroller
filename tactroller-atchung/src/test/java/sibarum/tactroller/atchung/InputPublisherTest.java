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
