package sibarum.tactroller.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Service-provider interface implemented once per target OS. A backend is the thin, stateful
 * bridge between the platform's native input APIs (bound via Panama in the platform modules)
 * and Tactroller's OS-agnostic types.
 *
 * <p>Implementations are discovered at runtime through {@link java.util.ServiceLoader}; each
 * platform module ships a {@code META-INF/services/sibarum.tactroller.api.InputBackend} entry.
 * Exactly one backend is expected to be present on the classpath, selected at build time by the
 * OS-activated Maven profile that pulled in the matching platform module.
 *
 * <p>Backends are polling-based: {@link #pollPointer()} and {@link #isKeyDown(Key)} read the
 * current hardware state on demand rather than delivering an event stream. This keeps the SPI
 * trivial to bind natively and friendly to GraalVM native-image, where callback registration
 * across the FFM boundary is more involved.
 *
 * <p>Instances are not required to be thread-safe; callers should confine a backend to a single
 * polling thread unless the implementation documents otherwise.
 */
public interface InputBackend extends AutoCloseable {

    /**
     * A short, stable identifier for this backend, e.g. {@code "windows-user32"}. Useful for
     * logging and diagnostics; not parsed by the framework.
     */
    String name();

    /**
     * Acquire any native resources (open library handles, event nodes, sources). Called once by
     * {@link Tactroller} before the first poll. Implementations must tolerate being initialised
     * exactly once.
     *
     * @throws BackendException if the platform's input subsystem could not be reached
     */
    void initialize() throws BackendException;

    /**
     * @return a fresh snapshot of the pointer's position and pressed buttons.
     * @throws BackendException if the native query failed
     */
    PointerState pollPointer() throws BackendException;

    /**
     * @param key the logical key to test
     * @return {@code true} if the key is currently held down.
     * @throws BackendException if the native query failed
     */
    boolean isKeyDown(Key key) throws BackendException;

    /**
     * Snapshot every key currently held down in a single query. The default polls each key via
     * {@link #isKeyDown(Key)}; backends whose native API can report the whole keyboard at once
     * (e.g. X11's {@code XQueryKeymap}) should override this for efficiency and to keep every key
     * in the snapshot consistent to the same instant.
     *
     * <p>Used by the shared event loop to derive {@code KeyPressed}/{@code KeyReleased} events, so
     * the semantics of this method are identical across platforms by construction.
     *
     * @return the set of pressed keys; never {@code null}
     * @throws BackendException if the native query failed
     */
    default Set<Key> pollKeys() throws BackendException {
        EnumSet<Key> down = EnumSet.noneOf(Key.class);
        for (Key key : Key.values()) {
            if (isKeyDown(key)) {
                down.add(key);
            }
        }
        return down;
    }

    /** Release native resources. Idempotent. */
    @Override
    void close();
}
