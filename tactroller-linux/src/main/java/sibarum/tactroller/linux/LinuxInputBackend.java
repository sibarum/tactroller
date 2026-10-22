package sibarum.tactroller.linux;

import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.InputBackend;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.PointerState;
import sibarum.tactroller.api.ScrollDelta;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Linux/X11 {@link InputBackend} backed by {@code libX11} via the Panama FFM API.
 *
 * <ul>
 *   <li>{@code Display* XOpenDisplay(char*)} / {@code XCloseDisplay} — connect to the X server.</li>
 *   <li>{@code Window XDefaultRootWindow(Display*)} — the root window to query against.</li>
 *   <li>{@code Bool XQueryPointer(...)} — global pointer position and button mask.</li>
 *   <li>{@code XQueryKeymap(Display*, char[32])} + {@code XKeysymToKeycode} — key state.</li>
 * </ul>
 *
 * libX11 is the standard X11 client library; it is bound by soname and not bundled. This backend
 * targets X11 (or XWayland). A native Wayland path would use libinput/evdev instead and can be
 * added as a sibling backend later.
 *
 * <p>Pointer and key <em>state</em> are polled here, on the loop thread. Everything that only exists as an
 * event — typed characters, the wheel, focus, whether the pointer is over the window — comes from
 * {@link X11WindowEvents}, which runs while a window is {@linkplain #attach attached}.
 *
 * <p><b>No press is lost between polls.</b> The loop derives presses and releases from consecutive polls, so a click
 * quicker than a frame would vanish. While a window is attached, each poll also takes the presses and releases seen
 * since the last one ({@link #settle}): a button reported up stays up only if it was never pressed meanwhile, and one
 * reported down stays down only if it was never released. A whole click between two polls is then down in one and up
 * in the next, one frame late rather than never.
 */
public final class LinuxInputBackend implements InputBackend {

    // X11 button mask bits (X.h).
    private static final int BUTTON1_MASK = 1 << 8;  // left
    private static final int BUTTON2_MASK = 1 << 9;  // middle
    private static final int BUTTON3_MASK = 1 << 10; // right

    private Arena arena;
    private MethodHandle xOpenDisplay;
    private MethodHandle xCloseDisplay;
    private MethodHandle xDefaultRootWindow;
    private MethodHandle xQueryPointer;
    private MethodHandle xQueryKeymap;
    private MethodHandle xKeysymToKeycode;
    private MethodHandle xTranslateCoordinates;
    private MethodHandle xWarpPointer;
    private MethodHandle xFlush;

    private long attachedWindow;
    private X11WindowEvents windowEvents;

    private MemorySegment display;
    private long rootWindow;

    // Query-pointer out-parameters (reused across polls).
    private MemorySegment rootReturn;
    private MemorySegment childReturn;
    private MemorySegment rootX;
    private MemorySegment rootY;
    private MemorySegment winX;
    private MemorySegment winY;
    private MemorySegment maskReturn;
    private MemorySegment keymap; // char[32]

    private final Map<Key, Long> keysymByKey = buildKeysymMap();
    private final Map<Key, Integer> keycodeByKey = new EnumMap<>(Key.class);

    // What the last poll reported, and the edges drained into it (loop/poll thread only).
    private int reportedButtons;
    private final Set<Key> reportedKeys = EnumSet.noneOf(Key.class);
    private final byte[] keysPressed = new byte[32];
    private final byte[] keysReleased = new byte[32];

    @Override
    public String name() {
        return "linux-x11";
    }

    @Override
    @SuppressWarnings("restricted")
    public void initialize() throws BackendException {
        try {
            arena = Arena.ofShared();
            Linker linker = Linker.nativeLinker();
            SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena);

            xOpenDisplay = linker.downcallHandle(
                    x11.findOrThrow("XOpenDisplay"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            xCloseDisplay = linker.downcallHandle(
                    x11.findOrThrow("XCloseDisplay"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            xDefaultRootWindow = linker.downcallHandle(
                    x11.findOrThrow("XDefaultRootWindow"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            xQueryPointer = linker.downcallHandle(
                    x11.findOrThrow("XQueryPointer"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // display
                            ValueLayout.JAVA_LONG, // window
                            ValueLayout.ADDRESS,   // root_return
                            ValueLayout.ADDRESS,   // child_return
                            ValueLayout.ADDRESS,   // root_x_return
                            ValueLayout.ADDRESS,   // root_y_return
                            ValueLayout.ADDRESS,   // win_x_return
                            ValueLayout.ADDRESS,   // win_y_return
                            ValueLayout.ADDRESS)); // mask_return
            xQueryKeymap = linker.downcallHandle(
                    x11.findOrThrow("XQueryKeymap"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            xKeysymToKeycode = linker.downcallHandle(
                    x11.findOrThrow("XKeysymToKeycode"),
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            xTranslateCoordinates = linker.downcallHandle(
                    x11.findOrThrow("XTranslateCoordinates"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // display
                            ValueLayout.JAVA_LONG, // src_w
                            ValueLayout.JAVA_LONG, // dest_w
                            ValueLayout.JAVA_INT,  // src_x
                            ValueLayout.JAVA_INT,  // src_y
                            ValueLayout.ADDRESS,   // dest_x_return
                            ValueLayout.ADDRESS,   // dest_y_return
                            ValueLayout.ADDRESS)); // child_return
            xWarpPointer = linker.downcallHandle(
                    x11.findOrThrow("XWarpPointer"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            xFlush = linker.downcallHandle(
                    x11.findOrThrow("XFlush"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            display = (MemorySegment) xOpenDisplay.invokeExact(MemorySegment.NULL);
            if (display.equals(MemorySegment.NULL)) {
                throw new BackendException("XOpenDisplay failed; no X11 display available (is $DISPLAY set?)");
            }
            rootWindow = (long) xDefaultRootWindow.invokeExact(display);

            rootReturn = arena.allocate(ValueLayout.JAVA_LONG);
            childReturn = arena.allocate(ValueLayout.JAVA_LONG);
            rootX = arena.allocate(ValueLayout.JAVA_INT);
            rootY = arena.allocate(ValueLayout.JAVA_INT);
            winX = arena.allocate(ValueLayout.JAVA_INT);
            winY = arena.allocate(ValueLayout.JAVA_INT);
            maskReturn = arena.allocate(ValueLayout.JAVA_INT);
            keymap = arena.allocate(32);

            // Resolve keysyms to keycodes once against the live server keymap.
            for (Map.Entry<Key, Long> e : keysymByKey.entrySet()) {
                byte kc = (byte) xKeysymToKeycode.invokeExact(display, (long) e.getValue());
                keycodeByKey.put(e.getKey(), kc & 0xFF);
            }
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Failed to bind/initialise libX11", t);
        }
    }

    @Override
    public PointerState pollPointer() throws BackendException {
        try {
            int onSameScreen = (int) xQueryPointer.invokeExact(
                    display, rootWindow, rootReturn, childReturn, rootX, rootY, winX, winY, maskReturn);
            if (onSameScreen == 0) {
                // Pointer is on another screen; root_x/root_y are still valid for the queried root.
            }
            int x = rootX.get(ValueLayout.JAVA_INT, 0);
            int y = rootY.get(ValueLayout.JAVA_INT, 0);
            int mask = maskReturn.get(ValueLayout.JAVA_INT, 0);
            X11WindowEvents events = windowEvents;
            if (events != null) {
                long edges = events.drainButtonEdges();
                int settled = 0;
                for (int bit : new int[]{BUTTON1_MASK, BUTTON2_MASK, BUTTON3_MASK}) {
                    if (settle((reportedButtons & bit) != 0, (mask & bit) != 0,
                            ((int) edges & bit) != 0, ((int) (edges >>> 32) & bit) != 0)) {
                        settled |= bit;
                    }
                }
                mask = settled;
            }
            reportedButtons = mask;

            Set<MouseButton> buttons = EnumSet.noneOf(MouseButton.class);
            if ((mask & BUTTON1_MASK) != 0) buttons.add(MouseButton.LEFT);
            if ((mask & BUTTON2_MASK) != 0) buttons.add(MouseButton.MIDDLE);
            if ((mask & BUTTON3_MASK) != 0) buttons.add(MouseButton.RIGHT);

            return new PointerState(x, y, buttons);
        } catch (Throwable t) {
            throw new BackendException("Native pointer poll failed", t);
        }
    }

    /**
     * What a poll reports for one button or key: its live state, unless an edge since the last poll says the last
     * report is stale. Down last time and released since: up, even if pressed again (the next poll sees that). Up
     * last time and pressed since: down, even if released again (the next poll sees that).
     */
    private static boolean settle(boolean wasDown, boolean live, boolean pressedSince, boolean releasedSince) {
        return wasDown ? live && !releasedSince : live || pressedSince;
    }

    /** The live state of one button, without taking the edges a poll would. */
    @Override
    public boolean isButtonDown(MouseButton button) throws BackendException {
        try {
            int ignored = (int) xQueryPointer.invokeExact(
                    display, rootWindow, rootReturn, childReturn, rootX, rootY, winX, winY, maskReturn);
            int mask = maskReturn.get(ValueLayout.JAVA_INT, 0);
            return (mask & switch (button) {
                case LEFT -> BUTTON1_MASK;
                case MIDDLE -> BUTTON2_MASK;
                case RIGHT -> BUTTON3_MASK;
            }) != 0;
        } catch (Throwable t) {
            throw new BackendException("Native button poll failed for " + button, t);
        }
    }

    @Override
    public boolean isKeyDown(Key key) throws BackendException {
        Integer keycode = keycodeByKey.get(key);
        if (keycode == null || keycode == 0) {
            return false; // unobservable key reported as not-down (keeps pollKeys robust)
        }
        try {
            int ok = (int) xQueryKeymap.invokeExact(display, keymap);
            if (ok == 0) {
                throw new BackendException("XQueryKeymap failed");
            }
            int kc = keycode;
            int b = keymap.get(ValueLayout.JAVA_BYTE, kc / 8) & 0xFF;
            return (b & (1 << (kc % 8))) != 0;
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Native key poll failed for " + key, t);
        }
    }

    @Override
    public Set<Key> pollKeys() throws BackendException {
        try {
            int ok = (int) xQueryKeymap.invokeExact(display, keymap);
            if (ok == 0) {
                throw new BackendException("XQueryKeymap failed");
            }
            X11WindowEvents events = windowEvents;
            if (events != null) {
                events.drainKeyEdges(keysPressed, keysReleased);
            }
            EnumSet<Key> down = EnumSet.noneOf(Key.class);
            for (Map.Entry<Key, Integer> e : keycodeByKey.entrySet()) {
                int kc = e.getValue();
                if (kc == 0) {
                    continue;
                }
                int bit = 1 << (kc % 8);
                boolean live = (keymap.get(ValueLayout.JAVA_BYTE, kc / 8) & bit) != 0;
                boolean isDown = events == null ? live
                        : settle(reportedKeys.contains(e.getKey()), live,
                                (keysPressed[kc / 8] & bit) != 0, (keysReleased[kc / 8] & bit) != 0);
                if (isDown) {
                    down.add(e.getKey());
                }
            }
            reportedKeys.clear();
            reportedKeys.addAll(down);
            return down;
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Native keymap poll failed", t);
        }
    }

    // ---- The attached window -----------------------------------------------------------------------------------

    /**
     * Attach an X11 window: from now on focus, pointer-over, typed characters and the wheel are read for it.
     * Re-attaching replaces the previous window.
     */
    @Override
    public void attach(NativeWindow window) throws BackendException {
        if (window.kind() != NativeWindow.Kind.X11_WINDOW) {
            throw new BackendException(name() + " attaches X11 windows, not " + window.kind());
        }
        detach();
        windowEvents = new X11WindowEvents(window.handle());
        attachedWindow = window.handle();
    }

    @Override
    public NativeWindow window(long osHandle) {
        return NativeWindow.ofX11Window(osHandle);
    }

    @Override
    public void detach() {
        if (windowEvents != null) {
            windowEvents.close();
            windowEvents = null;
        }
        attachedWindow = 0L;
    }

    @Override
    public boolean isWindowAttached() {
        return windowEvents != null;
    }

    @Override
    public boolean isFocused() {
        X11WindowEvents events = windowEvents;
        return events == null || events.focused();
    }

    @Override
    public boolean isPointerTarget() {
        X11WindowEvents events = windowEvents;
        return events == null || events.pointerInside();
    }

    @Override
    public int[] toClient(int screenX, int screenY) {
        return attachedWindow == 0L ? new int[]{screenX, screenY} : translate(rootWindow, attachedWindow, screenX, screenY);
    }

    @Override
    public int[] toScreen(int clientX, int clientY) {
        return attachedWindow == 0L ? new int[]{clientX, clientY} : translate(attachedWindow, rootWindow, clientX, clientY);
    }

    private int[] translate(long from, long to, int x, int y) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment dx = temp.allocate(ValueLayout.JAVA_INT);
            MemorySegment dy = temp.allocate(ValueLayout.JAVA_INT);
            MemorySegment child = temp.allocate(ValueLayout.JAVA_LONG);
            int ok = (int) xTranslateCoordinates.invokeExact(display, from, to, x, y, dx, dy, child);
            return ok == 0 ? new int[]{x, y} : new int[]{dx.get(ValueLayout.JAVA_INT, 0), dy.get(ValueLayout.JAVA_INT, 0)};
        } catch (Throwable t) {
            return new int[]{x, y};
        }
    }

    @Override
    public boolean supportsPointerWarp() {
        return true;
    }

    /** {@code XWarpPointer} to a root-window point. XWayland honours it only while the pointer is over this client. */
    @Override
    public void warpPointer(int screenX, int screenY) throws BackendException {
        try {
            int ignored = (int) xWarpPointer.invokeExact(display, 0L, rootWindow, 0, 0, 0, 0, screenX, screenY);
            int ignored2 = (int) xFlush.invokeExact(display);
        } catch (Throwable t) {
            throw new BackendException("XWarpPointer failed", t);
        }
    }

    @Override
    public ScrollDelta drainScroll() {
        X11WindowEvents events = windowEvents;
        return events == null ? ScrollDelta.ZERO : events.drainScroll();
    }

    @Override
    public int[] drainChars() {
        X11WindowEvents events = windowEvents;
        int[] chars = events == null ? null : events.drainChars();
        return chars == null ? NO_CHARS : chars;
    }

    @Override
    public void close() {
        detach();
        try {
            if (display != null && !display.equals(MemorySegment.NULL) && xCloseDisplay != null) {
                int ignored = (int) xCloseDisplay.invokeExact(display);
            }
        } catch (Throwable ignored) {
            // best effort
        } finally {
            display = null;
            if (arena != null) {
                arena.close();
                arena = null;
            }
        }
    }

    /** X11 keysyms (keysymdef.h): Latin-1 for printable keys, 0xFF-prefixed for function keys. */
    private static Map<Key, Long> buildKeysymMap() {
        Map<Key, Long> m = new EnumMap<>(Key.class);
        for (char c = 'A'; c <= 'Z'; c++) {
            // Use lowercase Latin-1 keysyms; the keycode is layout position, shift-independent.
            m.put(Key.valueOf(String.valueOf(c)), (long) Character.toLowerCase(c));
        }
        for (int d = 0; d <= 9; d++) {
            m.put(Key.valueOf("DIGIT_" + d), (long) ('0' + d));
        }
        m.put(Key.SPACE, 0x20L);
        m.put(Key.ENTER, 0xFF0DL);
        m.put(Key.ESCAPE, 0xFF1BL);
        m.put(Key.TAB, 0xFF09L);
        m.put(Key.BACKSPACE, 0xFF08L);
        m.put(Key.DELETE, 0xFFFFL);
        m.put(Key.LEFT, 0xFF51L);
        m.put(Key.UP, 0xFF52L);
        m.put(Key.RIGHT, 0xFF53L);
        m.put(Key.DOWN, 0xFF54L);
        m.put(Key.LEFT_SHIFT, 0xFFE1L);
        m.put(Key.RIGHT_SHIFT, 0xFFE2L);
        m.put(Key.LEFT_CONTROL, 0xFFE3L);
        m.put(Key.RIGHT_CONTROL, 0xFFE4L);
        m.put(Key.LEFT_ALT, 0xFFE9L);
        m.put(Key.RIGHT_ALT, 0xFFEAL);
        m.put(Key.LEFT_SUPER, 0xFFEBL);
        m.put(Key.RIGHT_SUPER, 0xFFECL);
        for (int f = 1; f <= 12; f++) {
            m.put(Key.valueOf("F" + f), 0xFFBEL + (f - 1));
        }
        m.put(Key.INSERT, 0xFF63L);
        m.put(Key.HOME, 0xFF50L);
        m.put(Key.END, 0xFF57L);
        m.put(Key.PAGE_UP, 0xFF55L);
        m.put(Key.PAGE_DOWN, 0xFF56L);
        m.put(Key.CAPS_LOCK, 0xFFE5L);
        for (int n = 0; n <= 9; n++) {
            m.put(Key.valueOf("NUMPAD_" + n), 0xFFB0L + n);
        }
        m.put(Key.NUMPAD_ADD, 0xFFABL);
        m.put(Key.NUMPAD_SUBTRACT, 0xFFADL);
        m.put(Key.NUMPAD_MULTIPLY, 0xFFAAL);
        m.put(Key.NUMPAD_DIVIDE, 0xFFAFL);
        m.put(Key.NUMPAD_DECIMAL, 0xFFAEL);
        m.put(Key.NUMPAD_ENTER, 0xFF8DL);
        m.put(Key.MINUS, (long) '-');
        m.put(Key.EQUAL, (long) '=');
        m.put(Key.LEFT_BRACKET, (long) '[');
        m.put(Key.RIGHT_BRACKET, (long) ']');
        m.put(Key.BACKSLASH, (long) '\\');
        m.put(Key.SEMICOLON, (long) ';');
        m.put(Key.APOSTROPHE, (long) '\'');
        m.put(Key.COMMA, (long) ',');
        m.put(Key.PERIOD, (long) '.');
        m.put(Key.SLASH, (long) '/');
        m.put(Key.GRAVE_ACCENT, (long) '`');
        return m;
    }
}
