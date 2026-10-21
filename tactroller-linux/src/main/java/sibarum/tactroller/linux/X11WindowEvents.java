package sibarum.tactroller.linux;

import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.ScrollDelta;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The event half of the Linux backend: one attached X11 window's typed characters, wheel, focus and
 * pointer-over state, read through XInput2 on a connection and a pump thread of its own.
 *
 * <p><b>Why XInput2, and why no button events.</b> The window's owner (the application's windowing layer) holds the
 * core event selection on the window, and a second client may not select core {@code ButtonPress} on it at all.
 * XI2 selections are per client, so this one can sit beside the owner's without taking anything from it. Buttons
 * are still not selected here: whichever client receives a press gets the implicit pointer grab, and taking that from
 * the owner would break the window manager's move and resize, which the owner starts from a press. The wheel is read
 * from the pointer's <em>scroll valuators</em> on {@code XI_Motion} instead (XI 2.1 smooth scrolling), which every
 * current server, XWayland included, provides and which involves no grab.
 *
 * <p><b>Occlusion comes free.</b> {@code XI_Enter}/{@code XI_Leave} are sent by the server against the real stacking
 * order, so "the pointer is over this window and nothing covers it there" is exactly whether the last crossing was an
 * enter.
 */
final class X11WindowEvents implements AutoCloseable {

    // XInput2 event types (XI2.h).
    private static final int XI_DeviceChanged = 1;
    private static final int XI_KeyPress = 2;
    private static final int XI_Motion = 6;
    private static final int XI_Enter = 7;
    private static final int XI_Leave = 8;
    private static final int XI_FocusIn = 9;
    private static final int XI_FocusOut = 10;

    private static final int XIAllDevices = 0;
    private static final int XIAllMasterDevices = 1;
    private static final int XIValuatorClass = 2;
    private static final int XIScrollClass = 3;
    private static final int XIScrollTypeVertical = 1;
    private static final int XIKeyRepeat = 1 << 16;

    private static final int GenericEvent = 35;
    private static final int KeyPress = 2;
    private static final long XEVENT_SIZE = 24 * 8;

    // ---- Struct layouts ---------------------------------------------------------------------------------------------

    private static final GroupLayout XIEventMask = MemoryLayout.structLayout(
            JAVA_INT.withName("deviceid"),
            JAVA_INT.withName("mask_len"),
            ADDRESS.withName("mask"));

    private static final GroupLayout XGenericEventCookie = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_INT.withName("extension"),
            JAVA_INT.withName("evtype"),
            JAVA_INT.withName("cookie"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("data"));

    /** {@code XIDeviceEvent}, whose head {@code XIEnterEvent} shares up to {@code event_y}. */
    private static final GroupLayout XIDeviceEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_INT.withName("extension"),
            JAVA_INT.withName("evtype"),
            JAVA_LONG.withName("time"),
            JAVA_INT.withName("deviceid"),
            JAVA_INT.withName("sourceid"),
            JAVA_INT.withName("detail"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("root"),
            JAVA_LONG.withName("event"),
            JAVA_LONG.withName("child"),
            JAVA_DOUBLE.withName("root_x"),
            JAVA_DOUBLE.withName("root_y"),
            JAVA_DOUBLE.withName("event_x"),
            JAVA_DOUBLE.withName("event_y"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
            JAVA_INT.withName("buttons_mask_len"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("buttons_mask"),
            JAVA_INT.withName("valuators_mask_len"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("valuators_mask"),
            ADDRESS.withName("valuators_values"),
            JAVA_INT.withName("mods_base"),
            JAVA_INT.withName("mods_latched"),
            JAVA_INT.withName("mods_locked"),
            JAVA_INT.withName("mods_effective"),
            JAVA_INT.withName("group_base"),
            JAVA_INT.withName("group_latched"),
            JAVA_INT.withName("group_locked"),
            JAVA_INT.withName("group_effective"));

    private static final GroupLayout XIDeviceInfo = MemoryLayout.structLayout(
            JAVA_INT.withName("deviceid"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("name"),
            JAVA_INT.withName("use"),
            JAVA_INT.withName("attachment"),
            JAVA_INT.withName("enabled"),
            JAVA_INT.withName("num_classes"),
            ADDRESS.withName("classes"));

    private static final GroupLayout XIValuatorClassInfo = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            JAVA_INT.withName("sourceid"),
            JAVA_INT.withName("number"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("label"),
            JAVA_DOUBLE.withName("min"),
            JAVA_DOUBLE.withName("max"),
            JAVA_DOUBLE.withName("value"),
            JAVA_INT.withName("resolution"),
            JAVA_INT.withName("mode"));

    private static final GroupLayout XIScrollClassInfo = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            JAVA_INT.withName("sourceid"),
            JAVA_INT.withName("number"),
            JAVA_INT.withName("scroll_type"),
            JAVA_DOUBLE.withName("increment"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4));

    /** The core {@code XKeyEvent}, built from an XI2 key press so {@code XLookupString} can translate it. */
    private static final GroupLayout XKeyEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window"),
            JAVA_LONG.withName("root"),
            JAVA_LONG.withName("subwindow"),
            JAVA_LONG.withName("time"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("x_root"),
            JAVA_INT.withName("y_root"),
            JAVA_INT.withName("state"),
            JAVA_INT.withName("keycode"),
            JAVA_INT.withName("same_screen"), MemoryLayout.paddingLayout(4));

    private static final GroupLayout POLLFD = MemoryLayout.structLayout(
            JAVA_INT.withName("fd"),
            JAVA_SHORT.withName("events"),
            JAVA_SHORT.withName("revents"));

    private static long off(GroupLayout layout, String field) {
        return layout.byteOffset(groupElement(field));
    }

    // ---- Native functions -------------------------------------------------------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIBS = Arena.ofAuto();

    private static final SymbolLookup X11 = SymbolLookup.libraryLookup("libX11.so.6", LIBS);
    private static final SymbolLookup XI = SymbolLookup.libraryLookup("libXi.so.6", LIBS);
    private static final SymbolLookup LIBC = SymbolLookup.libraryLookup("libc.so.6", LIBS);

    private static MethodHandle bind(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lib.findOrThrow(name), descriptor);
    }

    private static final MethodHandle XOpenDisplay = bind(X11, "XOpenDisplay",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle XCloseDisplay = bind(X11, "XCloseDisplay",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XConnectionNumber = bind(X11, "XConnectionNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XQueryExtension = bind(X11, "XQueryExtension",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle XPending = bind(X11, "XPending",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XNextEvent = bind(X11, "XNextEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle XGetEventData = bind(X11, "XGetEventData",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle XFreeEventData = bind(X11, "XFreeEventData",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle XFlush = bind(X11, "XFlush",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XLookupString = bind(X11, "XLookupString",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle XGetInputFocus = bind(X11, "XGetInputFocus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle XIQueryVersion = bind(XI, "XIQueryVersion",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle XISelectEvents = bind(XI, "XISelectEvents",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle XIQueryDevice = bind(XI, "XIQueryDevice",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle XIFreeDeviceInfo = bind(XI, "XIFreeDeviceInfo",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle poll = bind(LIBC, "poll",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle pipe2 = bind(LIBC, "pipe2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle write = bind(LIBC, "write",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle close = bind(LIBC, "close",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    // ---- State --------------------------------------------------------------------------------------------------------

    /** One scroll valuator of a device: which way it scrolls, how much is one notch, and where it last was. */
    private static final class ScrollAxis {
        final boolean vertical;
        final double increment;
        double last;
        boolean known;

        ScrollAxis(boolean vertical, double increment) {
            this.vertical = vertical;
            this.increment = increment;
        }
    }

    private final long window;
    private final MemorySegment display;
    private final Arena arena = Arena.ofShared();
    private final int[] stopPipe;
    private final Thread thread;
    private final int xiOpcode;

    /** {@code (deviceid << 16) | valuator number} to its axis; pump thread only. */
    private final Map<Long, ScrollAxis> axes = new HashMap<>();
    /** Keysyms to characters, dead keys included; pump thread only. */
    private final Xkb xkb = Xkb.open();

    private final Object lock = new Object();
    private final StringBuilder chars = new StringBuilder();
    private double scrollX;
    private double scrollY;

    private volatile boolean focused;
    private volatile boolean pointerInside;
    private volatile boolean stopping;

    @SuppressWarnings("restricted")
    X11WindowEvents(long window) throws BackendException {
        this.window = window;
        try {
            this.display = (MemorySegment) XOpenDisplay.invokeExact(MemorySegment.NULL);
            if (display.equals(MemorySegment.NULL)) {
                throw new BackendException("XOpenDisplay failed for the window event connection");
            }
            MemorySegment opcode = arena.allocate(JAVA_INT);
            MemorySegment ignoredEvent = arena.allocate(JAVA_INT);
            MemorySegment ignoredError = arena.allocate(JAVA_INT);
            int present = (int) XQueryExtension.invokeExact(display, arena.allocateFrom("XInputExtension"), opcode,
                    ignoredEvent, ignoredError);
            if (present == 0) {
                throw new BackendException("the X server has no XInput extension");
            }
            this.xiOpcode = opcode.get(JAVA_INT, 0);
            MemorySegment major = arena.allocateFrom(JAVA_INT, 2);
            MemorySegment minor = arena.allocateFrom(JAVA_INT, 2);
            int status = (int) XIQueryVersion.invokeExact(display, major, minor);
            if (status != 0 || major.get(JAVA_INT, 0) < 2) {
                throw new BackendException("XInput 2 unavailable (server offers " + major.get(JAVA_INT, 0) + "."
                        + minor.get(JAVA_INT, 0) + ")");
            }
            select();
            readInitialState();
            loadAxes();
            this.stopPipe = pipe();
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Failed to open the X11 window event connection", t);
        }
        this.thread = new Thread(this::run, "tactroller-x11-events");
        thread.setDaemon(true);
        thread.start();
    }

    private void select() throws Throwable {
        MemorySegment mask = arena.allocate(4);
        for (int type : new int[]{XI_DeviceChanged, XI_KeyPress, XI_Motion, XI_Enter, XI_Leave, XI_FocusIn,
                XI_FocusOut}) {
            int b = type / 8;
            mask.set(ValueLayout.JAVA_BYTE, b, (byte) (mask.get(ValueLayout.JAVA_BYTE, b) | (1 << (type % 8))));
        }
        MemorySegment em = arena.allocate(XIEventMask);
        em.set(JAVA_INT, off(XIEventMask, "deviceid"), XIAllMasterDevices);
        em.set(JAVA_INT, off(XIEventMask, "mask_len"), 4);
        em.set(ADDRESS, off(XIEventMask, "mask"), mask);
        int ignored = (int) XISelectEvents.invokeExact(display, window, em, 1);
        int ignored2 = (int) XFlush.invokeExact(display);
    }

    /**
     * Whether the window has the keyboard right now, before any focus event says so. The pointer starts outside:
     * a window mapped under the pointer is sent an enter by the server, so there is nothing to guess.
     */
    private void readInitialState() throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment focus = temp.allocate(JAVA_LONG);
            MemorySegment revert = temp.allocate(JAVA_INT);
            int ignored = (int) XGetInputFocus.invokeExact(display, focus, revert);
            focused = focus.get(JAVA_LONG, 0) == window;
        }
    }

    /** Every master device's scroll valuators, with where each one stands now. Pump thread (or constructor). */
    private void loadAxes() throws Throwable {
        axes.clear();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment count = temp.allocate(JAVA_INT);
            MemorySegment infos = (MemorySegment) XIQueryDevice.invokeExact(display, XIAllDevices, count);
            if (infos.equals(MemorySegment.NULL)) {
                return;
            }
            int n = count.get(JAVA_INT, 0);
            MemorySegment all = infos.reinterpret(XIDeviceInfo.byteSize() * n);
            Map<Long, Double> values = new HashMap<>();
            for (int i = 0; i < n; i++) {
                MemorySegment info = all.asSlice(XIDeviceInfo.byteSize() * i, XIDeviceInfo.byteSize());
                int deviceId = info.get(JAVA_INT, off(XIDeviceInfo, "deviceid"));
                int numClasses = info.get(JAVA_INT, off(XIDeviceInfo, "num_classes"));
                MemorySegment classes = info.get(ADDRESS, off(XIDeviceInfo, "classes"))
                        .reinterpret(ADDRESS.byteSize() * numClasses);
                for (int k = 0; k < numClasses; k++) {
                    MemorySegment cls = classes.getAtIndex(ADDRESS, k).reinterpret(XIValuatorClassInfo.byteSize());
                    int type = cls.get(JAVA_INT, 0);
                    if (type == XIScrollClass) {
                        int number = cls.get(JAVA_INT, off(XIScrollClassInfo, "number"));
                        boolean vertical = cls.get(JAVA_INT, off(XIScrollClassInfo, "scroll_type"))
                                == XIScrollTypeVertical;
                        double increment = cls.get(JAVA_DOUBLE, off(XIScrollClassInfo, "increment"));
                        axes.put(key(deviceId, number), new ScrollAxis(vertical, increment == 0 ? 1 : increment));
                    } else if (type == XIValuatorClass) {
                        values.put(key(deviceId, cls.get(JAVA_INT, off(XIValuatorClassInfo, "number"))),
                                cls.get(JAVA_DOUBLE, off(XIValuatorClassInfo, "value")));
                    }
                }
            }
            XIFreeDeviceInfo.invokeExact(infos);
            values.forEach((k, v) -> {
                ScrollAxis axis = axes.get(k);
                if (axis != null) {
                    axis.last = v;
                    axis.known = true;
                }
            });
        }
    }

    private static long key(int deviceId, int number) {
        return ((long) deviceId << 16) | number;
    }

    // ---- The pump thread ----------------------------------------------------------------------------------------------

    private void run() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment event = temp.allocate(XEVENT_SIZE, 8);
            MemorySegment fds = temp.allocate(POLLFD, 2);
            int xfd = (int) XConnectionNumber.invokeExact(display);
            fds.set(JAVA_INT, off(POLLFD, "fd"), xfd);
            fds.set(JAVA_SHORT, off(POLLFD, "events"), (short) 1);
            fds.set(JAVA_INT, POLLFD.byteSize() + off(POLLFD, "fd"), stopPipe[0]);
            fds.set(JAVA_SHORT, POLLFD.byteSize() + off(POLLFD, "events"), (short) 1);
            while (!stopping) {
                while ((int) XPending.invokeExact(display) > 0) {
                    int ignored = (int) XNextEvent.invokeExact(display, event);
                    if (event.get(JAVA_INT, 0) == GenericEvent
                            && event.get(JAVA_INT, off(XGenericEventCookie, "extension")) == xiOpcode
                            && (int) XGetEventData.invokeExact(display, event) != 0) {
                        try {
                            MemorySegment data = event.get(ADDRESS, off(XGenericEventCookie, "data"))
                                    .reinterpret(XIDeviceEvent.byteSize());
                            handle(event.get(JAVA_INT, off(XGenericEventCookie, "evtype")), data);
                        } finally {
                            XFreeEventData.invokeExact(display, event);
                        }
                    }
                }
                int ignored = (int) poll.invokeExact(fds, 2L, -1);
            }
        } catch (Throwable t) {
            if (!stopping) {
                System.err.println("tactroller: X11 window events stopped: " + t);
            }
        }
    }

    private void handle(int evtype, MemorySegment e) throws Throwable {
        if (evtype == XI_KeyPress) {
            onKey(e);
        } else if (evtype == XI_Motion) {
            onMotion(e);
        } else if (evtype == XI_Enter) {
            pointerInside = true;
            // Whatever the valuators did while the pointer was elsewhere was someone else's scrolling.
            loadAxes();
        } else if (evtype == XI_Leave) {
            pointerInside = false;
        } else if (evtype == XI_FocusIn) {
            focused = true;
        } else if (evtype == XI_FocusOut) {
            focused = false;
            xkb.reset();   // a dead key pressed here does not carry into another window
        } else if (evtype == XI_DeviceChanged) {
            loadAxes();
        }
    }

    /**
     * A key press as text, through the layout's keysym and then compose ({@link Xkb}). Repeats are dropped: typed characters never repeat, only command keys do, and those are
     * read from the key state rather than from here. Control characters ride the key channel, as on every backend.
     */
    private void onKey(MemorySegment e) throws Throwable {
        if ((e.get(JAVA_INT, off(XIDeviceEvent, "flags")) & XIKeyRepeat) != 0) {
            return;
        }
        int mods = e.get(JAVA_INT, off(XIDeviceEvent, "mods_effective"));
        int group = e.get(JAVA_INT, off(XIDeviceEvent, "group_effective"));
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment key = temp.allocate(XKeyEvent);
            key.set(JAVA_INT, off(XKeyEvent, "type"), KeyPress);
            key.set(ADDRESS, off(XKeyEvent, "display"), display);
            key.set(JAVA_LONG, off(XKeyEvent, "window"), window);
            key.set(JAVA_INT, off(XKeyEvent, "state"), (mods & 0xFF) | ((group & 0x3) << 13));
            key.set(JAVA_INT, off(XKeyEvent, "keycode"), e.get(JAVA_INT, off(XIDeviceEvent, "detail")));
            key.set(JAVA_INT, off(XKeyEvent, "same_screen"), 1);
            MemorySegment buf = temp.allocate(32);
            MemorySegment keysym = temp.allocate(JAVA_LONG);
            int ignored = (int) XLookupString.invokeExact(key, buf, 32, keysym, MemorySegment.NULL);
            int[] typed = xkb.press(keysym.get(JAVA_LONG, 0));
            if (typed.length > 0) {
                synchronized (lock) {
                    for (int cp : typed) {
                        chars.appendCodePoint(cp);
                    }
                }
            }
        }
    }

    /** Smooth scrolling: each scroll valuator that moved, as notches, against where it last stood. */
    private void onMotion(MemorySegment e) {
        int deviceId = e.get(JAVA_INT, off(XIDeviceEvent, "deviceid"));
        int maskLen = e.get(JAVA_INT, off(XIDeviceEvent, "valuators_mask_len"));
        if (maskLen <= 0) {
            return;
        }
        MemorySegment mask = e.get(ADDRESS, off(XIDeviceEvent, "valuators_mask")).reinterpret(maskLen);
        MemorySegment values = e.get(ADDRESS, off(XIDeviceEvent, "valuators_values"))
                .reinterpret(JAVA_DOUBLE.byteSize() * maskLen * 8L);
        int index = 0;
        double dx = 0;
        double dy = 0;
        for (int bit = 0; bit < maskLen * 8; bit++) {
            if ((mask.get(ValueLayout.JAVA_BYTE, bit / 8) & (1 << (bit % 8))) == 0) {
                continue;
            }
            double value = values.getAtIndex(JAVA_DOUBLE, index++);
            ScrollAxis axis = axes.get(key(deviceId, bit));
            if (axis == null) {
                continue;
            }
            if (axis.known) {
                double notches = (value - axis.last) / axis.increment;
                if (axis.vertical) {
                    dy -= notches;   // valuators grow downward; the API's positive y is away from the user
                } else {
                    dx += notches;
                }
            }
            axis.last = value;
            axis.known = true;
        }
        if (dx != 0 || dy != 0) {
            synchronized (lock) {
                scrollX += dx;
                scrollY += dy;
            }
        }
    }

    // ---- Read by the backend, from the loop thread --------------------------------------------------------------------

    boolean focused() {
        return focused;
    }

    boolean pointerInside() {
        return pointerInside;
    }

    ScrollDelta drainScroll() {
        synchronized (lock) {
            if (scrollX == 0 && scrollY == 0) {
                return ScrollDelta.ZERO;
            }
            ScrollDelta d = new ScrollDelta(scrollX, scrollY);
            scrollX = 0;
            scrollY = 0;
            return d;
        }
    }

    int[] drainChars() {
        synchronized (lock) {
            if (chars.isEmpty()) {
                return null;
            }
            int[] cps = chars.codePoints().toArray();
            chars.setLength(0);
            return cps;
        }
    }

    @Override
    public void close() {
        stopping = true;
        try (Arena temp = Arena.ofConfined()) {
            long ignored = (long) write.invokeExact(stopPipe[1], temp.allocate(1), 1L);
            thread.join(1000);
            int a = (int) close.invokeExact(stopPipe[0]);
            int b = (int) close.invokeExact(stopPipe[1]);
            int c = (int) XCloseDisplay.invokeExact(display);
            xkb.close();
        } catch (Throwable ignored) {
            // best effort
        } finally {
            arena.close();
        }
    }

    private static int[] pipe() throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment fds = temp.allocate(JAVA_INT, 2);
            int rc = (int) pipe2.invokeExact(fds, 0x80000 /* O_CLOEXEC */);
            if (rc != 0) {
                throw new BackendException("pipe2 failed");
            }
            return new int[]{fds.getAtIndex(JAVA_INT, 0), fds.getAtIndex(JAVA_INT, 1)};
        }
    }
}
