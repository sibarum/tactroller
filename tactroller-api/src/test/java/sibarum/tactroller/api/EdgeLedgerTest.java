package sibarum.tactroller.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeLedgerTest {

    private static final EdgeLedger.Projection SCREEN = (x, y) -> new int[]{x, y};
    private static final Set<Key> NO_KEYS = Set.of();
    private static final Set<MouseButton> NO_BUTTONS = Set.of();

    private static EdgeLedger primed() {
        EdgeLedger ledger = new EdgeLedger();
        ledger.settle(List.of(), NO_KEYS, NO_BUTTONS, 0, 0, 0L, SCREEN);
        return ledger;
    }

    private static InputEdge press(int t) {
        return InputEdge.button(MouseButton.LEFT, true, 10 + t, 20, t);
    }

    private static InputEdge release(int t) {
        return InputEdge.button(MouseButton.LEFT, false, 10 + t, 20, t);
    }

    /** The point of the queue: a click that began and ended between two polls left no state, and is still a click. */
    @Test
    void aClickInsideOneFrameIsAPressAndARelease() {
        List<InputEdge> edges = primed().settle(List.of(press(1), release(2)), NO_KEYS, NO_BUTTONS, 0, 0, 9L, SCREEN);
        assertEquals(List.of(press(1), release(2)), edges);
    }

    /** And two of them are two, each where and when it happened: a double click needs both, with their times. */
    @Test
    void aDoubleClickInsideOneFrameIsTwoClicks() {
        List<InputEdge> queued = List.of(press(1), release(2), press(3), release(4));
        assertEquals(queued, primed().settle(queued, NO_KEYS, NO_BUTTONS, 0, 0, 9L, SCREEN));
    }

    /** Without a queue it is the diff of two polls, which is what every backend had before. */
    @Test
    void withoutAQueueItIsTheDiffOfTwoPolls() {
        EdgeLedger ledger = primed();
        assertEquals(List.of(InputEdge.button(MouseButton.LEFT, true, 5, 6, 7L)),
                ledger.settle(List.of(), NO_KEYS, Set.of(MouseButton.LEFT), 5, 6, 7L, SCREEN));
        assertEquals(List.of(InputEdge.button(MouseButton.LEFT, false, 8, 9, 10L)),
                ledger.settle(List.of(), NO_KEYS, NO_BUTTONS, 8, 9, 10L, SCREEN));
    }

    /**
     * The poll can run ahead of the queue: the button is up now, but its release is still on its way from the OS.
     * The release comes from the poll now, and the queued one changes nothing next frame, so nothing is said twice.
     */
    @Test
    void aQueueBehindThePollIsSettledWithoutRepeating() {
        EdgeLedger ledger = primed();
        assertEquals(List.of(press(1), InputEdge.button(MouseButton.LEFT, false, 0, 0, 9L)),
                ledger.settle(List.of(press(1)), NO_KEYS, NO_BUTTONS, 0, 0, 9L, SCREEN));
        assertTrue(ledger.settle(List.of(release(2)), NO_KEYS, NO_BUTTONS, 0, 0, 10L, SCREEN).isEmpty());
    }

    /** The same race the other way: down by the poll before the press is queued, so the late press is dropped. */
    @Test
    void aLatePressIsNotAPressTwice() {
        EdgeLedger ledger = primed();
        assertEquals(1, ledger.settle(List.of(), NO_KEYS, Set.of(MouseButton.LEFT), 0, 0, 1L, SCREEN).size());
        assertEquals(List.of(release(3)),
                ledger.settle(List.of(press(2), release(3)), NO_KEYS, NO_BUTTONS, 0, 0, 4L, SCREEN));
    }

    /** A key held down repeats its press in the OS; it is still down, so no edge. */
    @Test
    void keyRepeatIsNotAnEdge() {
        EdgeLedger ledger = primed();
        InputEdge down = InputEdge.key(Key.A, true, 1L);
        assertEquals(List.of(down), ledger.settle(List.of(down), Set.of(Key.A), NO_BUTTONS, 0, 0, 2L, SCREEN));
        assertTrue(ledger.settle(List.of(InputEdge.key(Key.A, true, 3L), InputEdge.key(Key.A, true, 4L)),
                Set.of(Key.A), NO_BUTTONS, 0, 0, 5L, SCREEN).isEmpty());
    }

    /** What was held before anyone listened is the baseline, not a press. */
    @Test
    void theFirstSettleIsTheBaseline() {
        EdgeLedger ledger = new EdgeLedger();
        assertTrue(ledger.settle(List.of(press(1)), Set.of(Key.A), Set.of(MouseButton.LEFT), 0, 0, 2L, SCREEN)
                .isEmpty());
        assertTrue(ledger.settle(List.of(), Set.of(Key.A), Set.of(MouseButton.LEFT), 0, 0, 3L, SCREEN).isEmpty());
    }

    /** A queued button position is a screen point, and the frame's space is the caller's. */
    @Test
    void queuedPositionsAreProjected() {
        EdgeLedger.Projection client = (x, y) -> new int[]{x - 100, y - 200};
        List<InputEdge> edges = primed().settle(List.of(InputEdge.button(MouseButton.RIGHT, true, 150, 260, 1L),
                InputEdge.button(MouseButton.RIGHT, false, 151, 261, 2L)), NO_KEYS, NO_BUTTONS, 0, 0, 3L, client);
        assertEquals(List.of(InputEdge.button(MouseButton.RIGHT, true, 50, 60, 1L),
                InputEdge.button(MouseButton.RIGHT, false, 51, 61, 2L)), edges);
    }
}
