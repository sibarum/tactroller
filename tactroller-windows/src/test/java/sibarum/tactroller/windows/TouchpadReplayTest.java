package sibarum.tactroller.windows;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.GestureEvent;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.TouchpadContact;
import sibarum.tactroller.api.TouchpadGesture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays a real capture — every HID report an ELAN precision touchpad sent while three and four fingers were put
 * down, held and lifted — through everything above the {@code hid.dll} calls: frame assembly, contact conversion and
 * gesture recognition. The device spreads each scan over one report per finger, so this is the case a decoder that
 * published per report, or dropped continuation reports, would get wrong.
 */
class TouchpadReplayTest {

    private static final String CAPTURE = "/elan-04f3-31fd-three-four-fingers.txt";

    /** The layout the ELAN device reports: one finger slot (link 1), contact count at the top level. */
    private static WindowsTouchpadDecoder.Layout elan() {
        WindowsTouchpadDecoder.Layout layout = new WindowsTouchpadDecoder.Layout();
        layout.value(new WindowsTouchpadDecoder.ValueCap(0x01, 1, false, 0x30, 0x30, 0, 4010, 0, 12750, 0x11, 0xD));
        layout.value(new WindowsTouchpadDecoder.ValueCap(0x01, 1, false, 0x31, 0x31, 0, 2468, 0, 7860, 0x11, 0xD));
        layout.value(new WindowsTouchpadDecoder.ValueCap(0x0D, 0, false, 0x54, 0x54, 0, 127, 0, 65535, 0x1001, 0xC));
        layout.hasConfidence = true;
        return layout;
    }

    @Test
    void aRealThreeAndFourFingerCaptureDecodesIntoWholeFramesAndGestures() throws IOException {
        WindowsTouchpadDecoder.Layout layout = elan();
        WindowsTouchpadDecoder.FrameAssembler frames = new WindowsTouchpadDecoder.FrameAssembler();
        TouchpadGesture gestures = new TouchpadGesture();
        List<GestureEvent> out = new ArrayList<>();
        List<TouchpadContact> current = List.of();
        int[] framesBySize = new int[8];
        int reports = 0;

        for (String line : capture()) {
            String[] f = line.split(" ");
            long nanos = Long.parseLong(f[0]) * 1_000_000L;
            int count = Integer.parseInt(f[1]);
            int[] slot = {Integer.parseInt(f[2]), f[3].charAt(0) == 'T' ? 1 : 0, f[3].charAt(1) == 'C' ? 1 : 0,
                    Integer.parseInt(f[4]), Integer.parseInt(f[5])};
            reports++;
            List<int[]> frame = frames.accept(count, List.of(slot));
            if (frame == null) {
                continue;
            }
            framesBySize[frame.size()]++;
            List<TouchpadContact> next = layout.contacts(frame, 0);
            if (!next.equals(current)) {
                current = next;
                out.addAll(gestures.feed(new InputEvent.TouchpadChanged(next, nanos)));
            }
        }
        out.addAll(gestures.feed(new InputEvent.TouchpadChanged(List.of(), Long.MAX_VALUE / 2)));

        assertTrue(reports > 6000, "the whole capture was read: " + reports);
        assertTrue(framesBySize[3] > 100, "three-finger scans assembled whole: " + framesBySize[3]);
        assertTrue(framesBySize[4] > 100, "four-finger scans assembled whole: " + framesBySize[4]);

        List<Integer> started = out.stream().filter(e -> e instanceof GestureEvent.GestureStarted)
                .map(GestureEvent::fingers).toList();
        long ended = out.stream().filter(e -> e instanceof GestureEvent.GestureEnded).count();
        assertEquals(started.size(), ended, "every start has its end");
        assertTrue(out.stream().anyMatch(e -> e.fingers() >= 3), "three- and four-finger touches were recognized: " + out);
        assertTrue(started.contains(3) && started.contains(4), "gestures of both counts: " + started);
    }

    private static List<String> capture() throws IOException {
        try (InputStream in = TouchpadReplayTest.class.getResourceAsStream(CAPTURE)) {
            assertNotNull(in, CAPTURE + " is missing from the test resources");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        }
    }
}
