package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The parts of touchpad decoding that need no device: assembling hybrid-mode reports into frames, and reading a
 * device's layout. The value caps used here are the ones the ELAN touchpad this was written against reports.
 */
class TouchpadDecoderTest {

    private static int[] slot(int id, int x) {
        return new int[] {id, 1, 1, x, 100};
    }

    @Test
    void aHybridScanIsPublishedOnlyOnceEveryAnnouncedContactHasArrived() {
        WindowsTouchpadDecoder.FrameAssembler a = new WindowsTouchpadDecoder.FrameAssembler();
        assertNull(a.accept(3, List.of(slot(0, 10))), "first of three");
        assertNull(a.accept(0, List.of(slot(1, 20))), "second of three");
        List<int[]> frame = a.accept(0, List.of(slot(2, 30)));
        assertEquals(3, frame.size());
        assertEquals(List.of(0, 1, 2), frame.stream().map(s -> s[0]).toList());
    }

    @Test
    void slotsBeyondTheCountArePaddingAndIgnored() {
        WindowsTouchpadDecoder.FrameAssembler a = new WindowsTouchpadDecoder.FrameAssembler();
        List<int[]> frame = a.accept(2, List.of(slot(4, 10), slot(5, 20), slot(0, 0), slot(0, 0), slot(0, 0)));
        assertEquals(2, frame.size());
    }

    @Test
    void aNewCountAbandonsAnUnfinishedScan() {
        WindowsTouchpadDecoder.FrameAssembler a = new WindowsTouchpadDecoder.FrameAssembler();
        assertNull(a.accept(3, List.of(slot(0, 10))));
        List<int[]> frame = a.accept(1, List.of(slot(7, 70)));
        assertEquals(1, frame.size());
        assertEquals(7, frame.getFirst()[0]);
    }

    @Test
    void aContinuationWithoutItsFirstReportIsDropped() {
        WindowsTouchpadDecoder.FrameAssembler a = new WindowsTouchpadDecoder.FrameAssembler();
        assertNull(a.accept(0, List.of(slot(1, 20))));
    }

    @Test
    void aDeviceWithoutAContactCountReportsWholeFrames() {
        WindowsTouchpadDecoder.FrameAssembler a = new WindowsTouchpadDecoder.FrameAssembler();
        assertEquals(2, a.accept(-1, List.of(slot(1, 10), slot(2, 20))).size());
    }

    @Test
    void theLayoutFindsFingerSlotsAndThePhysicalSize() {
        WindowsTouchpadDecoder.Layout layout = new WindowsTouchpadDecoder.Layout();
        // ELAN 04f3:31fd: one finger slot (link 1), X 0..4010 over 12750e-3 cm, Y 0..2468 over 7860e-3 cm.
        layout.value(cap(0x0D, 1, 0x51, 0, 15, 0, 0, 0, 0));
        layout.value(cap(0x01, 1, 0x30, 0, 4010, 0, 12750, 0x11, 0xD));
        layout.value(cap(0x01, 1, 0x31, 0, 2468, 0, 7860, 0x11, 0xD));
        layout.value(cap(0x0D, 0, 0x56, 0, 65535, 0, 65535, 0x1001, 0xC));
        layout.value(cap(0x0D, 0, 0x54, 0, 127, 0, 65535, 0x1001, 0xC));
        assertArrayEquals(new int[] {1}, layout.fingerLinks());
        assertEquals(0, layout.contactCountLink);
        double[] size = layout.sizeMm();
        assertEquals(127.5, size[0], 1e-9);
        assertEquals(78.6, size[1], 1e-9);
    }

    @Test
    void inchesAndMissingPhysicalSizesAreHandled() {
        WindowsTouchpadDecoder.Layout inches = new WindowsTouchpadDecoder.Layout();
        inches.value(cap(0x01, 2, 0x30, 0, 1000, 0, 400, 0x13, 0xE));   // 4.00 in
        inches.value(cap(0x01, 2, 0x31, 0, 500, 0, 250, 0x13, 0xE));    // 2.50 in
        assertEquals(101.6, inches.sizeMm()[0], 1e-9);
        assertEquals(63.5, inches.sizeMm()[1], 1e-9);

        WindowsTouchpadDecoder.Layout unknown = new WindowsTouchpadDecoder.Layout();
        unknown.value(cap(0x01, 1, 0x30, 0, 2000, 0, 0, 0, 0));
        unknown.value(cap(0x01, 1, 0x31, 0, 1000, 0, 0, 0, 0));
        assertEquals(WindowsTouchpadDecoder.ASSUMED_WIDTH_MM, unknown.sizeMm()[0]);
        assertEquals(WindowsTouchpadDecoder.ASSUMED_WIDTH_MM / 2, unknown.sizeMm()[1], 1e-9);
    }

    private static WindowsTouchpadDecoder.ValueCap cap(int page, int link, int usage, int lmin, int lmax,
                                                       int pmin, int pmax, int units, int exp) {
        return new WindowsTouchpadDecoder.ValueCap(page, link, false, usage, usage, lmin, lmax, pmin, pmax, units, exp);
    }
}
