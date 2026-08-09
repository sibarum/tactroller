package sibarum.tactroller.api;

import java.util.Objects;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The middleware entry point. Resolves the single platform {@link InputBackend} on the classpath,
 * initialises it, and exposes an OS-agnostic API for one-shot polling and continuous event
 * listening, plus pointer-lock (mouselook), scroll, window attachment, focus gating and
 * client-relative coordinates.
 *
 * <h2>Polling (immediate-mode)</h2>
 * <pre>{@code
 * try (Tactroller t = Tactroller.open()) {
 *     t.lockPointer(PointerLockMode.RAW);
 *     while (running) {                       // once per frame
 *         PointerDelta look = t.pollPointerDelta();
 *         ScrollDelta zoom  = t.pollScroll();
 *         boolean fwd = t.isKeyDown(Key.W);
 *     }
 * }
 * }</pre>
 *
 * <h2>Events (retained-mode)</h2>
 * <pre>{@code
 * try (Tactroller t = Tactroller.open()) {
 *     t.attach(NativeWindow.ofHwnd(hwnd));
 *     t.setCoordinateSpace(CoordinateSpace.CLIENT);
 *     t.addListener(e -> { switch (e) { ... } });
 *     t.start();
 * }
 * }</pre>
 *
 * <p>The event loop lives entirely in this API module: it samples the backend (absolute pointer,
 * keys) and drains its accumulators (scroll, relative motion), then diffs and emits
 * {@link InputEvent}s. That derivation is the same code on every OS, so the event stream is
 * identical regardless of the native backend underneath.
 *
 * <p><b>Draining:</b> {@link #pollPointerDelta()}/{@link #pollScroll()} and the running event loop
 * both consume the backend's relative-motion and scroll accumulators. Use one or the other for a
 * given signal; mixing them splits the stream between consumers.
 */
public final class Tactroller implements AutoCloseable {

    /** Default event-loop sampling rate, in samples per second. */
    public static final int DEFAULT_POLL_HZ = 125;

    private final InputBackend backend;
    private final CopyOnWriteArrayList<InputListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Consumer<Throwable> errorHandler =
            t -> System.getLogger(Tactroller.class.getName())
                    .log(System.Logger.Level.WARNING, "Tactroller event loop error", t);

    private volatile Thread loopThread;
    private volatile CoordinateSpace coordinateSpace = CoordinateSpace.SCREEN;
    private volatile boolean focusGated = true;

    // Baseline for the unlocked pollPointerDelta() path (independent of the event loop).
    private boolean pollDeltaPrimed;
    private int pollDeltaX;
    private int pollDeltaY;

    private Tactroller(InputBackend backend) {
        this.backend = backend;
    }

    /**
     * Discover, instantiate and initialise the platform backend.
     *
     * @throws BackendException if no backend is present, more than one is present, or
     *                          initialisation fails
     */
    public static Tactroller open() throws BackendException {
        InputBackend resolved = null;
        for (InputBackend candidate : ServiceLoader.load(InputBackend.class)) {
            if (resolved != null) {
                throw new BackendException(
                        "Multiple InputBackend providers on the classpath: '" + resolved.name()
                                + "' and '" + candidate.name() + "'. Exactly one platform module is expected.");
            }
            resolved = candidate;
        }
        if (resolved == null) {
            throw new BackendException(
                    "No InputBackend provider found on the classpath. Add the platform module for this OS "
                            + "(tactroller-windows / -macos / -linux); a plain `mvn package` selects it automatically.");
        }
        resolved.initialize();
        return new Tactroller(resolved);
    }

    /** @return the identifier of the resolved backend, e.g. {@code "windows-user32"}. */
    public String backendName() {
        return backend.name();
    }

    // ---- One-shot polling -------------------------------------------------

    /** @return the current pointer position (in the configured {@link CoordinateSpace}) and buttons. */
    public PointerState pointer() throws BackendException {
        return inSpace(backend.pollPointer());
    }

    /** @return the current pointer position in screen space, ignoring the configured space. */
    public PointerState screenPointer() throws BackendException {
        return backend.pollPointer();
    }

    /** @return whether {@code key} is currently held down. */
    public boolean isKeyDown(Key key) throws BackendException {
        return backend.isKeyDown(key);
    }

    /**
     * Drain relative pointer motion since the last call. While the pointer is locked this is the
     * backend's captured device/recenter delta; while unlocked it is the change in absolute
     * position between successive calls (0 on the first call).
     */
    public PointerDelta pollPointerDelta() throws BackendException {
        if (backend.isPointerLocked()) {
            return backend.drainPointerDelta();
        }
        PointerState p = backend.pollPointer();
        if (!pollDeltaPrimed) {
            pollDeltaPrimed = true;
            pollDeltaX = p.x();
            pollDeltaY = p.y();
            return PointerDelta.ZERO;
        }
        PointerDelta d = new PointerDelta(p.x() - pollDeltaX, p.y() - pollDeltaY);
        pollDeltaX = p.x();
        pollDeltaY = p.y();
        return d;
    }

    /** Drain scroll-wheel motion since the last call. */
    public ScrollDelta pollScroll() {
        return backend.drainScroll();
    }

    // ---- Window attachment, focus, coordinate space ----------------------

    /** Attach a host window for focus gating and client-relative coordinates. */
    public void attach(NativeWindow window) throws BackendException {
        backend.attach(Objects.requireNonNull(window, "window"));
    }

    /** Detach the current window; coordinate space falls back to screen. */
    public void detach() {
        backend.detach();
    }

    /** @return whether a window is attached. */
    public boolean isAttached() {
        return backend.isWindowAttached();
    }

    /** @return whether the attached window is focused (always true when no window is attached). */
    public boolean isFocused() {
        return backend.isFocused();
    }

    /**
     * Set the coordinate space for pointer positions in polls and events. {@link CoordinateSpace#CLIENT}
     * requires an attached window at the time coordinates are read.
     */
    public void setCoordinateSpace(CoordinateSpace space) {
        this.coordinateSpace = Objects.requireNonNull(space, "space");
    }

    public CoordinateSpace coordinateSpace() {
        return coordinateSpace;
    }

    /**
     * When {@code true} (the default) and a window is attached, events are suppressed while the
     * window lacks focus, so background input does not reach the application.
     */
    public void setFocusGated(boolean gated) {
        this.focusGated = gated;
    }

    // ---- Pointer lock -----------------------------------------------------

    /** @return whether the backend supports pointer lock. */
    public boolean supportsPointerLock() {
        return backend.supportsPointerLock();
    }

    /** Capture the pointer for relative-motion input in the given mode and hide the cursor. */
    public void lockPointer(PointerLockMode mode) throws BackendException {
        backend.setPointerLock(Objects.requireNonNull(mode, "mode"));
    }

    /** Release the pointer and restore the cursor. */
    public void unlockPointer() {
        backend.clearPointerLock();
    }

    /** @return whether the pointer is currently locked. */
    public boolean isPointerLocked() {
        return backend.isPointerLocked();
    }

    // ---- Event listening --------------------------------------------------

    public void addListener(InputListener listener) {
        listeners.add(listener);
    }

    public void removeListener(InputListener listener) {
        listeners.remove(listener);
    }

    /** Replace the handler for loop/listener errors (default logs a warning). Must not be null. */
    public void setErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Start the event loop at {@link #DEFAULT_POLL_HZ}. No-op if already running. */
    public void start() {
        start(DEFAULT_POLL_HZ);
    }

    /** Start the event loop at the given sampling rate on a daemon thread. No-op if already running. */
    public synchronized void start(int pollHz) {
        if (pollHz <= 0) {
            throw new IllegalArgumentException("pollHz must be positive: " + pollHz);
        }
        if (loopThread != null) {
            return;
        }
        long periodNanos = 1_000_000_000L / pollHz;
        Thread t = new Thread(() -> runLoop(periodNanos), "tactroller-event-loop");
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }

    /** Stop the event loop and wait briefly for it to finish. No-op if not running. */
    public synchronized void stop() {
        Thread t = loopThread;
        if (t == null) {
            return;
        }
        loopThread = null;
        t.interrupt();
        try {
            t.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return loopThread != null;
    }

    private void runLoop(long periodNanos) {
        Set<Key> prevKeys = Set.of();
        Set<MouseButton> prevButtons = Set.of();
        int prevX = 0;
        int prevY = 0;
        boolean primed = false;
        boolean prevFocused = true;

        while (loopThread == Thread.currentThread() && !Thread.currentThread().isInterrupted()) {
            long tickStart = System.nanoTime();
            try {
                boolean focused = backend.isFocused();
                boolean gated = focusGated && backend.isWindowAttached() && !focused;

                // Always drain accumulators so they never build up while gated/idle.
                PointerDelta relMotion = backend.drainPointerDelta();
                ScrollDelta scroll = backend.drainScroll();
                PointerState pointer = backend.pollPointer();
                Set<Key> keys = backend.pollKeys();
                long now = System.nanoTime();

                if (focused != prevFocused && backend.isWindowAttached()) {
                    dispatch(new InputEvent.FocusChanged(focused, now));
                    prevFocused = focused;
                }

                if (!gated && primed) {
                    int[] xy = project(pointer.x(), pointer.y());
                    emitMotion(prevX, prevY, xy, pointer, relMotion, now);
                    emitScroll(scroll, xy, now);
                    emitButtons(prevButtons, pointer, xy, now);
                    emitKeys(prevKeys, keys, now);
                }

                prevKeys = keys;
                prevButtons = pointer.buttons();
                prevX = project(pointer.x(), pointer.y())[0];
                prevY = project(pointer.x(), pointer.y())[1];
                primed = true;
            } catch (BackendException e) {
                safelyReport(e);
            }

            long sleepNanos = periodNanos - (System.nanoTime() - tickStart);
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Project a screen point into the configured coordinate space (falls back to screen if detached). */
    private int[] project(int screenX, int screenY) {
        if (coordinateSpace == CoordinateSpace.CLIENT && backend.isWindowAttached()) {
            return backend.toClient(screenX, screenY);
        }
        return new int[] {screenX, screenY};
    }

    private PointerState inSpace(PointerState screen) {
        if (coordinateSpace == CoordinateSpace.CLIENT && backend.isWindowAttached()) {
            int[] c = backend.toClient(screen.x(), screen.y());
            return new PointerState(c[0], c[1], screen.buttons());
        }
        return screen;
    }

    private void emitMotion(int prevX, int prevY, int[] xy, PointerState now, PointerDelta rel, long ts) {
        if (backend.isPointerLocked()) {
            if (!rel.isZero()) {
                dispatch(new InputEvent.PointerMoved(xy[0], xy[1], rel.dx(), rel.dy(), ts));
            }
        } else if (xy[0] != prevX || xy[1] != prevY) {
            dispatch(new InputEvent.PointerMoved(xy[0], xy[1], xy[0] - prevX, xy[1] - prevY, ts));
        }
    }

    private void emitScroll(ScrollDelta scroll, int[] xy, long ts) {
        if (!scroll.isZero()) {
            dispatch(new InputEvent.Scrolled(scroll.x(), scroll.y(), xy[0], xy[1], ts));
        }
    }

    private void emitButtons(Set<MouseButton> prev, PointerState now, int[] xy, long ts) {
        for (MouseButton b : MouseButton.values()) {
            boolean was = prev.contains(b);
            boolean is = now.isPressed(b);
            if (is && !was) {
                dispatch(new InputEvent.ButtonPressed(b, xy[0], xy[1], ts));
            } else if (!is && was) {
                dispatch(new InputEvent.ButtonReleased(b, xy[0], xy[1], ts));
            }
        }
    }

    private void emitKeys(Set<Key> prev, Set<Key> now, long ts) {
        for (Key k : now) {
            if (!prev.contains(k)) {
                dispatch(new InputEvent.KeyPressed(k, ts));
            }
        }
        for (Key k : prev) {
            if (!now.contains(k)) {
                dispatch(new InputEvent.KeyReleased(k, ts));
            }
        }
    }

    private void dispatch(InputEvent event) {
        for (InputListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                safelyReport(e);
            }
        }
    }

    private void safelyReport(Throwable t) {
        try {
            errorHandler.accept(t);
        } catch (RuntimeException ignored) {
            // An error handler that itself throws must not kill the loop.
        }
    }

    @Override
    public void close() {
        stop();
        backend.clearPointerLock();
        backend.detach();
        backend.close();
    }
}
