package sibarum.tactroller.windows;

import sibarum.tactroller.api.BackendException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The process-scoped half of the Windows input backend: the RawInput pump, shared by every
 * {@link WindowsInputBackend} instance in the process.
 *
 * <h2>Why this class exists as a separate thing</h2>
 *
 * <p>{@code InputBackend} is a <b>per-window</b> abstraction, but several Win32 input channels are
 * <b>per-process</b> or <b>global</b>. RawInput is the sharpest case: Windows delivers {@code WM_INPUT} for a
 * usage pair to the <em>single most recently registered</em> target window in the process, so a backend that
 * registers per instance silences every earlier instance the moment a second window opens — silently, because
 * the OS considers the later registration entirely legal. That mismatch between OS scope and abstraction scope
 * is a bug factory; this class is the seam that corrects it, so the mismatch is handled in exactly one place
 * instead of being rediscovered per channel.
 *
 * <p>Accordingly: one hub per process owns the message-only window, the {@code WM_INPUT} registration, the
 * pump thread and the pump-thread-only buffers, and <b>fans</b> what arrives into every registered sink's
 * accumulators. The hub is reference-counted by {@link #register}/{@link #unregister} and its lifetime is
 * independent of any individual backend — so closing whichever window happened to open first cannot leave the
 * process deaf, and no ownership hand-off is needed.
 *
 * <p><b>Anything process- or globally-scoped belongs here, not in the per-window backend.</b>
 * {@code RawInputScopeGuardTest} enforces that by scanning bytecode for the relevant Win32 symbols.
 *
 * <p>Threading: {@link #register}/{@link #unregister} are serialized on {@link #LOCK} and called from
 * application threads; {@link #wndProc} and {@link #translateToChars} run on the hub's pump thread and touch
 * only hub-owned buffers plus the sinks' atomic/locked accumulators.
 */
final class WindowsRawInputHub {

    // Win32 constants owned by the pump path.
    private static final int WM_INPUT = 0x00FF;
    private static final int WM_QUIT = 0x0012;
    private static final long HWND_MESSAGE = -3L;
    private static final int RID_INPUT = 0x10000003;
    private static final int RIDEV_INPUTSINK = 0x00000100;
    private static final int RAWINPUTHEADER_SIZE = 24;
    private static final int RIM_TYPEMOUSE = 0;
    private static final int RIM_TYPEKEYBOARD = 1;
    private static final int RI_KEY_BREAK = 0x01; // key-up flag in RAWKEYBOARD.Flags
    private static final int RI_MOUSE_WHEEL = 0x0400;
    private static final int RI_MOUSE_HWHEEL = 0x0800;
    private static final int MOUSE_MOVE_ABSOLUTE = 0x01;
    private static final int MAPVK_VK_TO_VSC = 0;
    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12; // ALT
    private static final int VK_CAPITAL = 0x14;
    private static final int TOGGLED_MASK = 0x01;
    private static final int DOWN_MASK = 0x8000;

    private static final AtomicInteger CLASS_SEQ = new AtomicInteger();

    /** Guards {@link #instance} creation/disposal so register/unregister are atomic against each other. */
    private static final Object LOCK = new Object();
    private static WindowsRawInputHub instance;

    /**
     * Register {@code sink} to receive fanned RawInput, starting the process hub if this is the first sink.
     * Idempotent per sink.
     */
    static void register(WindowsInputBackend sink) throws BackendException {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new WindowsRawInputHub();
            }
            if (!instance.sinks.contains(sink)) {
                instance.sinks.add(sink);
            }
        }
    }

    /** Unregister {@code sink}; the hub is torn down once the last sink leaves. Safe to call for a non-sink. */
    static void unregister(WindowsInputBackend sink) {
        synchronized (LOCK) {
            if (instance == null) {
                return;
            }
            instance.sinks.remove(sink);
            if (instance.sinks.isEmpty()) {
                instance.dispose();
                instance = null;
            }
        }
    }

    /** Visible for tests: whether a hub is currently up (i.e. at least one sink is registered). */
    static boolean isRunning() {
        synchronized (LOCK) {
            return instance != null;
        }
    }

    private final List<WindowsInputBackend> sinks = new CopyOnWriteArrayList<>();

    private final Arena arena;
    private final MemorySegment rawInputBuffer;   // reusable RAWINPUT buffer (pump thread only)
    private final MemorySegment rawInputSize;     // reusable pcbSize (pump thread only)
    private final MemorySegment keyStateBuffer;   // 256-byte keyboard state for ToUnicodeEx (pump thread only)
    private final MemorySegment uniBuffer;        // UTF-16 output for ToUnicodeEx (pump thread only)

    private final MethodHandle getAsyncKeyState;
    private final MethodHandle getModuleHandle;
    private final MethodHandle registerClassEx;
    private final MethodHandle createWindowEx;
    private final MethodHandle defWindowProc;
    private final MethodHandle destroyWindow;
    private final MethodHandle registerRawInputDevices;
    private final MethodHandle getRawInputData;
    private final MethodHandle getMessage;
    private final MethodHandle translateMessage;
    private final MethodHandle dispatchMessage;
    private final MethodHandle postThreadMessage;
    private final MethodHandle getCurrentThreadId;
    private final MethodHandle mapVirtualKey;
    private final MethodHandle getKeyboardLayout;
    private final MethodHandle getKeyState;
    private final MethodHandle toUnicodeEx;

    private Thread pumpThread;
    private volatile int pumpThreadId;
    private volatile MemorySegment messageHwnd = MemorySegment.NULL;
    private volatile Throwable pumpInitError;

    @SuppressWarnings("restricted")
    private WindowsRawInputHub() throws BackendException {
        try {
            this.arena = Arena.ofShared();
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

            getAsyncKeyState = dc(linker, user32, "GetAsyncKeyState", FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
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
            mapVirtualKey = dc(linker, user32, "MapVirtualKeyW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
            getKeyboardLayout = dc(linker, user32, "GetKeyboardLayout", FunctionDescriptor.of(ADDRESS, JAVA_INT));
            getKeyState = dc(linker, user32, "GetKeyState", FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
            toUnicodeEx = dc(linker, user32, "ToUnicodeEx", FunctionDescriptor.of(JAVA_INT,
                    JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

            rawInputBuffer = arena.allocate(64);
            rawInputSize = arena.allocate(JAVA_INT);
            keyStateBuffer = arena.allocate(256);
            uniBuffer = arena.allocate(16); // up to 8 UTF-16 units (2 bytes each) per keystroke

            startPumpThread(linker);
        } catch (BackendException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BackendException("Failed to start the RawInput hub", e);
        }
    }

    @SuppressWarnings("restricted")
    private void startPumpThread(Linker linker) throws BackendException {
        MemorySegment stub;
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    WindowsRawInputHub.class, "wndProc",
                    MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
                    .bindTo(this);
            stub = linker.upcallStub(handle,
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), arena);
        } catch (ReflectiveOperationException e) {
            throw new BackendException("Failed to create WndProc upcall stub", e);
        }

        CountDownLatch ready = new CountDownLatch(1);
        String className = "TactrollerRawInput" + CLASS_SEQ.incrementAndGet();
        pumpThread = new Thread(() -> runPump(className, stub, ready), "tactroller-win-msgpump");
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

            // Two RAWINPUTDEVICEs (16 bytes each): mouse (for wheel/relative motion) and keyboard (for typed
            // text). usUsagePage@0, usUsage@2, dwFlags@4, hwndTarget@8. INPUTSINK so we receive input even when
            // this message-only window is not focused (routing/gating happens later, at publish time).
            MemorySegment rid = pump.allocate(32);
            rid.set(JAVA_SHORT, 0, (short) 0x01);  // generic desktop
            rid.set(JAVA_SHORT, 2, (short) 0x02);  // mouse
            rid.set(JAVA_INT, 4, RIDEV_INPUTSINK);
            rid.set(ADDRESS, 8, hwnd);
            rid.set(JAVA_SHORT, 16, (short) 0x01); // generic desktop
            rid.set(JAVA_SHORT, 18, (short) 0x06); // keyboard
            rid.set(JAVA_INT, 20, RIDEV_INPUTSINK);
            rid.set(ADDRESS, 24, hwnd);
            int ok = (int) registerRawInputDevices.invokeExact(rid, 2, 16);
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

    /**
     * Window procedure (pump thread). Accumulates raw mouse deltas and wheel notches into <em>every</em>
     * registered sink — this pump is the process's only one, so what it receives is every window's share.
     */
    @SuppressWarnings({"restricted", "unused"})
    private long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            if (msg == WM_INPUT) {
                MemorySegment hRawInput = MemorySegment.ofAddress(lParam);
                rawInputSize.set(JAVA_INT, 0, 64);
                int n = (int) getRawInputData.invokeExact(
                        hRawInput, RID_INPUT, rawInputBuffer, rawInputSize, RAWINPUTHEADER_SIZE);
                int dwType = n > 0 ? rawInputBuffer.get(JAVA_INT, 0) : -1;
                if (dwType == RIM_TYPEMOUSE) {
                    int usFlags = rawInputBuffer.get(JAVA_SHORT, 24) & 0xFFFF;
                    int usButtonFlags = rawInputBuffer.get(JAVA_SHORT, 28) & 0xFFFF;
                    short usButtonData = rawInputBuffer.get(JAVA_SHORT, 30);
                    int lLastX = rawInputBuffer.get(JAVA_INT, 36);
                    int lLastY = rawInputBuffer.get(JAVA_INT, 40);
                    boolean relative = (usFlags & MOUSE_MOVE_ABSOLUTE) == 0;
                    int wheelY = (usButtonFlags & RI_MOUSE_WHEEL) != 0 ? usButtonData : 0;
                    int wheelX = (usButtonFlags & RI_MOUSE_HWHEEL) != 0 ? usButtonData : 0;
                    for (WindowsInputBackend sink : sinks) {
                        sink.acceptRawMouse(relative ? lLastX : 0, relative ? lLastY : 0, wheelX, wheelY);
                    }
                } else if (dwType == RIM_TYPEKEYBOARD) {
                    // RAWKEYBOARD begins at offset 24: MakeCode@24, Flags@26, Reserved@28, VKey@30, Message@32.
                    int flags = rawInputBuffer.get(JAVA_SHORT, 26) & 0xFFFF;
                    int vKey = rawInputBuffer.get(JAVA_SHORT, 30) & 0xFFFF;
                    if ((flags & RI_KEY_BREAK) == 0) { // key-down only
                        translateToChars(vKey);
                    }
                }
            }
            return (long) defWindowProc.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            // Never let an exception cross the native boundary.
            return 0L;
        }
    }

    /**
     * Translate a key-down virtual key to text via {@code ToUnicodeEx} and fan the produced UTF-16 units into
     * every sink (pump thread). The keyboard state is rebuilt from {@code GetAsyncKeyState}/{@code GetKeyState}
     * because this message-only window is not the focused queue, so the per-thread state {@code ToUnicodeEx}
     * would otherwise read is not maintained here. Shortcut chords (Control without Alt) produce no text — that
     * keeps the command channel ({@code KeyPressed}) and the text channel disjoint — and control characters
     * (Enter/Tab/Backspace/Escape) are filtered out, as those are handled as keys.
     */
    @SuppressWarnings("restricted")
    private void translateToChars(int vKey) throws Throwable {
        // Skip pure modifiers/fake keys — they never produce text and would waste a ToUnicodeEx call.
        if (vKey == 0 || vKey == 0xFF || (vKey >= 0xA0 && vKey <= 0xA5) || vKey == VK_SHIFT
                || vKey == VK_CONTROL || vKey == VK_MENU || vKey == VK_CAPITAL) {
            return;
        }
        boolean shift = down(VK_SHIFT);
        boolean ctrl = down(VK_CONTROL);
        boolean alt = down(VK_MENU);
        // Ctrl (without Alt) means a shortcut chord, not typed text. AltGr (== Ctrl+Alt) still types.
        if (ctrl && !alt) {
            return;
        }
        keyStateBuffer.fill((byte) 0);
        if (shift) keyStateBuffer.set(JAVA_BYTE, VK_SHIFT, (byte) 0x80);
        if (ctrl) keyStateBuffer.set(JAVA_BYTE, VK_CONTROL, (byte) 0x80);
        if (alt) keyStateBuffer.set(JAVA_BYTE, VK_MENU, (byte) 0x80);
        if ((((short) getKeyState.invokeExact(VK_CAPITAL)) & TOGGLED_MASK) != 0) {
            keyStateBuffer.set(JAVA_BYTE, VK_CAPITAL, (byte) TOGGLED_MASK);
        }

        int scan = (int) mapVirtualKey.invokeExact(vKey, MAPVK_VK_TO_VSC);
        MemorySegment hkl = (MemorySegment) getKeyboardLayout.invokeExact(0);
        int rc = (int) toUnicodeEx.invokeExact(vKey, scan, keyStateBuffer, uniBuffer, 8, 0, hkl);
        if (rc <= 0) {
            return; // 0 = no translation; -1 = dead key (buffered by the OS for the next keystroke)
        }
        StringBuilder produced = new StringBuilder(rc);
        for (int i = 0; i < rc; i++) {
            char c = (char) (uniBuffer.get(JAVA_SHORT, i * 2L) & 0xFFFF);
            if (c >= 0x20 && c != 0x7F) { // drop control chars — those ride the KeyPressed channel
                produced.append(c);
            }
        }
        if (produced.isEmpty()) {
            return;
        }
        for (WindowsInputBackend sink : sinks) {
            sink.acceptChars(produced);
        }
    }

    @SuppressWarnings("restricted")
    private boolean down(int vk) throws Throwable {
        short state = (short) getAsyncKeyState.invokeExact(vk);
        return (state & DOWN_MASK) != 0;
    }

    /** Stop the pump thread and release the hub's arena. Called with {@link #LOCK} held. */
    @SuppressWarnings("restricted")
    private void dispose() {
        int tid = pumpThreadId;
        if (tid != 0) {
            try {
                int ignored = (int) postThreadMessage.invokeExact(tid, WM_QUIT, 0L, 0L);
            } catch (Throwable ignored) {
                // fall through to the join/close
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
        pumpThreadId = 0;
        arena.close();
    }

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
}
