package sibarum.tactroller.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recognizer's job is separating a drag from a click (distance) and from a twitch (time), so most
 * of these tests sit on one of those two boundaries or on the ways a gesture can end.
 *
 * <p>Times are written in milliseconds and converted, because the thresholds are stated in milliseconds
 * while the event stream is in nanoseconds, and mixing the two silently is how a hold threshold ends up
 * a million times too short.
 */
class DragGestureTest {

    private final DragGesture g = new DragGesture();   // 2px, 100ms, LEFT
    private final List<DragEvent> out = new ArrayList<>();

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    /** Tracks the press row, so horizontal-only moves need not restate it. */
    private int row;

    private void press(long atMs, int x, int y) {
        row = y;
        out.addAll(g.feed(new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, ms(atMs))));
    }

    private void move(long atMs, int x, int y, int dx, int dy) {
        out.addAll(g.feed(new InputEvent.PointerMoved(x, y, dx, dy, ms(atMs))));
    }

    /** A move along the press row: most tests only need one axis to cross a threshold. */
    private void move(long atMs, int x, int dx, int dy) {
        move(atMs, x, row, dx, dy);
    }

    private void release(long atMs, int x, int y) {
        out.addAll(g.feed(new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, ms(atMs))));
    }

    private void escape(long atMs) {
        out.addAll(g.feed(new InputEvent.KeyPressed(Key.ESCAPE, ms(atMs))));
    }

    private void tick(long atMs) {
        out.addAll(g.tick(ms(atMs)));
    }

    // ---- the distance boundary: drag or click -------------------------------

    @Test
    void pressAloneEmitsNothing() {
        press(0, 10, 10);
        assertTrue(out.isEmpty(), "a press may still become either a click or a drag");
        assertFalse(g.isDragging());
    }

    @Test
    void aPressThatNeverTravelsEmitsNothingAtAll() {
        press(0, 10, 10);
        move(50, 11, 10, 1, 0);      // 1px, under the 2px distance
        release(300, 11, 10);        // held well past 100ms, so only distance can have excluded it

        assertTrue(out.isEmpty(), "a click is left entirely to the raw button events");
    }

    @Test
    void crossingTheDistanceAfterTheHoldStartsTheDragImmediately() {
        press(0, 10, 10);
        move(150, 15, 10, 5, 0);

        assertEquals(1, out.size());
        DragEvent.DragStarted s = assertInstanceOf(DragEvent.DragStarted.class, out.get(0));
        assertEquals(10, s.startX());
        assertEquals(15, s.x());
        assertEquals(5, s.offsetX(), "offset accumulates from the press, not just the last move");
        assertTrue(g.isDragging());
        assertEquals(MouseButton.LEFT, g.activeButton());
    }

    @Test
    void distanceLatchesSoReturningToThePressPointStillDrags() {
        press(0, 10, 10);
        move(20, 40, 30, 0);
        move(40, 10, -30, 0);        // back where it began, before the hold elapsed
        tick(150);

        assertInstanceOf(DragEvent.DragStarted.class, out.get(0),
                "having travelled is a fact about the gesture, not about the current position");
    }

    // ---- the time boundary: when the drag becomes visible -------------------

    @Test
    void theHoldDelaysTheStartButDoesNotPreventIt() {
        press(0, 10, 10);
        move(20, 20, 10, 0);         // distance met at 20ms
        assertTrue(out.isEmpty(), "too early: the hold debounces the start");
        assertFalse(g.isDragging());

        tick(99);
        assertTrue(out.isEmpty());
        tick(100);

        assertEquals(1, out.size());
        assertInstanceOf(DragEvent.DragStarted.class, out.get(0));
        assertTrue(g.isDragging());
    }

    @Test
    void aFlickReleasedBeforeTheHoldIsStillADragNotAClick() {
        press(0, 100, 100);
        move(30, 150, 50, 0);        // 50px in 30ms -- distance met, hold not
        assertTrue(out.isEmpty());
        release(40, 150, 100);

        assertEquals(2, out.size(), "the start is synthesized so the consumer can build a payload");
        DragEvent.DragStarted s = assertInstanceOf(DragEvent.DragStarted.class, out.get(0));
        DragEvent.DragEnded e = assertInstanceOf(DragEvent.DragEnded.class, out.get(1));
        assertFalse(e.cancelled());
        assertEquals(100, s.startX());
        assertEquals(150, e.x(), "reporting this as a click would put it 50px from the press");
    }

    @Test
    void aSilentStreamStillResolvesAtReleaseWithoutAnyTick() {
        press(0, 0, 0);
        move(10, 20, 20, 0);
        release(500, 20, 0);         // no tick ever called, and no motion after 10ms

        assertEquals(2, out.size(), "tick makes the start punctual; it is not required for correctness");
        assertInstanceOf(DragEvent.DragStarted.class, out.get(0));
        assertInstanceOf(DragEvent.DragEnded.class, out.get(1));
    }

    @Test
    void tickBeforeTheDistanceIsMetDoesNothing() {
        press(0, 10, 10);
        move(10, 11, 1, 0);
        tick(5_000);

        assertTrue(out.isEmpty(), "waiting is not travelling; time alone never starts a drag");
    }

    @Test
    void tickWithNoGestureInFlightDoesNothing() {
        tick(1_000);
        press(0, 0, 0);
        release(10, 0, 0);
        out.clear();
        tick(2_000);

        assertTrue(out.isEmpty());
    }

    @Test
    void tickDoesNotStartASecondDrag() {
        press(0, 0, 0);
        move(150, 20, 20, 0);
        out.clear();
        tick(200);
        tick(300);

        assertTrue(out.isEmpty(), "DragStarted is emitted exactly once per gesture");
    }

    // ---- in flight ----------------------------------------------------------

    @Test
    void movesAfterTheStartAreDragOverNotSecondStarts() {
        press(0, 0, 0);
        move(150, 10, 10, 0);
        move(160, 20, 5, 10, 5);
        move(170, 30, 5, 10, 0);

        assertEquals(3, out.size());
        assertInstanceOf(DragEvent.DragStarted.class, out.get(0));
        DragEvent.DragOver d = assertInstanceOf(DragEvent.DragOver.class, out.get(2));
        assertEquals(30, d.offsetX());
        assertEquals(5, d.offsetY());
        assertEquals(10, d.dx(), "the per-move delta survives alongside the accumulated offset");
    }

    @Test
    void releaseAfterADragIsADropAtTheReleasePosition() {
        press(0, 0, 0);
        move(150, 20, 20, 0);
        release(200, 400, 300);      // far outside the window: pointer capture delivered it anyway

        DragEvent.DragEnded e = assertInstanceOf(DragEvent.DragEnded.class, out.get(1));
        assertFalse(e.cancelled());
        assertEquals(400, e.x(), "the drop point comes from the release, which may move without a move event");
        assertEquals(300, e.y());
        assertEquals(0, e.startX());
        assertFalse(g.isDragging());
    }

    // ---- cancellation -------------------------------------------------------

    @Test
    void escapeCancelsAndTheReleaseThenProducesNothing() {
        press(0, 0, 0);
        move(150, 20, 20, 0);
        escape(160);
        move(170, 40, 20, 0);
        release(180, 40, 0);

        assertEquals(2, out.size());
        DragEvent.DragEnded e = assertInstanceOf(DragEvent.DragEnded.class, out.get(1));
        assertTrue(e.cancelled(), "a cancel must be distinguishable from a drop, or the drop lands twice");
        assertFalse(g.isDragging());
    }

    @Test
    void escapeAfterCancelCannotRestartTheDragOnTheSameHeldButton() {
        press(0, 0, 0);
        move(150, 20, 20, 0);
        escape(160);
        move(170, 60, 40, 0);        // would cross the distance all over again
        tick(500);

        assertEquals(2, out.size(), "only the button coming up can begin a new gesture");
        assertFalse(g.isDragging());
    }

    @Test
    void escapeBeforeTheStartSuppressesTheGestureSilently() {
        press(0, 0, 0);
        move(10, 20, 20, 0);         // distance met, hold not elapsed
        escape(20);
        assertTrue(out.isEmpty(), "nothing had started, so there is nothing to end");
        release(30, 20, 0);

        assertTrue(out.isEmpty(), "the flick must not be resurrected by the release");
    }

    @Test
    void escapeWithNoGestureIsIgnored() {
        escape(10);
        press(20, 0, 0);
        release(30, 0, 0);

        assertTrue(out.isEmpty());
    }

    // ---- buttons ------------------------------------------------------------

    @Test
    void aSecondButtonDoesNotStealTheGestureInFlight() {
        press(0, 0, 0);
        move(150, 20, 20, 0);
        out.addAll(g.feed(new InputEvent.ButtonPressed(MouseButton.RIGHT, 20, 0, ms(160))));
        out.addAll(g.feed(new InputEvent.ButtonReleased(MouseButton.RIGHT, 20, 0, ms(170))));
        move(180, 30, 0, 10, 0);
        release(190, 30, 0);

        assertEquals(3, out.size(), "the other button contributed nothing at all");
        assertEquals(MouseButton.LEFT, ((DragEvent.DragEnded) out.get(2)).button());
    }

    @Test
    void anUnwatchedButtonIsIgnoredEntirely() {
        out.addAll(g.feed(new InputEvent.ButtonPressed(MouseButton.RIGHT, 0, 0, ms(0))));
        move(150, 40, 40, 0);
        out.addAll(g.feed(new InputEvent.ButtonReleased(MouseButton.RIGHT, 40, 0, ms(200))));

        assertTrue(out.isEmpty());
    }

    // ---- configuration ------------------------------------------------------

    @Test
    void zeroHoldPromotesAsSoonAsTheDistanceIsMet() {
        DragGesture eager = new DragGesture(2, 0, Set.of(MouseButton.LEFT));
        eager.feed(new InputEvent.ButtonPressed(MouseButton.LEFT, 0, 0, 0));
        List<DragEvent> now = eager.feed(new InputEvent.PointerMoved(5, 0, 5, 0, ms(1)));

        assertInstanceOf(DragEvent.DragStarted.class, now.get(0), "distance-only, like the platform toolkits");
    }

    @Test
    void configuredDistanceAndButtonAreHonoured() {
        DragGesture right = new DragGesture(20, 0, Set.of(MouseButton.RIGHT));
        List<DragEvent> seen = new ArrayList<>();

        seen.addAll(right.feed(new InputEvent.ButtonPressed(MouseButton.RIGHT, 0, 0, 0)));
        seen.addAll(right.feed(new InputEvent.PointerMoved(15, 0, 15, 0, ms(1))));
        assertTrue(seen.isEmpty(), "15 is under the configured 20");
        seen.addAll(right.feed(new InputEvent.PointerMoved(25, 0, 10, 0, ms(2))));

        assertInstanceOf(DragEvent.DragStarted.class, seen.get(0));
    }

    @Test
    void zeroDistanceAndZeroHoldStartOnTheFirstMotion() {
        DragGesture eager = new DragGesture(0, 0, Set.of(MouseButton.LEFT));
        eager.feed(new InputEvent.ButtonPressed(MouseButton.LEFT, 0, 0, 0));

        assertInstanceOf(DragEvent.DragStarted.class,
                eager.feed(new InputEvent.PointerMoved(1, 0, 1, 0, ms(1))).get(0));
    }

    @Test
    void zeroDistanceStillIgnoresAMoveThatDidNotMove() {
        DragGesture eager = new DragGesture(0, 0, Set.of(MouseButton.LEFT));
        eager.feed(new InputEvent.ButtonPressed(MouseButton.LEFT, 0, 0, 0));

        assertTrue(eager.feed(new InputEvent.PointerMoved(0, 0, 0, 0, ms(1))).isEmpty(),
                "a zero delta is not motion, whatever the threshold");
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DragGesture(-1, 100, Set.of(MouseButton.LEFT)));
        assertThrows(IllegalArgumentException.class, () -> new DragGesture(2, -1, Set.of(MouseButton.LEFT)));
        assertThrows(IllegalArgumentException.class, () -> new DragGesture(2, 100, Set.of()));
        assertThrows(NullPointerException.class, () -> new DragGesture(2, 100, null));
        assertThrows(NullPointerException.class, () -> g.feed(null));
    }

    // ---- housekeeping -------------------------------------------------------

    @Test
    void motionBeforeThePressDoesNotCountTowardTheDistance() {
        move(0, 50, 50, 50, 50);
        press(10, 50, 50);
        move(200, 51, 50, 1, 0);
        release(300, 51, 50);

        assertTrue(out.isEmpty(), "pre-press motion must not leak into a later press");
    }

    @Test
    void resetAbandonsTheGestureSilently() {
        press(0, 0, 0);
        move(150, 20, 20, 0);
        out.clear();
        g.reset();
        release(200, 20, 0);

        assertTrue(out.isEmpty());
        assertFalse(g.isDragging());
        assertNull(g.activeButton());
    }

    @Test
    void unrelatedEventsPassThroughHarmlessly() {
        press(0, 0, 0);
        out.addAll(g.feed(new InputEvent.Scrolled(0, 1, 0, 0, ms(10))));
        out.addAll(g.feed(new InputEvent.CharTyped('a', ms(20))));
        out.addAll(g.feed(new InputEvent.FocusChanged(false, ms(30))));
        move(150, 20, 20, 0);
        release(200, 20, 0);

        assertEquals(2, out.size(), "focus loss does not cancel a drag; the drop still lands");
        assertInstanceOf(DragEvent.DragStarted.class, out.get(0));
        assertFalse(((DragEvent.DragEnded) out.get(1)).cancelled());
    }
}
