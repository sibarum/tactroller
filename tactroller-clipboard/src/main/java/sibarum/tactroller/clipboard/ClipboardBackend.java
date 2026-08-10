package sibarum.tactroller.clipboard;

import java.util.Optional;

/**
 * Per-OS implementation of system-clipboard text access. One implementation exists per platform;
 * the host's is chosen at runtime by {@link Clipboard#open()}.
 *
 * <p>Implementations are not required to be thread-safe.
 */
public interface ClipboardBackend extends AutoCloseable {

    /** Short, stable identifier, e.g. {@code "windows-user32"}. */
    String name();

    /**
     * Acquire native resources. Called once before the first operation.
     *
     * @throws ClipboardException if the platform clipboard is unavailable or not yet supported
     */
    void initialize() throws ClipboardException;

    /**
     * @return the clipboard's current text, or empty if the clipboard holds no text.
     * @throws ClipboardException if the read failed
     */
    Optional<String> getText() throws ClipboardException;

    /**
     * Replace the clipboard contents with {@code text}.
     *
     * @throws ClipboardException if the write failed
     */
    void setText(String text) throws ClipboardException;

    /**
     * @return whether the clipboard currently holds text. The default derives this from
     *         {@link #getText()}; backends may override with a cheaper native availability check.
     */
    default boolean hasText() throws ClipboardException {
        return getText().isPresent();
    }

    /** Release native resources. Idempotent. */
    @Override
    void close();
}
