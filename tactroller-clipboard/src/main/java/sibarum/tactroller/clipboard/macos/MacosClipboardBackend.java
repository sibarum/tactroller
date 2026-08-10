package sibarum.tactroller.clipboard.macos;

import sibarum.tactroller.clipboard.ClipboardBackend;
import sibarum.tactroller.clipboard.ClipboardException;

import java.util.Optional;

/**
 * Placeholder macOS clipboard backend. A full implementation will bind {@code NSPasteboard}
 * (AppKit) via Panama. Until then every operation fails clearly.
 */
public final class MacosClipboardBackend implements ClipboardBackend {

    @Override
    public String name() {
        return "macos-nspasteboard";
    }

    @Override
    public void initialize() throws ClipboardException {
        throw new ClipboardException("macOS clipboard backend is not yet implemented");
    }

    @Override
    public Optional<String> getText() throws ClipboardException {
        throw new ClipboardException("macOS clipboard backend is not yet implemented");
    }

    @Override
    public void setText(String text) throws ClipboardException {
        throw new ClipboardException("macOS clipboard backend is not yet implemented");
    }

    @Override
    public void close() {
        // nothing to release
    }
}
