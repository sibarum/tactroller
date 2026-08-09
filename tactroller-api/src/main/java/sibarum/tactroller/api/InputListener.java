package sibarum.tactroller.api;

/**
 * Receives {@link InputEvent}s from a {@link Tactroller} event loop. Registered via
 * {@link Tactroller#addListener(InputListener)}.
 *
 * <p>Callbacks are invoked on the event-loop thread, one event at a time. Keep them short; offload
 * heavy work to another thread. An exception thrown from a listener is isolated — it is reported to
 * the loop's error handler and does not stop delivery to other listeners or halt the loop.
 */
@FunctionalInterface
public interface InputListener {

    void onEvent(InputEvent event);
}
