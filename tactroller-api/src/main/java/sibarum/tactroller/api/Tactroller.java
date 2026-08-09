package sibarum.tactroller.api;

import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The middleware entry point. Resolves the single platform {@link InputBackend} on the classpath,
 * initialises it, and exposes an OS-agnostic API for both one-shot polling and continuous event
 * listening.
 *
 * <p><b>Polling</b> — call {@link #pointer()} / {@link #isKeyDown(Key)} to read the current state.
 *
 * <p><b>Event listening</b> — register {@link InputListener}s and {@link #start()} the loop to
 * receive {@link InputEvent}s (key press/release, pointer moves, button press/release):
 * <pre>{@code
 * try (Tactroller t = Tactroller.open()) {
 *     t.addListener(e -> {
 *         switch (e) {
 *             case InputEvent.KeyPressed k    -> System.out.println("down " + k.key());
 *             case InputEvent.ButtonPressed b -> System.out.println("click " + b.button());
 *             default -> {}
 *         }
 *     });
 *     t.start();
 *     Thread.sleep(10_000);
 * }
 * }</pre>
 *
 * <p>The event loop lives entirely in this API module: it samples the backend and diffs consecutive
 * snapshots to derive events. That derivation is the same code on every OS, so the event stream is
 * identical regardless of the native backend underneath.
 *
 * <p>Which backend is resolved is decided at build time: the OS-activated Maven profile in the
 * parent POM pulls in exactly one of {@code tactroller-windows}, {@code tactroller-macos} or
 * {@code tactroller-linux}, and that module's {@code META-INF/services} entry is what
 * {@link ServiceLoader} finds here.
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

    private Tactroller(InputBackend backend) {
        this.backend = backend;
    }

    /**
     * Discover, instantiate and initialise the platform backend.
     *
     * @return an open Tactroller bound to this machine's input subsystem
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

    /** @return the current pointer position and pressed buttons. */
    public PointerState pointer() throws BackendException {
        return backend.pollPointer();
    }

    /** @return whether {@code key} is currently held down. */
    public boolean isKeyDown(Key key) throws BackendException {
        return backend.isKeyDown(key);
    }

    // ---- Event listening --------------------------------------------------

    /** Register a listener. Safe to call before or during a running loop. */
    public void addListener(InputListener listener) {
        listeners.add(listener);
    }

    /** Remove a previously registered listener. */
    public void removeListener(InputListener listener) {
        listeners.remove(listener);
    }

    /**
     * Set the handler invoked when the event loop hits a {@link BackendException} while sampling,
     * or when a listener throws. Replaces the default (which logs a warning). Must not be null.
     */
    public void setErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = java.util.Objects.requireNonNull(handler, "handler");
    }

    /** Start the event loop at {@link #DEFAULT_POLL_HZ}. No-op if already running. */
    public void start() {
        start(DEFAULT_POLL_HZ);
    }

    /**
     * Start the event loop at the given sampling rate on a daemon thread. No-op if already running.
     *
     * @param pollHz samples per second; higher catches faster presses at more CPU cost
     */
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

    /** @return whether the event loop is currently running. */
    public boolean isRunning() {
        return loopThread != null;
    }

    private void runLoop(long periodNanos) {
        // Baseline: the first sample establishes state without emitting synthetic events.
        Set<Key> prevKeys = Set.of();
        Set<MouseButton> prevButtons = Set.of();
        int prevX = 0;
        int prevY = 0;
        boolean primed = false;

        while (loopThread == Thread.currentThread() && !Thread.currentThread().isInterrupted()) {
            long tickStart = System.nanoTime();
            try {
                PointerState pointer = backend.pollPointer();
                Set<Key> keys = backend.pollKeys();
                long now = System.nanoTime();

                if (primed) {
                    emitPointer(prevX, prevY, pointer, now);
                    emitButtons(prevButtons, pointer, now);
                    emitKeys(prevKeys, keys, now);
                }

                prevKeys = keys;
                prevButtons = pointer.buttons();
                prevX = pointer.x();
                prevY = pointer.y();
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

    private void emitPointer(int prevX, int prevY, PointerState now, long ts) {
        if (now.x() != prevX || now.y() != prevY) {
            dispatch(new InputEvent.PointerMoved(now.x(), now.y(), now.x() - prevX, now.y() - prevY, ts));
        }
    }

    private void emitButtons(Set<MouseButton> prev, PointerState now, long ts) {
        for (MouseButton b : MouseButton.values()) {
            boolean was = prev.contains(b);
            boolean is = now.isPressed(b);
            if (is && !was) {
                dispatch(new InputEvent.ButtonPressed(b, now.x(), now.y(), ts));
            } else if (!is && was) {
                dispatch(new InputEvent.ButtonReleased(b, now.x(), now.y(), ts));
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
        backend.close();
    }
}
