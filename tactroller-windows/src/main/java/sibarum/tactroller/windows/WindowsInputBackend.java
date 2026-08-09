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
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Windows {@link InputBackend} on the Panama FFM API.
 *
 * <p>Absolute pointer position, key and button state come from polling {@code user32.dll}
 * ({@code GetCursorPos}, {@code GetAsyncKeyState}). Scroll wheel and raw relative motion cannot be
 * polled on Windows, so this backend runs its own <b>message-only window</b> registered for
 * <b>RawInput</b> ({@code WM_INPUT}) on a dedicated pump thread; the window procedure (an FFM
 * upcall) only <i>accumulates</i> wheel notches and raw deltas into atomic counters. The shared
 * event loop drains those via {@link #drainScroll()} / {@link #drainPointerDelta()}, so event
 * emission still happens in one place.
 *
 * <p>Pointer lock supports both {@link PointerLockMode#RECENTER} (warp-to-center each drain) and
 * {@link PointerLockMode#RAW} (RawInput deltas). Window attachment enables focus gating
 * ({@code GetForegroundWindow}) and client-relative coordinates ({@code ScreenToClient}).
 */
public final class WindowsInputBackend implements InputBackend {

    private static final AtomicInteger CLASS_SEQ = new AtomicInteger();

    // Win32 constants.
    private static final int WM_INPUT = 0x00FF;
    private static final int WM_QUIT = 0x0012;
    private static final long HWND_MESSAGE = -3L;
    private static final int RID_INPUT = 0x10000003;
    private static final int RIDEV_INPUTSINK = 0x00000100;
    private static final int RAWINPUTHEADER_SIZE = 24;
    private static final int RI_MOUSE_WHEEL = 0x0400;
    private static final int RI_MOUSE_HWHEEL = 0x0800;
    private static final int MOUSE_MOVE_ABSOLUTE = 0x01;
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

    private Arena arena;
    private MemorySegment pointBuffer;      // reusable POINT for pollPointer (loop/poll thread)
    private MemorySegment rawInputBuffer;   // reusable RAWINPUT buffer (pump thread only)
    private MemorySegment rawInputSize;     // reusable pcbSize (pump thread only)

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
    private MethodHandle getModuleHandle;
    private MethodHandle registerClassEx;
    private MethodHandle createWindowEx;
    private MethodHandle defWindowProc;
    private MethodHandle destroyWindow;
    private MethodHandle registerRawInputDevices;
    private MethodHandle getRawInputData;
    private MethodHandle getMessage;
    private MethodHandle translateMessage;
    private MethodHandle dispatchMessage;
    private MethodHandle postThreadMessage;
    private MethodHandle getCurrentThreadId;

    private Thread pumpThread;
    private volatile int pumpThreadId;
    private volatile MemorySegment messageHwnd = MemorySegment.NULL;
    private volatile Throwable pumpInitError;

    private volatile long attachedHwnd;
    private volatile PointerLockMode lockMode;

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
            registerClassEx = dc(linker, user32, "RegisterClassExW", FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
            createWindowEx = dc(linker, user32, "CreateWindowExW", FunctionDescriptor.of(ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            defWindowProc = dc(linker, user32, "DefWindowProcW",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
            destroyWindow = dc(linker, user32, "DestroyWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            registerRawInputDevices = dc(linker, user32, "RegisterRawInputDevices",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            getRawInputData = dc(linker, user32, "GetRawInputData",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            getMessage = dc(linker, user32, "GetMessageW",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
            translateMessage = dc(linker, user32, "TranslateMessage", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            dispatchMessage = dc(linker, user32, "DispatchMessageW", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            postThreadMessage = dc(linker, user32, "PostThreadMessageW",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));
            getModuleHandle = dc(linker, kernel32, "GetModuleHandleW", FunctionDescriptor.of(ADDRESS, ADDRESS));
            getCurrentThreadId = dc(linker, kernel32, "GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));

            pointBuffer = arena.allocate(POINT);
            rawInputBuffer = arena.allocate(64);
            rawInputSize = arena.allocate(JAVA_INT);

            MemorySegment wndProcStub = makeWndProcStub(linker);
            startPumpThread(linker, wndProcStub);
        } catch (BackendException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BackendException("Failed to bind Windows input libraries", e);
        }
    }

    @SuppressWarnings("restricted")
    private MemorySegment makeWndProcStub(Linker linker) throws BackendException {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    WindowsInputBackend.class, "wndProc",
                    MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
                    .bindTo(this);
            return linker.upcallStub(handle,
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), arena);
        } catch (ReflectiveOperationException e) {
            throw new BackendException("Failed to create WndProc upcall stub", e);
        }
    }

    @SuppressWarnings("restricted")
    private void startPumpThread(Linker linker, MemorySegment wndProcStub) throws BackendException {
        CountDownLatch ready = new CountDownLatch(1);
        String className = "TactrollerRawInput" + CLASS_SEQ.incrementAndGet();
        pumpThread = new Thread(() -> runPump(className, wndProcStub, ready), "tactroller-win-msgpump");
        pumpThread.setDaemon(true);
        pumpThread.start();
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException("Interrupted starting message pump", e);
        }
        if (pumpInitError != null) {
            throw new BackendException("Message pump initialisation failed", pumpInitError);
        }
    }

    @SuppressWarnings("restricted")
    private void runPump(String className, MemorySegment wndProcStub, CountDownLatch ready) {
        try (Arena pump = Arena.ofConfined()) {
            pumpThreadId = (int) getCurrentThreadId.invokeExact();
            MemorySegment hInstance = (MemorySegment) getModuleHandle.invokeExact(MemorySegment.NULL);
            MemorySegment classNameSeg = wide(pump, className);

            // WNDCLASSEXW (80 bytes): cbSize@0, lpfnWndProc@8, hInstance@24, lpszClassName@64.
            MemorySegment wc = pump.allocate(80);
            wc.set(JAVA_INT, 0, 80);
            wc.set(ADDRESS, 8, wndProcStub);
            wc.set(ADDRESS, 24, hInstance);
            wc.set(ADDRESS, 64, classNameSeg);
            short atom = (short) registerClassEx.invokeExact(wc);
            if (atom == 0) {
                throw new IllegalStateException("RegisterClassExW failed");
            }

            MemorySegment hwnd = (MemorySegment) createWindowEx.invokeExact(
                    0, classNameSeg, wide(pump, "tactroller"), 0, 0, 0, 0, 0,
                    MemorySegment.ofAddress(HWND_MESSAGE), MemorySegment.NULL, hInstance, MemorySegment.NULL);
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("CreateWindowExW (message-only) failed");
            }
            messageHwnd = hwnd;

            // RAWINPUTDEVICE (16 bytes): usUsagePage@0, usUsage@2, dwFlags@4, hwndTarget@8.
            MemorySegment rid = pump.allocate(16);
            rid.set(JAVA_SHORT, 0, (short) 0x01); // generic desktop
            rid.set(JAVA_SHORT, 2, (short) 0x02); // mouse
            rid.set(JAVA_INT, 4, RIDEV_INPUTSINK);
            rid.set(ADDRESS, 8, hwnd);
            int ok = (int) registerRawInputDevices.invokeExact(rid, 1, 16);
            if (ok == 0) {
                throw new IllegalStateException("RegisterRawInputDevices failed");
            }
        } catch (Throwable t) {
            pumpInitError = t;
            ready.countDown();
            return;
        }
        ready.countDown();

        // Message loop. GetMessageW returns 0 on WM_QUIT, -1 on error.
        try (Arena msgArena = Arena.ofConfined()) {
            MemorySegment msg = msgArena.allocate(48);
            int r;
            while ((r = (int) getMessage.invokeExact(msg, MemorySegment.NULL, 0, 0)) != 0) {
                if (r == -1) {
                    break;
                }
                int ignoredT = (int) translateMessage.invokeExact(msg);
                long ignoredD = (long) dispatchMessage.invokeExact(msg);
            }
        } catch (Throwable t) {
            pumpInitError = t;
        } finally {
            try {
                if (!messageHwnd.equals(MemorySegment.NULL)) {
                    int ignored = (int) destroyWindow.invokeExact(messageHwnd);
                    messageHwnd = MemorySegment.NULL;
                }
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    /** Window procedure (runs on the pump thread). Accumulates raw mouse deltas and wheel notches. */
    @SuppressWarnings({"restricted", "unused"})
    private long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            if (msg == WM_INPUT) {
                MemorySegment hRawInput = MemorySegment.ofAddress(lParam);
                rawInputSize.set(JAVA_INT, 0, 64);
                int n = (int) getRawInputData.invokeExact(
                        hRawInput, RID_INPUT, rawInputBuffer, rawInputSize, RAWINPUTHEADER_SIZE);
                if (n > 0 && rawInputBuffer.get(JAVA_INT, 0) == 0) { // dwType == RIM_TYPEMOUSE
                    int usFlags = rawInputBuffer.get(JAVA_SHORT, 24) & 0xFFFF;
                    int usButtonFlags = rawInputBuffer.get(JAVA_SHORT, 28) & 0xFFFF;
                    short usButtonData = rawInputBuffer.get(JAVA_SHORT, 30);
                    int lLastX = rawInputBuffer.get(JAVA_INT, 36);
                    int lLastY = rawInputBuffer.get(JAVA_INT, 40);
                    if ((usFlags & MOUSE_MOVE_ABSOLUTE) == 0) {
                        rawDx.addAndGet(lLastX);
                        rawDy.addAndGet(lLastY);
                    }
                    if ((usButtonFlags & RI_MOUSE_WHEEL) != 0) {
                        scrollUnitsY.addAndGet(usButtonData);
                    }
                    if ((usButtonFlags & RI_MOUSE_HWHEEL) != 0) {
                        scrollUnitsX.addAndGet(usButtonData);
                    }
                }
            }
            return (long) defWindowProc.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            // Never let an exception cross the native boundary.
            return 0L;
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
    @SuppressWarnings("restricted")
    public void close() {
        try {
            clearPointerLock();
        } catch (RuntimeException ignored) {
            // continue teardown
        }
        // Ask the pump thread to quit, then join before releasing the arena (which frees the stub).
        int tid = pumpThreadId;
        if (tid != 0) {
            try {
                int ignored = (int) postThreadMessage.invokeExact(tid, WM_QUIT, 0L, 0L);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        if (pumpThread != null) {
            try {
                pumpThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pumpThread = null;
        }
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
