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
 * <p><b>Draining:</b> {@link #pollPointerDelta()}/{@link #pollScroll()}/{@link #snapshot()} and the
 * running event loop all consume the backend's relative-motion and scroll accumulators. Use one or
 * the other for a given signal; mixing them splits the stream between consumers.
 *
 * <h2>Thread-affinity contract</h2>
 * <ul>
 *   <li>The backend owns any native event source (on Windows, a message-only RawInput window on its
 *       own pump thread); it does <b>not</b> require the embedding application's window or its event
 *       pump to be running, and it registers RawInput against its own window, not the app's HWND.</li>
 *   <li>Relative-motion and scroll accumulators are updated on that pump thread and drained via
 *       atomic swaps, so it is safe to drain them from a different thread than the one filling them.</li>
 *   <li><b>Recommended for an engine with its own render loop (e.g. a first-person view):</b> do not
 *       call {@link #start()}. Instead call {@link #snapshot()} (or the {@code poll*} methods) once
 *       per frame on the render thread. This keeps produce-and-consume on one thread and gives edges
 *       for free.</li>
 *   <li>{@link #start()} spins a separate daemon that samples at a fixed rate — use it for
 *       retained-mode/event-driven consumers, not alongside per-frame polling of the same signals.</li>
 * </ul>
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

    // Baseline for snapshot() edge diffing (render-thread polling path).
    private boolean snapPrimed;
    private Set<Key> snapPrevKeys = Set.of();
    private Set<MouseButton> snapPrevButtons = Set.of();
    private int snapPrevX;
    private int snapPrevY;

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

    /** @return whether {@code button} is currently held down (allocation-free on supporting backends). */
    public boolean isButtonDown(MouseButton button) throws BackendException {
        return backend.isButtonDown(button);
    }

    /** @return the currently active keyboard modifiers. */
    public Set<Modifier> modifiers() throws BackendException {
        return Modifier.from(backend.pollKeys());
    }

    /** @return the attached window's content scale (1.0 at 100% DPI, 1.5 at 150%); 1.0 if detached. */
    public double contentScale() {
        return backend.contentScale();
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

    /**
     * Capture an immutable {@link InputFrame} for this frame: held keys/buttons/modifiers, the
     * pressed/released edges since the previous snapshot, relative motion, scroll, focus and pointer
     * position (in the configured {@link CoordinateSpace}).
     *
     * <p>This is the render-thread polling path: call it once per frame from a single thread. It
     * drains the relative-motion and scroll accumulators, so — as with {@link #pollPointerDelta()}
     * / {@link #pollScroll()} — do not also run the event loop for the same signals. The first
     * snapshot reports no edges (it establishes the baseline).
     */
    public synchronized InputFrame snapshot() throws BackendException {
        boolean focused = backend.isFocused();
        ScrollDelta scroll = backend.drainScroll();
        PointerState p = backend.pollPointer();
        Set<Key> keys = backend.pollKeys();
        Set<MouseButton> buttons = p.buttons();
        int[] xy = project(p.x(), p.y());
        long now = System.nanoTime();

        PointerDelta motion;
        if (backend.isPointerLocked()) {
            motion = backend.drainPointerDelta();
        } else if (snapPrimed) {
            motion = new PointerDelta(xy[0] - snapPrevX, xy[1] - snapPrevY);
        } else {
            motion = PointerDelta.ZERO;
        }

        Set<Key> pressedKeys = snapPrimed ? minus(keys, snapPrevKeys) : Set.of();
        Set<Key> releasedKeys = snapPrimed ? minus(snapPrevKeys, keys) : Set.of();
        Set<MouseButton> pressedBtn = snapPrimed ? minus(buttons, snapPrevButtons) : Set.of();
        Set<MouseButton> releasedBtn = snapPrimed ? minus(snapPrevButtons, buttons) : Set.of();

        InputFrame frame = new InputFrame(
                keys, pressedKeys, releasedKeys,
                buttons, pressedBtn, releasedBtn,
                Modifier.from(keys),
                xy[0], xy[1], motion, scroll, focused, now);

        snapPrevKeys = keys;
        snapPrevButtons = buttons;
        snapPrevX = xy[0];
        snapPrevY = xy[1];
        snapPrimed = true;
        return frame;
    }

    private static <T extends Enum<T>> Set<T> minus(Set<T> a, Set<T> b) {
        if (a.isEmpty()) {
            return Set.of();
        }
        java.util.EnumSet<T> r = java.util.EnumSet.copyOf(a);
        r.removeAll(b);
        return r;
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
        if (!backend.isWindowAttached()) {
            return new int[] {screenX, screenY};
        }
        return switch (coordinateSpace) {
            case SCREEN -> new int[] {screenX, screenY};
            case CLIENT -> backend.toClient(screenX, screenY);
            case FRAMEBUFFER -> {
                int[] c = backend.toClient(screenX, screenY);
                double s = backend.contentScale();
                yield new int[] {(int) Math.round(c[0] * s), (int) Math.round(c[1] * s)};
            }
        };
    }

    private PointerState inSpace(PointerState screen) {
        int[] p = project(screen.x(), screen.y());
        if (p[0] == screen.x() && p[1] == screen.y()) {
            return screen;
        }
        return new PointerState(p[0], p[1], screen.buttons());
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
