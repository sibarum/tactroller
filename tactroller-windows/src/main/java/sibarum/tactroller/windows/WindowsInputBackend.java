package sibarum.tactroller.windows;

import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.InputBackend;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.PointerDelta;
import sibarum.tactroller.api.PointerLockMode;
import sibarum.tactroller.api.PointerState;
import sibarum.tactroller.api.ScrollDelta;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Windows {@link InputBackend} on the Panama FFM API.
 *
 * <p><b>This class is the per-window half.</b> Absolute pointer position, key and button state come from
 * polling {@code user32.dll} ({@code GetCursorPos}, {@code GetAsyncKeyState}), and window attachment supplies
 * client-relative coordinates ({@code ScreenToClient}) plus the two routing gates: {@link #isFocused()} for
 * focal channels (keys, typed text) and {@link #isPointerInClient()} for positional ones (wheel, pointer).
 *
 * <p>Scroll wheel, raw relative motion and typed text cannot be polled on Windows — they arrive via RawInput,
 * which is <b>process-scoped</b>: one registration per usage pair wins for the entire process. That half lives
 * in {@link WindowsRawInputHub}, which runs a single pump for the process and fans every report into every
 * live backend through {@link #acceptRawMouse} / {@link #acceptChars}; the loop thread then drains this
 * instance's accumulators via {@link #drainScroll()} / {@link #drainPointerDelta()} / {@link #drainChars()}.
 *
 * <p><b>Keep that split.</b> Anything process- or globally-scoped belongs in the hub, never here — a
 * per-instance registration silently steals input from every other window. {@code RawInputScopeGuardTest}
 * enforces this by scanning bytecode.
 *
 * <p>Pointer lock supports both {@link PointerLockMode#RECENTER} (warp-to-center each drain) and
 * {@link PointerLockMode#RAW} (RawInput deltas). Note that it manipulates <b>globally</b>-scoped state
 * ({@code ClipCursor}, {@code ShowCursor}, {@code SetCursorPos}), so at most one window in a process should
 * hold the lock at a time; the application arbitrates that today.
 */
public final class WindowsInputBackend implements InputBackend {

    // Win32 constants. (The RawInput/message-pump constants live in WindowsRawInputHub with their code.)
    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12; // ALT
    private static final int WHEEL_DELTA = 120;
    private static final int SM_CXSCREEN = 0;
    private static final int SM_CYSCREEN = 1;
    private static final int DOWN_MASK = 0x8000;
    private static final int VK_LBUTTON = 0x01;
    private static final int VK_RBUTTON = 0x02;
    private static final int VK_MBUTTON = 0x04;

    /** POINT { LONG x; LONG y; } */
    private static final MemoryLayout POINT = MemoryLayout.structLayout(
            JAVA_INT.withName("x"), JAVA_INT.withName("y"));

    private final Map<Key, Integer> vkByKey = buildKeyMap();

    // Accumulators fed by the WndProc (pump thread), drained by the loop/poll thread.
    private final AtomicInteger rawDx = new AtomicInteger();
    private final AtomicInteger rawDy = new AtomicInteger();
    private final AtomicInteger scrollUnitsX = new AtomicInteger();
    private final AtomicInteger scrollUnitsY = new AtomicInteger();
    // Typed characters (UTF-16 units) accumulated on the pump thread, drained by the loop/poll thread.
    private final StringBuilder charBuf = new StringBuilder();
    private final Object charLock = new Object();

    private Arena arena;
    private MemorySegment pointBuffer;      // reusable POINT for pollPointer (loop/poll thread)

    // user32 / kernel32 handles.
    private MethodHandle getCursorPos;
    private MethodHandle setCursorPos;
    private MethodHandle getAsyncKeyState;
    private MethodHandle showCursor;
    private MethodHandle clipCursor;
    private MethodHandle screenToClient;
    private MethodHandle clientToScreen;
    private MethodHandle getForegroundWindow;
    private MethodHandle getClientRect;
    private MethodHandle getSystemMetrics;
    private MethodHandle getDpiForWindow;

    private volatile long attachedHwnd;
    private volatile PointerLockMode lockMode;
    /** Where the cursor was when the lock was taken, in screen pixels, so unlocking can put it back. */
    private volatile int[] preLockCursor;

    @Override
    public String name() {
        return "windows-user32";
    }

    @Override
    @SuppressWarnings("restricted")
    public void initialize() throws BackendException {
        try {
            arena = Arena.ofShared();
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

            getCursorPos = dc(linker, user32, "GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            setCursorPos = dc(linker, user32, "SetCursorPos", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
            getAsyncKeyState = dc(linker, user32, "GetAsyncKeyState", FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
            showCursor = dc(linker, user32, "ShowCursor", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            clipCursor = dc(linker, user32, "ClipCursor", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            screenToClient = dc(linker, user32, "ScreenToClient", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            clientToScreen = dc(linker, user32, "ClientToScreen", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            getForegroundWindow = dc(linker, user32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
            getClientRect = dc(linker, user32, "GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            getSystemMetrics = dc(linker, user32, "GetSystemMetrics", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            getDpiForWindow = dc(linker, user32, "GetDpiForWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));

            pointBuffer = arena.allocate(POINT);

            // RawInput (wheel, raw motion, typed text) is process-scoped, so the hub owns it and fans each
            // report into this instance via acceptRawMouse/acceptChars. Never register RawInput from here.
            WindowsRawInputHub.register(this);
        } catch (BackendException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BackendException("Failed to bind Windows input libraries", e);
        }
    }


    // ---- RawInput sink (called on the hub's pump thread) -------------------

    /**
     * Accept one raw-mouse report fanned from {@link WindowsRawInputHub}: relative motion and wheel notches,
     * already decoded. Pump thread — the accumulators are atomics drained by the loop/poll thread.
     */
    void acceptRawMouse(int dx, int dy, int wheelX, int wheelY) {
        if (dx != 0) {
            rawDx.addAndGet(dx);
        }
        if (dy != 0) {
            rawDy.addAndGet(dy);
        }
        if (wheelX != 0) {
            scrollUnitsX.addAndGet(wheelX);
        }
        if (wheelY != 0) {
            scrollUnitsY.addAndGet(wheelY);
        }
    }

    /** Accept typed UTF-16 units fanned from {@link WindowsRawInputHub}. Pump thread. */
    void acceptChars(CharSequence produced) {
        synchronized (charLock) {
            charBuf.append(produced);
        }
    }

    // ---- Polling ----------------------------------------------------------

    @Override
    public PointerState pollPointer() throws BackendException {
        try {
            int ok = (int) getCursorPos.invokeExact(pointBuffer);
            if (ok == 0) {
                throw new BackendException("GetCursorPos returned FALSE");
            }
            int x = pointBuffer.get(JAVA_INT, 0);
            int y = pointBuffer.get(JAVA_INT, 4);
            Set<MouseButton> buttons = EnumSet.noneOf(MouseButton.class);
            if (down(VK_LBUTTON)) buttons.add(MouseButton.LEFT);
            if (down(VK_RBUTTON)) buttons.add(MouseButton.RIGHT);
            if (down(VK_MBUTTON)) buttons.add(MouseButton.MIDDLE);
            return new PointerState(x, y, buttons);
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Native pointer poll failed", t);
        }
    }

    @Override
    public boolean isKeyDown(Key key) throws BackendException {
        Integer vk = vkByKey.get(key);
        if (vk == null) {
            return false; // a key we cannot observe is reported as not-down (keeps pollKeys robust)
        }
        try {
            return down(vk);
        } catch (Throwable t) {
            throw new BackendException("Native key poll failed for " + key, t);
        }
    }

    @Override
    public boolean isButtonDown(MouseButton button) throws BackendException {
        try {
            return down(switch (button) {
                case LEFT -> VK_LBUTTON;
                case RIGHT -> VK_RBUTTON;
                case MIDDLE -> VK_MBUTTON;
            });
        } catch (Throwable t) {
            throw new BackendException("Native button poll failed for " + button, t);
        }
    }

    private boolean down(int vk) throws Throwable {
        short state = (short) getAsyncKeyState.invokeExact(vk);
        return (state & DOWN_MASK) != 0;
    }

    // ---- Scroll & relative motion ----------------------------------------

    @Override
    public ScrollDelta drainScroll() {
        int y = scrollUnitsY.getAndSet(0);
        int x = scrollUnitsX.getAndSet(0);
        if (x == 0 && y == 0) {
            return ScrollDelta.ZERO;
        }
        return new ScrollDelta((double) x / WHEEL_DELTA, (double) y / WHEEL_DELTA);
    }

    @Override
    public int[] drainChars() {
        String s;
        synchronized (charLock) {
            if (charBuf.length() == 0) {
                return NO_CHARS;
            }
            s = charBuf.toString();
            charBuf.setLength(0);
        }
        return s.codePoints().toArray(); // fold any UTF-16 surrogate pairs into single code points
    }

    @Override
    public PointerDelta drainPointerDelta() {
        PointerLockMode mode = lockMode;
        if (mode == PointerLockMode.RAW) {
            int dx = rawDx.getAndSet(0);
            int dy = rawDy.getAndSet(0);
            return (dx == 0 && dy == 0) ? PointerDelta.ZERO : new PointerDelta(dx, dy);
        }
        if (mode == PointerLockMode.RECENTER) {
            try {
                int[] center = centerScreenPoint();
                int ok = (int) getCursorPos.invokeExact(pointBuffer);
                if (ok == 0) {
                    return PointerDelta.ZERO;
                }
                int dx = pointBuffer.get(JAVA_INT, 0) - center[0];
                int dy = pointBuffer.get(JAVA_INT, 4) - center[1];
                int ignored = (int) setCursorPos.invokeExact(center[0], center[1]);
                return (dx == 0 && dy == 0) ? PointerDelta.ZERO : new PointerDelta(dx, dy);
            } catch (Throwable t) {
                throw wrap("recenter drain failed", t);
            }
        }
        return PointerDelta.ZERO;
    }

    // ---- Pointer lock -----------------------------------------------------

    @Override
    public boolean supportsPointerLock() {
        return true;
    }

    @Override
    public void setPointerLock(PointerLockMode mode) throws BackendException {
        try {
            boolean wasLocked = lockMode != null;
            lockMode = mode;
            rawDx.set(0);
            rawDy.set(0);
            if (!wasLocked) {
                // Remembered before anything moves it, so that unlocking is invisible. A lock held for a whole
                // session does not care where the cursor was; a lock held for the length of one drag cares very
                // much, because letting go somewhere the user did not put the pointer is its own bug.
                preLockCursor = screenCursorPoint();
                int ignored = (int) showCursor.invokeExact(0); // hide (best effort)
            }
            if (mode == PointerLockMode.RECENTER) {
                int[] c = centerScreenPoint();
                int ignored = (int) setCursorPos.invokeExact(c[0], c[1]);
            }
        } catch (Throwable t) {
            throw new BackendException("Failed to lock pointer", t);
        }
    }

    @Override
    public void clearPointerLock() {
        if (lockMode == null) {
            return;
        }
        lockMode = null;
        try {
            int[] back = preLockCursor;
            preLockCursor = null;
            if (back != null) {
                // Put it back before showing it, so the cursor never appears at the centre and then jumps.
                int moved = (int) setCursorPos.invokeExact(back[0], back[1]);
            }
            int ignored = (int) showCursor.invokeExact(1); // show
        } catch (Throwable t) {
            throw wrap("failed to restore cursor", t);
        }
    }

    @Override
    public boolean isPointerLocked() {
        return lockMode != null;
    }

    // ---- Window attachment / focus / coordinates -------------------------

    @Override
    public void attach(NativeWindow window) throws BackendException {
        if (window.kind() != NativeWindow.Kind.HWND) {
            throw new BackendException("Windows backend requires an HWND, got " + window.kind());
        }
        attachedHwnd = window.handle();
    }

    @Override
    public void detach() {
        attachedHwnd = 0L;
    }

    @Override
    public boolean isWindowAttached() {
        return attachedHwnd != 0L;
    }

    @Override
    @SuppressWarnings("restricted")
    public boolean isFocused() {
        long hwnd = attachedHwnd;
        if (hwnd == 0L) {
            return true;
        }
        try {
            MemorySegment fg = (MemorySegment) getForegroundWindow.invokeExact();
            return fg.address() == hwnd;
        } catch (Throwable t) {
            throw wrap("GetForegroundWindow failed", t);
        }
    }

    /**
     * Whether the cursor lies inside the attached window's client rect — the <b>positional</b> routing gate
     * (wheel, pointer). Uses {@code ScreenToClient} + {@code GetClientRect} rather than
     * {@code WindowFromPoint}, so the answer is about <em>this</em> window's geometry only: with overlapping
     * windows both may report true, and the consumer's own hit-testing resolves the overlap.
     */
    @Override
    @SuppressWarnings("restricted")
    public boolean isPointerInClient() {
        long hwnd = attachedHwnd;
        if (hwnd == 0L) {
            return true;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pt = a.allocate(POINT);
            if ((int) getCursorPos.invokeExact(pt) == 0) {
                return false;
            }
            int ignored = (int) screenToClient.invokeExact(MemorySegment.ofAddress(hwnd), pt);
            int x = pt.get(JAVA_INT, 0);
            int y = pt.get(JAVA_INT, 4);
            MemorySegment rect = a.allocate(16);   // RECT: left@0, top@4, right@8, bottom@12
            if ((int) getClientRect.invokeExact(MemorySegment.ofAddress(hwnd), rect) == 0) {
                return false;
            }
            return x >= rect.get(JAVA_INT, 0) && x < rect.get(JAVA_INT, 8)
                    && y >= rect.get(JAVA_INT, 4) && y < rect.get(JAVA_INT, 12);
        } catch (Throwable t) {
            throw wrap("GetClientRect/ScreenToClient failed", t);
        }
    }

    @Override
    @SuppressWarnings("restricted")
    public int[] toClient(int screenX, int screenY) {
        long hwnd = attachedHwnd;
        if (hwnd == 0L) {
            return new int[] {screenX, screenY};
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pt = a.allocate(POINT);
            pt.set(JAVA_INT, 0, screenX);
            pt.set(JAVA_INT, 4, screenY);
            int ignored = (int) screenToClient.invokeExact(MemorySegment.ofAddress(hwnd), pt);
            return new int[] {pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4)};
        } catch (Throwable t) {
            throw wrap("ScreenToClient failed", t);
        }
    }

    @Override
    @SuppressWarnings("restricted")
    public double contentScale() {
        long hwnd = attachedHwnd;
        if (hwnd == 0L) {
            return 1.0;
        }
        try {
            int dpi = (int) getDpiForWindow.invokeExact(MemorySegment.ofAddress(hwnd));
            return dpi > 0 ? dpi / 96.0 : 1.0;
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /** Where the cursor is now, in screen coordinates — what a lock remembers so unlocking can undo itself. */
    @SuppressWarnings("restricted")
    private int[] screenCursorPoint() throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pt = a.allocate(POINT);
            if ((int) getCursorPos.invokeExact(pt) == 0) {
                return null;                       // no answer is better than a wrong place to put it back
            }
            return new int[]{pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4)};
        }
    }

    /** Center point (screen coords) used for RECENTER: attached window's client center, else screen center. */
    @SuppressWarnings("restricted")
    private int[] centerScreenPoint() throws Throwable {
        long hwnd = attachedHwnd;
        if (hwnd != 0L) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment rect = a.allocate(16); // RECT { left, top, right, bottom }
                int ok = (int) getClientRect.invokeExact(MemorySegment.ofAddress(hwnd), rect);
                if (ok != 0) {
                    int cx = rect.get(JAVA_INT, 8) / 2;
                    int cy = rect.get(JAVA_INT, 12) / 2;
                    MemorySegment pt = a.allocate(POINT);
                    pt.set(JAVA_INT, 0, cx);
                    pt.set(JAVA_INT, 4, cy);
                    int ig = (int) clientToScreen.invokeExact(MemorySegment.ofAddress(hwnd), pt);
                    return new int[] {pt.get(JAVA_INT, 0), pt.get(JAVA_INT, 4)};
                }
            }
        }
        int cx = (int) getSystemMetrics.invokeExact(SM_CXSCREEN);
        int cy = (int) getSystemMetrics.invokeExact(SM_CYSCREEN);
        return new int[] {cx / 2, cy / 2};
    }

    // ---- Teardown ---------------------------------------------------------

    @Override
    public void close() {
        try {
            clearPointerLock();
        } catch (RuntimeException ignored) {
            // continue teardown
        }
        // Leave the process hub; it tears its pump down once the last backend is gone. Because the hub's
        // lifetime is independent of any instance, closing whichever window opened first cannot leave the
        // surviving windows deaf to wheel and typed text.
        WindowsRawInputHub.unregister(this);
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    // ---- Helpers ----------------------------------------------------------

    @SuppressWarnings("restricted")
    private static MethodHandle dc(Linker linker, SymbolLookup lib, String symbol, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.findOrThrow(symbol), fd);
    }

    /** Allocate a null-terminated UTF-16LE (wide) string. */
    private static MemorySegment wide(Arena arena, String s) {
        MemorySegment seg = arena.allocate((s.length() + 1) * 2L);
        for (int i = 0; i < s.length(); i++) {
            seg.set(JAVA_SHORT, i * 2L, (short) s.charAt(i));
        }
        seg.set(JAVA_SHORT, s.length() * 2L, (short) 0);
        return seg;
    }

    private static RuntimeException wrap(String msg, Throwable t) {
        return new RuntimeException(msg, t);
    }

    private static Map<Key, Integer> buildKeyMap() {
        Map<Key, Integer> m = new EnumMap<>(Key.class);
        for (char c = 'A'; c <= 'Z'; c++) {
            m.put(Key.valueOf(String.valueOf(c)), (int) c);
        }
        for (int d = 0; d <= 9; d++) {
            m.put(Key.valueOf("DIGIT_" + d), (int) ('0' + d));
        }
        m.put(Key.SPACE, 0x20);
        m.put(Key.ENTER, 0x0D);
        m.put(Key.ESCAPE, 0x1B);
        m.put(Key.TAB, 0x09);
        m.put(Key.BACKSPACE, 0x08);
        m.put(Key.DELETE, 0x2E);
        m.put(Key.LEFT, 0x25);
        m.put(Key.UP, 0x26);
        m.put(Key.RIGHT, 0x27);
        m.put(Key.DOWN, 0x28);
        m.put(Key.LEFT_SHIFT, 0xA0);
        m.put(Key.RIGHT_SHIFT, 0xA1);
        m.put(Key.LEFT_CONTROL, 0xA2);
        m.put(Key.RIGHT_CONTROL, 0xA3);
        m.put(Key.LEFT_ALT, 0xA4);
        m.put(Key.RIGHT_ALT, 0xA5);
        m.put(Key.LEFT_SUPER, 0x5B);
        m.put(Key.RIGHT_SUPER, 0x5C);

        // Function keys VK_F1..VK_F12 = 0x70..0x7B.
        for (int f = 1; f <= 12; f++) {
            m.put(Key.valueOf("F" + f), 0x70 + (f - 1));
        }
        m.put(Key.INSERT, 0x2D);
        m.put(Key.HOME, 0x24);
        m.put(Key.END, 0x23);
        m.put(Key.PAGE_UP, 0x21);
        m.put(Key.PAGE_DOWN, 0x22);
        m.put(Key.CAPS_LOCK, 0x14);

        // Numpad VK_NUMPAD0..9 = 0x60..0x69.
        for (int n = 0; n <= 9; n++) {
            m.put(Key.valueOf("NUMPAD_" + n), 0x60 + n);
        }
        m.put(Key.NUMPAD_MULTIPLY, 0x6A);
        m.put(Key.NUMPAD_ADD, 0x6B);
        m.put(Key.NUMPAD_SUBTRACT, 0x6D);
        m.put(Key.NUMPAD_DECIMAL, 0x6E);
        m.put(Key.NUMPAD_DIVIDE, 0x6F);
        m.put(Key.NUMPAD_ENTER, 0x0D); // VK_RETURN; not distinguishable from ENTER via GetAsyncKeyState

        // OEM punctuation (US layout).
        m.put(Key.MINUS, 0xBD);
        m.put(Key.EQUAL, 0xBB);
        m.put(Key.LEFT_BRACKET, 0xDB);
        m.put(Key.RIGHT_BRACKET, 0xDD);
        m.put(Key.BACKSLASH, 0xDC);
        m.put(Key.SEMICOLON, 0xBA);
        m.put(Key.APOSTROPHE, 0xDE);
        m.put(Key.COMMA, 0xBC);
        m.put(Key.PERIOD, 0xBE);
        m.put(Key.SLASH, 0xBF);
        m.put(Key.GRAVE_ACCENT, 0xC0);
        return m;
    }
}
