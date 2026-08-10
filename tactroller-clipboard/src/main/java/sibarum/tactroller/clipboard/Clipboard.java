package sibarum.tactroller.clipboard;

import sibarum.tactroller.clipboard.linux.LinuxClipboardBackend;
import sibarum.tactroller.clipboard.macos.MacosClipboardBackend;
import sibarum.tactroller.clipboard.windows.WindowsClipboardBackend;

import java.util.Locale;
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
 * <p>The platform backend is selected at runtime from {@code os.name}. Windows is implemented;
 * other platforms throw {@link ClipboardException} from {@link #open()} until their bindings land.
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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsClipboardBackend();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacosClipboardBackend();
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return new LinuxClipboardBackend();
        }
        throw new ClipboardException("No clipboard backend for OS: " + os);
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
