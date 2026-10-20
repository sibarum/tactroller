package sibarum.tactroller.clipboard;


import java.util.Objects;
import java.util.Optional;

/**
 * The system clipboard, for reading and writing text. Standalone: this module does not depend on
 * the Tactroller input subsystem, so a GUI can use it on its own.
 *
 * <pre>{@code
 * try (Clipboard cb = Clipboard.open()) {
 *     cb.setText("hello");
 *     Optional<String> pasted = cb.getText();
 * }
 * }</pre>
 *
 * <p>The platform backend is the one {@link ClipboardBackend} on the class path, found through
 * {@link java.util.ServiceLoader}: {@code tactroller-clipboard-windows} or {@code tactroller-clipboard-linux}, which
 * this module's OS-activated build profile adds. This module holds no platform knowledge of its own. Where there is
 * no OS module (macOS, today), {@link #open()} throws {@link ClipboardException}.
 */
public final class Clipboard implements AutoCloseable {

    private final ClipboardBackend backend;

    private Clipboard(ClipboardBackend backend) {
        this.backend = backend;
    }

    /** Resolve, initialise and open the host platform's clipboard backend. */
    public static Clipboard open() throws ClipboardException {
        ClipboardBackend backend = resolveBackend();
        backend.initialize();
        return new Clipboard(backend);
    }

    private static ClipboardBackend resolveBackend() throws ClipboardException {
        java.util.List<ClipboardBackend> found = java.util.ServiceLoader.load(ClipboardBackend.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .toList();
        if (found.size() != 1) {
            throw new ClipboardException((found.isEmpty() ? "no" : found.size() + "")
                    + " clipboard OS modules on the class path; exactly one of tactroller-clipboard-windows or "
                    + "tactroller-clipboard-linux is needed, which tactroller-clipboard's OS-activated profile adds");
        }
        return found.getFirst();
    }

    /** @return the identifier of the resolved backend. */
    public String backendName() {
        return backend.name();
    }

    /** @return the clipboard's current text, or empty if it holds no text. */
    public Optional<String> getText() throws ClipboardException {
        return backend.getText();
    }

    /** Replace the clipboard contents with {@code text}. */
    public void setText(String text) throws ClipboardException {
        backend.setText(Objects.requireNonNull(text, "text"));
    }

    /** @return whether the clipboard currently holds text. */
    public boolean hasText() throws ClipboardException {
        return backend.hasText();
    }

    @Override
    public void close() {
        backend.close();
    }
}
