package sibarum.tactroller.linux;

import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.InputBackend;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.PointerState;

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
                byte kc = (byte) xKeysymToKeycode.invokeExact(display, e.getValue());
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

            Set<MouseButton> buttons = EnumSet.noneOf(MouseButton.class);
            if ((mask & BUTTON1_MASK) != 0) buttons.add(MouseButton.LEFT);
            if ((mask & BUTTON2_MASK) != 0) buttons.add(MouseButton.MIDDLE);
            if ((mask & BUTTON3_MASK) != 0) buttons.add(MouseButton.RIGHT);

            return new PointerState(x, y, buttons);
        } catch (Throwable t) {
            throw new BackendException("Native pointer poll failed", t);
        }
    }

    @Override
    public boolean isKeyDown(Key key) throws BackendException {
        Integer keycode = keycodeByKey.get(key);
        if (keycode == null || keycode == 0) {
            throw new BackendException("Key not mapped for X11: " + key);
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
            EnumSet<Key> down = EnumSet.noneOf(Key.class);
            for (Map.Entry<Key, Integer> e : keycodeByKey.entrySet()) {
                int kc = e.getValue();
                if (kc == 0) {
                    continue;
                }
                int b = keymap.get(ValueLayout.JAVA_BYTE, kc / 8) & 0xFF;
                if ((b & (1 << (kc % 8))) != 0) {
                    down.add(e.getKey());
                }
            }
            return down;
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Native keymap poll failed", t);
        }
    }

    @Override
    public void close() {
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
        return m;
    }
}
