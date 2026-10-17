package sibarum.tactroller.windows;

import sibarum.tactroller.api.TouchpadContact;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Turns Windows Precision Touchpad HID reports into {@link TouchpadContact}s. Owned by {@link WindowsRawInputHub}
 * and called only on its pump thread, with the {@code RIM_TYPEHID} reports that arrive for the Digitizer/Touch Pad
 * usage the hub registers.
 *
 * <h2>Reading a report</h2>
 *
 * <p>A precision touchpad's report layout is not fixed: each device describes its own, and the HID parser in
 * {@code hid.dll} reads it from the device's <em>preparsed data</em>. Each finger slot is a link collection carrying
 * a Contact Identifier, X, Y and the Tip Switch and Confidence bits; the top-level collection carries the Contact
 * Count. The first report from a device learns that layout once ({@link Device}).
 *
 * <h2>Hybrid reporting</h2>
 *
 * <p>A device may have fewer finger slots per report than fingers it can track, and then a single scan of the
 * surface arrives as several reports: the first carries the scan's contact count, the rest carry a count of zero and
 * continue filling slots. Most laptop touchpads work this way — the one this was written against reports one finger
 * per report. So reports are assembled into frames ({@link FrameAssembler}), and a frame is published only when it
 * holds every contact its first report announced; publishing per report would make three fingers look like one
 * finger, then two, then three, every scan.
 *
 * <p>A frame lists every contact on the surface, so it replaces the device's previous contacts outright. A finger
 * lifting is reported once with Tip Switch clear, and a palm with Confidence clear; neither counts as a contact.
 */
final class WindowsTouchpadDecoder {

    private static final int HIDP_STATUS_SUCCESS = 0x00110000;
    private static final int HIDP_INPUT = 0;
    private static final int RIDI_PREPARSEDDATA = 0x20000005;

    private static final short PAGE_GENERIC_DESKTOP = 0x01;
    private static final short PAGE_DIGITIZER = 0x0D;
    private static final int USAGE_X = 0x30;
    private static final int USAGE_Y = 0x31;
    private static final int USAGE_TIP_SWITCH = 0x42;
    private static final int USAGE_CONFIDENCE = 0x47;
    private static final int USAGE_CONTACT_ID = 0x51;
    private static final int USAGE_CONTACT_COUNT = 0x54;

    // HIDP_CAPS / HIDP_VALUE_CAPS / HIDP_BUTTON_CAPS layouts (x64).
    private static final int CAPS_SIZE = 64;
    private static final int CAPS_NUM_INPUT_BUTTON_CAPS = 46;
    private static final int CAPS_NUM_INPUT_VALUE_CAPS = 48;
    private static final int VALUE_CAPS_SIZE = 72;
    private static final int VC_USAGE_PAGE = 0;
    private static final int VC_LINK_COLLECTION = 6;
    private static final int VC_IS_RANGE = 12;
    private static final int VC_UNITS_EXP = 32;
    private static final int VC_UNITS = 36;
    private static final int VC_LOGICAL_MIN = 40;
    private static final int VC_LOGICAL_MAX = 44;
    private static final int VC_PHYSICAL_MIN = 48;
    private static final int VC_PHYSICAL_MAX = 52;
    private static final int VC_USAGE = 56;        // NotRange.Usage, or Range.UsageMin
    private static final int VC_USAGE_MAX = 58;    // Range.UsageMax

    /** Width assumed for a device that does not say how big it is; the height follows its logical aspect. */
    static final double ASSUMED_WIDTH_MM = 100.0;

    /** Contact ids from different devices are kept apart by putting the device's ordinal above the device's id. */
    private static final int DEVICE_ID_SHIFT = 16;

    private final Arena arena;
    private final MethodHandle getRawInputDeviceInfo;
    private final MethodHandle hidpGetCaps;
    private final MethodHandle hidpGetValueCaps;
    private final MethodHandle hidpGetButtonCaps;
    private final MethodHandle hidpGetUsageValue;
    private final MethodHandle hidpGetUsages;

    // Pump-thread-only scratch.
    private final MemorySegment value;
    private final MemorySegment usageList;
    private final MemorySegment usageLength;
    private static final int USAGE_LIST_CAPACITY = 32;

    /** Devices by RawInput handle; a null value is a device whose layout could not be read, so it is skipped. */
    private final Map<Long, Device> devices = new HashMap<>();

    @SuppressWarnings("restricted")
    WindowsTouchpadDecoder(Arena arena, Linker linker, SymbolLookup user32) {
        this.arena = arena;
        SymbolLookup hid = SymbolLookup.libraryLookup("hid", arena);
        getRawInputDeviceInfo = linker.downcallHandle(user32.findOrThrow("GetRawInputDeviceInfoW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        hidpGetCaps = linker.downcallHandle(hid.findOrThrow("HidP_GetCaps"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        hidpGetValueCaps = linker.downcallHandle(hid.findOrThrow("HidP_GetValueCaps"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        hidpGetButtonCaps = linker.downcallHandle(hid.findOrThrow("HidP_GetButtonCaps"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        hidpGetUsageValue = linker.downcallHandle(hid.findOrThrow("HidP_GetUsageValue"), FunctionDescriptor.of(
                JAVA_INT, JAVA_INT, JAVA_SHORT, JAVA_SHORT, JAVA_SHORT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        hidpGetUsages = linker.downcallHandle(hid.findOrThrow("HidP_GetUsages"), FunctionDescriptor.of(
                JAVA_INT, JAVA_INT, JAVA_SHORT, JAVA_SHORT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        value = arena.allocate(JAVA_INT);
        usageList = arena.allocate(JAVA_SHORT, USAGE_LIST_CAPACITY);
        usageLength = arena.allocate(JAVA_INT);
    }

    /**
     * Decode one HID report from {@code hDevice}.
     *
     * @return every contact on every touchpad, when this report completed a frame; null when it did not, so there
     *         is nothing new to publish
     */
    List<TouchpadContact> decode(MemorySegment hDevice, MemorySegment report) throws Throwable {
        long key = hDevice.address();
        Device device = devices.get(key);
        if (device == null && !devices.containsKey(key)) {
            device = learn(hDevice, devices.size());
            devices.put(key, device);
        }
        if (device == null) {
            return null;
        }
        if (!device.accept(report)) {
            return null;
        }
        List<TouchpadContact> all = new ArrayList<>();
        for (Device d : devices.values()) {
            if (d != null) {
                all.addAll(d.contacts);
            }
        }
        return List.copyOf(all);
    }

    /** Read a device's layout from its preparsed data; null if it is not a touchpad this decoder can read. */
    private Device learn(MemorySegment hDevice, int ordinal) throws Throwable {
        MemorySegment size = arena.allocate(JAVA_INT);
        int r = (int) getRawInputDeviceInfo.invokeExact(hDevice, RIDI_PREPARSEDDATA, MemorySegment.NULL, size);
        if (r != 0 || size.get(JAVA_INT, 0) <= 0) {
            return null;
        }
        MemorySegment preparsed = arena.allocate(size.get(JAVA_INT, 0), 8);
        r = (int) getRawInputDeviceInfo.invokeExact(hDevice, RIDI_PREPARSEDDATA, preparsed, size);
        if (r <= 0) {
            return null;
        }
        MemorySegment caps = arena.allocate(CAPS_SIZE);
        if ((int) hidpGetCaps.invokeExact(preparsed, caps) != HIDP_STATUS_SUCCESS) {
            return null;
        }

        int valueCount = caps.get(JAVA_SHORT, CAPS_NUM_INPUT_VALUE_CAPS) & 0xFFFF;
        MemorySegment vcs = arena.allocate((long) VALUE_CAPS_SIZE * Math.max(1, valueCount));
        MemorySegment length = arena.allocate(JAVA_SHORT);
        length.set(JAVA_SHORT, 0, (short) valueCount);
        if (valueCount == 0
                || (int) hidpGetValueCaps.invokeExact(HIDP_INPUT, vcs, length, preparsed) != HIDP_STATUS_SUCCESS) {
            return null;
        }
        Layout layout = new Layout();
        for (int i = 0; i < (length.get(JAVA_SHORT, 0) & 0xFFFF); i++) {
            MemorySegment vc = vcs.asSlice((long) i * VALUE_CAPS_SIZE, VALUE_CAPS_SIZE);
            layout.value(new ValueCap(
                    vc.get(JAVA_SHORT, VC_USAGE_PAGE) & 0xFFFF,
                    vc.get(JAVA_SHORT, VC_LINK_COLLECTION) & 0xFFFF,
                    vc.get(java.lang.foreign.ValueLayout.JAVA_BYTE, VC_IS_RANGE) != 0,
                    vc.get(JAVA_SHORT, VC_USAGE) & 0xFFFF,
                    vc.get(JAVA_SHORT, VC_USAGE_MAX) & 0xFFFF,
                    vc.get(JAVA_INT, VC_LOGICAL_MIN), vc.get(JAVA_INT, VC_LOGICAL_MAX),
                    vc.get(JAVA_INT, VC_PHYSICAL_MIN), vc.get(JAVA_INT, VC_PHYSICAL_MAX),
                    vc.get(JAVA_INT, VC_UNITS), vc.get(JAVA_INT, VC_UNITS_EXP)));
        }

        int buttonCount = caps.get(JAVA_SHORT, CAPS_NUM_INPUT_BUTTON_CAPS) & 0xFFFF;
        if (buttonCount > 0) {
            // HIDP_BUTTON_CAPS is the same size as HIDP_VALUE_CAPS and shares the fields read here.
            MemorySegment bcs = arena.allocate((long) VALUE_CAPS_SIZE * buttonCount);
            length.set(JAVA_SHORT, 0, (short) buttonCount);
            if ((int) hidpGetButtonCaps.invokeExact(HIDP_INPUT, bcs, length, preparsed) == HIDP_STATUS_SUCCESS) {
                for (int i = 0; i < (length.get(JAVA_SHORT, 0) & 0xFFFF); i++) {
                    long o = (long) i * VALUE_CAPS_SIZE;
                    int page = bcs.get(JAVA_SHORT, o + VC_USAGE_PAGE) & 0xFFFF;
                    int min = bcs.get(JAVA_SHORT, o + VC_USAGE) & 0xFFFF;
                    boolean range = bcs.get(java.lang.foreign.ValueLayout.JAVA_BYTE, o + VC_IS_RANGE) != 0;
                    int max = range ? bcs.get(JAVA_SHORT, o + VC_USAGE_MAX) & 0xFFFF : min;
                    if (page == PAGE_DIGITIZER && min <= USAGE_CONFIDENCE && USAGE_CONFIDENCE <= max) {
                        layout.hasConfidence = true;
                    }
                }
            }
        }
        return layout.fingerLinks().length == 0 ? null : new Device(ordinal, preparsed, layout);
    }

    // ---- The layout, learned once per device (pure, so it can be tested without a device) -----------------

    /** One HIDP_VALUE_CAPS entry, reduced to what the layout needs. */
    record ValueCap(int page, int link, boolean isRange, int usage, int usageMax, int logicalMin, int logicalMax,
                    int physicalMin, int physicalMax, int units, int unitsExp) {

        boolean covers(int p, int u) {
            return page == p && (isRange ? usage <= u && u <= usageMax : usage == u);
        }

        /** The span of this axis in millimetres, or NaN if the device does not give it in a unit we know. */
        double physicalMm() {
            double perUnit = switch (units & 0xFF) {
                case 0x11 -> 10.0;   // SI linear, centimetres
                case 0x13 -> 25.4;   // English linear, inches
                default -> Double.NaN;
            };
            int exp = unitsExp & 0xF;
            if (exp >= 8) {
                exp -= 16;           // a 4-bit two's-complement nibble
            }
            double span = (double) physicalMax - physicalMin;
            return span > 0 ? span * Math.pow(10, exp) * perUnit : Double.NaN;
        }
    }

    /** Where a device keeps each finger and how big its surface is. */
    static final class Layout {
        final Map<Integer, ValueCap> xByLink = new HashMap<>();
        final Map<Integer, ValueCap> yByLink = new HashMap<>();
        int contactCountLink = -1;
        boolean hasConfidence;

        void value(ValueCap vc) {
            if (vc.covers(PAGE_GENERIC_DESKTOP, USAGE_X)) {
                xByLink.putIfAbsent(vc.link(), vc);
            } else if (vc.covers(PAGE_GENERIC_DESKTOP, USAGE_Y)) {
                yByLink.putIfAbsent(vc.link(), vc);
            } else if (vc.covers(PAGE_DIGITIZER, USAGE_CONTACT_COUNT) && contactCountLink < 0) {
                contactCountLink = vc.link();
            }
        }

        /** The finger slots: link collections carrying both an X and a Y, in link order. */
        int[] fingerLinks() {
            return xByLink.keySet().stream().filter(yByLink::containsKey).sorted().mapToInt(Integer::intValue).toArray();
        }

        /** {@code [width, height]} of the surface in millimetres. */
        double[] sizeMm() {
            int first = fingerLinks()[0];
            ValueCap x = xByLink.get(first);
            ValueCap y = yByLink.get(first);
            double w = x.physicalMm();
            double h = y.physicalMm();
            if (Double.isNaN(w) || Double.isNaN(h)) {
                // No usable physical size: assume a typical width, and take the aspect from the logical ranges,
                // which is right for the common case of square device units.
                w = ASSUMED_WIDTH_MM;
                h = w * span(y) / span(x);
            }
            return new double[] {w, h};
        }

        /**
         * A completed frame's slots as contacts: lifted fingers (tip clear) and palms (confidence clear) dropped,
         * positions in millimetres and normalized. {@code ordinal} keeps ids from different devices apart.
         */
        List<TouchpadContact> contacts(List<int[]> frame, int ordinal) {
            int first = fingerLinks()[0];
            ValueCap xc = xByLink.get(first);
            ValueCap yc = yByLink.get(first);
            double[] size = sizeMm();
            List<TouchpadContact> out = new ArrayList<>(frame.size());
            for (int[] s : frame) {
                boolean tip = s[1] != 0;
                boolean confident = !hasConfidence || s[2] != 0;
                if (tip && confident) {
                    double u = clamp((s[3] - xc.logicalMin()) / span(xc));
                    double v = clamp((s[4] - yc.logicalMin()) / span(yc));
                    out.add(new TouchpadContact((ordinal << DEVICE_ID_SHIFT) | s[0], u * size[0], v * size[1], u, v));
                }
            }
            return List.copyOf(out);
        }

        static double span(ValueCap c) {
            return Math.max(1, (double) c.logicalMax() - c.logicalMin());
        }
    }

    /**
     * Assembles reports into whole frames, for devices that spread one scan across several reports. Each slot is
     * {@code [contactId, tip, confident, x, y]}.
     */
    static final class FrameAssembler {
        private int expected;
        private final List<int[]> pending = new ArrayList<>();

        /**
         * @param contactCount the report's contact count: the frame's size in its first report, 0 in a continuation,
         *                     -1 for a device that reports no count (every report is then a whole frame)
         * @param slots        the report's finger slots, in order
         * @return the completed frame's slots, or null while it is incomplete
         */
        List<int[]> accept(int contactCount, List<int[]> slots) {
            if (contactCount < 0) {
                return slots;
            }
            if (contactCount > 0) {
                expected = contactCount;   // a new scan; anything left of an unfinished one is dropped with it
                pending.clear();
            } else if (expected == 0) {
                return null;               // a continuation whose first report we never saw
            }
            for (int[] slot : slots) {
                if (pending.size() < expected) {
                    pending.add(slot);     // slots past the count are unused padding
                }
            }
            if (pending.size() < expected) {
                return null;
            }
            List<int[]> frame = List.copyOf(pending);
            pending.clear();
            expected = 0;
            return frame;
        }
    }

    /** One touchpad: its layout and its current contacts. */
    private final class Device {
        private final int ordinal;
        private final MemorySegment preparsed;
        private final Layout layout;
        private final int[] fingerLinks;
        private final FrameAssembler frames = new FrameAssembler();
        List<TouchpadContact> contacts = List.of();

        Device(int ordinal, MemorySegment preparsed, Layout layout) {
            this.ordinal = ordinal;
            this.preparsed = preparsed;
            this.layout = layout;
            this.fingerLinks = layout.fingerLinks();
        }

        /** @return whether this report completed a frame, replacing {@link #contacts}. */
        boolean accept(MemorySegment report) throws Throwable {
            int count = -1;
            if (layout.contactCountLink >= 0) {
                count = value(report, PAGE_DIGITIZER, layout.contactCountLink, USAGE_CONTACT_COUNT);
                if (count < 0) {
                    return false;          // not a contact report (another report id on the same collection)
                }
            }
            List<int[]> slots = new ArrayList<>(fingerLinks.length);
            for (int link : fingerLinks) {
                int x = value(report, PAGE_GENERIC_DESKTOP, link, USAGE_X);
                int y = value(report, PAGE_GENERIC_DESKTOP, link, USAGE_Y);
                int id = value(report, PAGE_DIGITIZER, link, USAGE_CONTACT_ID);
                if (x < 0 || y < 0) {
                    continue;
                }
                int buttons = buttons(report, link);
                slots.add(new int[] {Math.max(id, 0), buttons & 1, (buttons >> 1) & 1, x, y});
            }
            List<int[]> frame = frames.accept(count, slots);
            if (frame == null) {
                return false;
            }
            contacts = layout.contacts(frame, ordinal);
            return true;
        }

        /** A value usage, or -1 if the report does not carry it. */
        private int value(MemorySegment report, short page, int link, int usage) throws Throwable {
            int st = (int) hidpGetUsageValue.invokeExact(HIDP_INPUT, page, (short) link, (short) usage, value,
                    preparsed, report, (int) report.byteSize());
            return st == HIDP_STATUS_SUCCESS ? value.get(JAVA_INT, 0) : -1;
        }

        /** The finger's tip switch (bit 0) and confidence (bit 1). */
        private int buttons(MemorySegment report, int link) throws Throwable {
            usageLength.set(JAVA_INT, 0, USAGE_LIST_CAPACITY);
            int st = (int) hidpGetUsages.invokeExact(HIDP_INPUT, PAGE_DIGITIZER, (short) link, usageList,
                    usageLength, preparsed, report, (int) report.byteSize());
            if (st != HIDP_STATUS_SUCCESS) {
                return 0;
            }
            int bits = 0;
            for (int i = 0; i < usageLength.get(JAVA_INT, 0); i++) {
                int u = usageList.getAtIndex(JAVA_SHORT, i) & 0xFFFF;
                if (u == USAGE_TIP_SWITCH) {
                    bits |= 1;
                } else if (u == USAGE_CONFIDENCE) {
                    bits |= 2;
                }
            }
            return bits;
        }
    }

    private static double clamp(double d) {
        return d < 0 ? 0 : d > 1 ? 1 : d;
    }
}
