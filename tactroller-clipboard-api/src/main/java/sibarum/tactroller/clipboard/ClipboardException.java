package sibarum.tactroller.clipboard;

/** Thrown when a clipboard operation fails or the platform clipboard cannot be reached. */
public class ClipboardException extends Exception {

    public ClipboardException(String message) {
        super(message);
    }

    public ClipboardException(String message, Throwable cause) {
        super(message, cause);
    }
}
