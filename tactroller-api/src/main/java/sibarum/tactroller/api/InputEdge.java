package sibarum.tactroller.api;

/**
 * One press or release of a key or a mouse button, at the moment it happened.
 *
 * <p>A frame is a poll, and a poll sees state: what is down now. A press and release that both fall between two
 * polls leave the state unchanged, so a click quicker than a frame never happened as far as polling can tell, and
 * two clicks inside one frame are at best one. Edges are what the poll cannot see. A backend that is an event
 * source for keys and buttons queues them as they arrive ({@link InputBackend#drainEdges()}), and {@link
 * InputFrame#edges()} carries every one of them, in order, from one frame to the next.
 *
 * <p>Handled through {@link #to(Sink)}: an edge hands itself to the sink method for its kind, and the sink has one
 * method per kind and no defaults, so a new kind is a compile error at every consumer rather than a silent miss.
 */
public interface InputEdge {

    /** Monotonic capture time from {@link System#nanoTime()}, taken when the edge happened, not when it was read. */
    long timestampNanos();

    /** Hand this edge to the {@code sink} method for its kind. */
    void to(Sink sink);

    /** A key went down ({@code down}) or up. */
    static InputEdge key(Key key, boolean down, long timestampNanos) {
        return new KeyEdge(key, down, timestampNanos);
    }

    /**
     * A mouse button went down ({@code down}) or up, with the pointer at {@code (x, y)} at that moment: screen
     * pixels from a backend, the {@link Tactroller}'s {@link CoordinateSpace} in a frame.
     */
    static InputEdge button(MouseButton button, boolean down, int x, int y, long timestampNanos) {
        return new ButtonEdge(button, down, x, y, timestampNanos);
    }

    /** What an edge is handed to. One method per kind of edge; deliberately no defaults. */
    interface Sink {
        void keyPressed(Key key, long timestampNanos);

        void keyReleased(Key key, long timestampNanos);

        void buttonPressed(MouseButton button, int x, int y, long timestampNanos);

        void buttonReleased(MouseButton button, int x, int y, long timestampNanos);
    }

    /** A key edge. */
    record KeyEdge(Key key, boolean down, long timestampNanos) implements InputEdge {
        @Override
        public void to(Sink sink) {
            if (down) {
                sink.keyPressed(key, timestampNanos);
            } else {
                sink.keyReleased(key, timestampNanos);
            }
        }
    }

    /** A mouse-button edge, with where the pointer was. */
    record ButtonEdge(MouseButton button, boolean down, int x, int y, long timestampNanos) implements InputEdge {
        @Override
        public void to(Sink sink) {
            if (down) {
                sink.buttonPressed(button, x, y, timestampNanos);
            } else {
                sink.buttonReleased(button, x, y, timestampNanos);
            }
        }
    }
}
