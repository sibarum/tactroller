package sibarum.tactroller.clipboard.linux;

import sibarum.tactroller.clipboard.ClipboardBackend;
import sibarum.tactroller.clipboard.ClipboardException;

import java.util.Optional;

/**
 * Placeholder Linux clipboard backend. A full implementation will bind X11 selections
 * ({@code XSetSelectionOwner}/{@code XConvertSelection} on {@code CLIPBOARD}) — or a Wayland
 * data-device path — via Panama. Until then every operation fails clearly.
 */
public final class LinuxClipboardBackend implements ClipboardBackend {

    @Override
    public String name() {
        return "linux-x11";
    }

    @Override
    public void initialize() throws ClipboardException {
        throw new ClipboardException("Linux clipboard backend is not yet implemented");
    }

    @Override
    public Optional<String> getText() throws ClipboardException {
        throw new ClipboardException("Linux clipboard backend is not yet implemented");
    }

    @Override
    public void setText(String text) throws ClipboardException {
        throw new ClipboardException("Linux clipboard backend is not yet implemented");
    }

    @Override
    public void close() {
        // nothing to release
    }
}
