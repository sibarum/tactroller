package sibarum.tactroller.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recognizer's decisions are classification (pan or pinch), boundaries (when a gesture starts and ends as fingers
 * come and go) and taps, so the tests sit on those. Positions are millimetres; times are written in milliseconds.
 */
class TouchpadGestureTest {

    private final TouchpadGesture g = new TouchpadGesture();   // 2mm, 250ms, 2mm slop
    private final List<GestureEvent> out = new ArrayList<>();

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    private static TouchpadContact c(int id, double x, double y) {
        return new TouchpadContact(id, x, y, x / 100, y / 60);
    }

    private void touch(long atMs, TouchpadContact... contacts) {
        out.addAll(g.feed(new InputEvent.TouchpadChanged(List.of(contacts), ms(atMs))));
    }

    private void clear(long atMs) {
        touch(atMs);
    }

    private <T extends GestureEvent> List<T> all(Class<T> type) {
        return out.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private double sumDx() {
        return all(GestureEvent.GestureUpdated.class).stream().mapToDouble(GestureEvent.GestureUpdated::dx).sum();
    }

    private double productScale() {
        return all(GestureEvent.GestureUpdated.class).stream()
                .mapToDouble(GestureEvent.GestureUpdated::scale).reduce(1, (a, b) -> a * b);
    }

    @Test
    void twoFingersMovingTogetherAreAPanCarryingOnlyTranslation() {
        touch(0, c(1, 40, 30), c(2, 60, 30));
        touch(10, c(1, 41, 30), c(2, 61, 30));
        assertTrue(out.isEmpty(), "1mm is under the threshold: " + out);
        touch(20, c(1, 43, 30.4), c(2, 63, 29.8));   // fingers drift apart a little, as real ones do
        touch(30, c(1, 50, 30.4), c(2, 70, 29.8));

        GestureEvent.GestureStarted s = assertInstanceOf(GestureEvent.GestureStarted.class, out.get(0));
        assertEquals(GestureEvent.Kind.PAN, s.kind());
        assertEquals(2, s.fingers());
        assertEquals(10, sumDx(), 1e-9, "no motion is lost to the threshold: the start's update carries it");
        for (GestureEvent.GestureUpdated u : all(GestureEvent.GestureUpdated.class)) {
            assertEquals(1.0, u.scale(), "a pan never zooms");
            assertEquals(0.0, u.rotation(), "a pan never turns");
        }
    }

    @Test
    void fingersSpreadingApartAreAPinchWhoseScalesMultiplyToTheSpreadRatio() {
        touch(0, c(1, 45, 30), c(2, 55, 30));
        touch(10, c(1, 43, 30), c(2, 57, 30));
        touch(20, c(1, 40, 30), c(2, 60, 30));
        touch(30, c(1, 35, 30), c(2, 65, 30));

        assertEquals(GestureEvent.Kind.PINCH, all(GestureEvent.GestureStarted.class).getFirst().kind());
        assertEquals(3.0, productScale(), 1e-9, "spread went from 10mm to 30mm");
        assertEquals(0, sumDx(), 1e-9, "the centre did not move");
    }

    @Test
    void aPinchWithOneFingerHeldStillIsStillAPinch() {
        // The moving finger shifts the centre exactly as far as it changes the spread -- the ambiguous case.
        touch(0, c(1, 40, 30), c(2, 50, 30));
        touch(10, c(1, 40, 30), c(2, 54, 30));
        assertEquals(GestureEvent.Kind.PINCH, all(GestureEvent.GestureStarted.class).getFirst().kind());
    }

    @Test
    void twistingIsAPinchWithClockwisePositiveRotation() {
        // Two fingers 20mm apart turning a quarter turn clockwise (y grows downwards) about (50, 30).
        int steps = 9;
        for (int i = 0; i <= steps; i++) {
            double a = Math.PI / 2 * i / steps;
            touch(i * 10L, c(1, 50 - 10 * Math.cos(a), 30 - 10 * Math.sin(a)),
                    c(2, 50 + 10 * Math.cos(a), 30 + 10 * Math.sin(a)));
        }
        assertEquals(GestureEvent.Kind.PINCH, all(GestureEvent.GestureStarted.class).getFirst().kind());
        double turned = all(GestureEvent.GestureUpdated.class).stream()
                .mapToDouble(GestureEvent.GestureUpdated::rotation).sum();
        assertEquals(Math.PI / 2, turned, 1e-9);
        assertEquals(1.0, productScale(), 1e-9);
    }

    @Test
    void threeFingerSwipeEndsWithItsReleaseVelocity() {
        touch(0, c(1, 30, 30), c(2, 40, 30), c(3, 50, 30));
        for (int i = 1; i <= 10; i++) {
            double dx = 3 * i;   // 3mm every 10ms: 300mm/s
            touch(i * 10L, c(1, 30 + dx, 30), c(2, 40 + dx, 30), c(3, 50 + dx, 30));
        }
        clear(110);

        assertEquals(3, all(GestureEvent.GestureStarted.class).getFirst().fingers());
        GestureEvent.GestureEnded end = all(GestureEvent.GestureEnded.class).getFirst();
        assertEquals(3, end.fingers());
        assertEquals(300, end.velocityX(), 1e-6);
        assertEquals(0, end.velocityY(), 1e-9);
    }

    @Test
    void fingersThatRestBeforeLiftingLeaveWithNoVelocity() {
        touch(0, c(1, 40, 30), c(2, 60, 30));
        touch(10, c(1, 50, 30), c(2, 70, 30));
        clear(400);
        assertEquals(0, all(GestureEvent.GestureEnded.class).getFirst().velocityX());
    }

    @Test
    void theFirstFingerToLiftEndsTheGestureAndTheRestStartNothing() {
        touch(0, c(1, 30, 30), c(2, 40, 30), c(3, 50, 30));
        touch(10, c(1, 40, 30), c(2, 50, 30), c(3, 60, 30));
        touch(20, c(1, 45, 30), c(2, 55, 30));          // one finger lifted
        touch(30, c(1, 60, 30), c(2, 70, 30));          // the other two keep sliding as they leave
        clear(40);

        assertEquals(1, all(GestureEvent.GestureStarted.class).size(), "no two-finger gesture from the leftovers");
        assertEquals(1, all(GestureEvent.GestureEnded.class).size());
        assertEquals(3, all(GestureEvent.GestureEnded.class).getFirst().fingers());
        assertInstanceOf(GestureEvent.GestureEnded.class, out.get(2), "ended on the lift, not when the pad cleared");
    }

    @Test
    void afterLiftingToOneFingerANewGestureCanBegin() {
        touch(0, c(1, 40, 30), c(2, 60, 30));
        touch(10, c(1, 45, 30), c(2, 65, 30));
        touch(20, c(1, 45, 30));                         // down to one: the pointer
        touch(30, c(1, 45, 30), c(3, 65, 30));           // a second finger back down
        touch(40, c(1, 50, 30), c(3, 70, 30));
        assertEquals(2, all(GestureEvent.GestureStarted.class).size());
    }

    @Test
    void fingersLandingOneByOneJoinTheGestureBeforeItIsDecided() {
        touch(0, c(1, 40, 30));
        touch(5, c(1, 40, 30), c(2, 50, 30));
        touch(10, c(1, 40, 30), c(2, 50, 30), c(3, 60, 30));
        touch(20, c(1, 45, 30), c(2, 55, 30), c(3, 65, 30));
        GestureEvent.GestureStarted s = all(GestureEvent.GestureStarted.class).getFirst();
        assertEquals(3, s.fingers());
        assertEquals(1, all(GestureEvent.GestureStarted.class).size());
    }

    @Test
    void aFingerAddedMidGestureEndsItAndTheNewCountMayStartAnother() {
        touch(0, c(1, 40, 30), c(2, 50, 30));
        touch(10, c(1, 45, 30), c(2, 55, 30));
        touch(20, c(1, 45, 30), c(2, 55, 30), c(3, 65, 30));
        touch(30, c(1, 50, 30), c(2, 60, 30), c(3, 70, 30));
        List<GestureEvent.GestureStarted> starts = all(GestureEvent.GestureStarted.class);
        assertEquals(List.of(2, 3), starts.stream().map(GestureEvent::fingers).toList());
        assertEquals(1, all(GestureEvent.GestureEnded.class).size());
    }

    @Test
    void oneFingerIsThePointerAndNeverAGesture() {
        touch(0, c(1, 40, 30));
        touch(10, c(1, 60, 30));
        clear(20);
        touch(100, c(1, 40, 30));
        clear(150);
        assertTrue(out.isEmpty(), out.toString());
    }

    @Test
    void aQuickStillTouchOfSeveralFingersIsATap() {
        touch(0, c(1, 40, 30), c(2, 50, 30));
        touch(30, c(1, 40.5, 30), c(2, 50, 30), c(3, 60, 30));
        touch(80, c(1, 40.5, 30));
        clear(100);
        GestureEvent.Tapped tap = assertInstanceOf(GestureEvent.Tapped.class, out.getFirst());
        assertEquals(3, tap.fingers(), "the most fingers down at once");
        assertEquals(1, out.size());
    }

    @Test
    void aSlowTouchIsNotATap() {
        touch(0, c(1, 40, 30), c(2, 50, 30));
        clear(400);
        assertTrue(out.isEmpty());
    }

    @Test
    void clickingThePadIsNotATap() {
        out.addAll(g.feed(new InputEvent.TouchpadChanged(List.of(c(1, 40, 30), c(2, 50, 30)), ms(0))));
        out.addAll(g.feed(new InputEvent.ButtonPressed(MouseButton.RIGHT, 5, 5, ms(20))));
        out.addAll(g.feed(new InputEvent.ButtonReleased(MouseButton.RIGHT, 5, 5, ms(60))));
        clear(80);
        assertTrue(out.isEmpty(), "a two-finger click is the OS's right-click, not a tap: " + out);

        touch(200, c(1, 40, 30), c(2, 50, 30));       // and the click does not poison the next touch
        clear(250);
        assertInstanceOf(GestureEvent.Tapped.class, out.getFirst());
    }

    @Test
    void gesturesCarryThePointerPosition() {
        out.addAll(g.feed(new InputEvent.PointerMoved(300, 200, 0, 0, ms(0))));
        touch(1, c(1, 40, 30), c(2, 60, 30));
        touch(10, c(1, 50, 30), c(2, 70, 30));
        GestureEvent first = out.getFirst();
        assertEquals(300, first.x());
        assertEquals(200, first.y());
    }

    @Test
    void framesDriveTheSameRecognizer() {
        TouchpadGesture fg = new TouchpadGesture();
        List<GestureEvent> got = new ArrayList<>();
        got.addAll(fg.feed(frame(0, List.of(c(1, 40, 30), c(2, 60, 30)))));
        got.addAll(fg.feed(frame(10, List.of(c(1, 50, 30), c(2, 70, 30)))));
        got.addAll(fg.feed(frame(20, List.of())));
        assertInstanceOf(GestureEvent.GestureStarted.class, got.get(0));
        assertInstanceOf(GestureEvent.GestureUpdated.class, got.get(1));
        assertInstanceOf(GestureEvent.GestureEnded.class, got.get(2));
        assertEquals(400, got.get(0).x());
    }

    @Test
    void resetAbandonsSilently() {
        touch(0, c(1, 40, 30), c(2, 60, 30));
        touch(10, c(1, 50, 30), c(2, 70, 30));
        assertTrue(g.isActive());
        out.clear();
        g.reset();
        assertFalse(g.isActive());
        touch(20, c(1, 60, 30), c(2, 80, 30));
        clear(30);
        assertTrue(out.isEmpty(), out.toString());
    }

    @Test
    void rejectsNonsenseParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TouchpadGesture(0, 250, 2));
        assertThrows(IllegalArgumentException.class, () -> new TouchpadGesture(2, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> new TouchpadGesture(2, 250, Double.NaN));
    }

    private static InputFrame frame(long atMs, List<TouchpadContact> contacts) {
        return new InputFrame(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), 400, 300, PointerDelta.ZERO,
                ScrollDelta.ZERO, true, ms(atMs), InputBackend.NO_CHARS, true, contacts);
    }
}
