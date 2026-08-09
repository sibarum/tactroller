package sibarum.tactroller.api;

/** Thrown when a platform backend cannot reach or query the OS input subsystem. */
public class BackendException extends Exception {

    public BackendException(String message) {
        super(message);
    }

    public BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
