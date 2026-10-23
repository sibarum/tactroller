package sibarum.tactroller.api;

import java.util.List;
import java.util.Set;

/**
 * An immutable per-frame input snapshot with edge information, produced by {@link Tactroller#snapshot()}.
 *
 * <p>This is the first-class API for the <b>render-thread polling</b> model: call {@code snapshot()}
 * once per frame on your render thread and read held state <i>and</i> edges
 * ({@link #wasPressed(Key)} / {@link #wasReleased(Key)}) without running the background event loop
 * or diffing state by hand. Because a single thread both produces and consumes the frame, it also
 * sidesteps the accumulator-hand-off between the pump thread and the event-loop thread.
 *
 * <p>Coordinates ({@link #pointerX()}/{@link #pointerY()}) are in the {@link CoordinateSpace}
 * configured on the {@code Tactroller} at snapshot time. {@link #motion()} is the relative pointer
 * delta since the previous snapshot (the captured device/recenter delta while pointer-locked).
 * {@link #scroll()} is the wheel movement since the previous snapshot.
 *
 * <p>{@link #typedChars()} are the Unicode code points produced by the keyboard this frame (layout-
 * and dead-key-resolved text; see {@link InputEvent.CharTyped}), distinct from the {@code KeyPressed}
 * command channel. The array is defensively cloned on construction and read-back; note it is compared
 * by identity in the record's generated {@code equals} (frames are not value-compared in practice).
 *
 * <p>{@link #touchpadContacts()} are the fingers on the touchpad at snapshot time (empty when the surface is clear
 * or the backend reads no touchpad). Feed frames to a {@link TouchpadGesture} for pinches, pans and taps.
 *
 * <p>{@link #edges()} are every key and button press and release since the previous snapshot, in the order they
 * happened, each with its own time and (for a button) pointer position. This is the lossless view: a click that began
 * and ended inside the frame is a press and a release here, and two such clicks are four edges. The edge <em>sets</em>
 * ({@link #pressedKeys()}, {@link #releasedButtons()}, ...) summarise them: everything with at least one press, or one
 * release, among the edges. So a quick click has its button in both {@code pressedButtons} and {@code releasedButtons}
 * and not in {@code heldButtons}; only the list says which came first, and how many times.
 */
public record InputFrame(
        Set<Key> heldKeys,
        Set<Key> pressedKeys,
        Set<Key> releasedKeys,
        Set<MouseButton> heldButtons,
        Set<MouseButton> pressedButtons,
        Set<MouseButton> releasedButtons,
        Set<Modifier> modifiers,
        int pointerX,
        int pointerY,
        PointerDelta motion,
        ScrollDelta scroll,
        boolean focused,
        long timestampNanos,
        int[] typedChars,
        boolean pointerTarget,
        List<TouchpadContact> touchpadContacts,
        List<InputEdge> edges) {

    public InputFrame {
        edges = List.copyOf(edges);
        touchpadContacts = List.copyOf(touchpadContacts);
        heldKeys = Set.copyOf(heldKeys);
        pressedKeys = Set.copyOf(pressedKeys);
        releasedKeys = Set.copyOf(releasedKeys);
        heldButtons = Set.copyOf(heldButtons);
        pressedButtons = Set.copyOf(pressedButtons);
        releasedButtons = Set.copyOf(releasedButtons);
        modifiers = Set.copyOf(modifiers);
        typedChars = typedChars == null || typedChars.length == 0 ? InputBackend.NO_CHARS : typedChars.clone();
    }

    /**
     * Backward-compatible constructor for a frame with no typed characters (keeps existing call sites
     * that predate the text channel working unchanged).
     */
    public InputFrame(
            Set<Key> heldKeys,
            Set<Key> pressedKeys,
            Set<Key> releasedKeys,
            Set<MouseButton> heldButtons,
            Set<MouseButton> pressedButtons,
            Set<MouseButton> releasedButtons,
            Set<Modifier> modifiers,
            int pointerX,
            int pointerY,
            PointerDelta motion,
            ScrollDelta scroll,
            boolean focused,
            long timestampNanos) {
        this(heldKeys, pressedKeys, releasedKeys, heldButtons, pressedButtons, releasedButtons, modifiers,
                pointerX, pointerY, motion, scroll, focused, timestampNanos, InputBackend.NO_CHARS);
    }

    /**
     * Backward-compatible constructor predating the positional routing gate: this window is reported as the
     * pointer's target, which is the single-window answer.
     */
    public InputFrame(
            Set<Key> heldKeys,
            Set<Key> pressedKeys,
            Set<Key> releasedKeys,
            Set<MouseButton> heldButtons,
            Set<MouseButton> pressedButtons,
            Set<MouseButton> releasedButtons,
            Set<Modifier> modifiers,
            int pointerX,
            int pointerY,
            PointerDelta motion,
            ScrollDelta scroll,
            boolean focused,
            long timestampNanos,
            int[] typedChars) {
        this(heldKeys, pressedKeys, releasedKeys, heldButtons, pressedButtons, releasedButtons, modifiers,
                pointerX, pointerY, motion, scroll, focused, timestampNanos, typedChars, true);
    }

    /** Backward-compatible constructor predating touchpad contacts: the surface is reported clear. */
    public InputFrame(
            Set<Key> heldKeys,
            Set<Key> pressedKeys,
            Set<Key> releasedKeys,
            Set<MouseButton> heldButtons,
            Set<MouseButton> pressedButtons,
            Set<MouseButton> releasedButtons,
            Set<Modifier> modifiers,
            int pointerX,
            int pointerY,
            PointerDelta motion,
            ScrollDelta scroll,
            boolean focused,
            long timestampNanos,
            int[] typedChars,
            boolean pointerTarget) {
        this(heldKeys, pressedKeys, releasedKeys, heldButtons, pressedButtons, releasedButtons, modifiers,
                pointerX, pointerY, motion, scroll, focused, timestampNanos, typedChars, pointerTarget, List.of());
    }

    /**
     * Backward-compatible constructor predating ordered edges: the edges are read off the sets, in the order a frame
     * published them before there was a list (key presses, key releases, button presses, button releases), all at
     * the frame's time and buttons at its pointer position. Which is all the sets can say.
     */
    public InputFrame(
            Set<Key> heldKeys,
            Set<Key> pressedKeys,
            Set<Key> releasedKeys,
            Set<MouseButton> heldButtons,
            Set<MouseButton> pressedButtons,
            Set<MouseButton> releasedButtons,
            Set<Modifier> modifiers,
            int pointerX,
            int pointerY,
            PointerDelta motion,
            ScrollDelta scroll,
            boolean focused,
            long timestampNanos,
            int[] typedChars,
            boolean pointerTarget,
            List<TouchpadContact> touchpadContacts) {
        this(heldKeys, pressedKeys, releasedKeys, heldButtons, pressedButtons, releasedButtons, modifiers,
                pointerX, pointerY, motion, scroll, focused, timestampNanos, typedChars, pointerTarget, touchpadContacts,
                edgesOf(pressedKeys, releasedKeys, pressedButtons, releasedButtons, pointerX, pointerY, timestampNanos));
    }

    private static List<InputEdge> edgesOf(Set<Key> pressedKeys, Set<Key> releasedKeys,
                                           Set<MouseButton> pressedButtons, Set<MouseButton> releasedButtons,
                                           int x, int y, long ts) {
        List<InputEdge> edges = new java.util.ArrayList<>();
        for (Key k : pressedKeys) {
            edges.add(InputEdge.key(k, true, ts));
        }
        for (Key k : releasedKeys) {
            edges.add(InputEdge.key(k, false, ts));
        }
        for (MouseButton b : pressedButtons) {
            edges.add(InputEdge.button(b, true, x, y, ts));
        }
        for (MouseButton b : releasedButtons) {
            edges.add(InputEdge.button(b, false, x, y, ts));
        }
        return edges;
    }

    /** @return a copy of the code points typed this frame (empty if none), in order. */
    public int[] typedChars() {
        return typedChars.length == 0 ? typedChars : typedChars.clone();
    }

    /** @return whether {@code key} is held this frame. */
    public boolean isKeyDown(Key key) {
        return heldKeys.contains(key);
    }

    /** @return whether {@code key} was pressed at least once between the previous frame and this one. */
    public boolean wasPressed(Key key) {
        return pressedKeys.contains(key);
    }

    /** @return whether {@code key} was released at least once between the previous frame and this one. */
    public boolean wasReleased(Key key) {
        return releasedKeys.contains(key);
    }

    /** @return whether {@code button} is held this frame. */
    public boolean isButtonDown(MouseButton button) {
        return heldButtons.contains(button);
    }

    /** @return whether {@code button} was pressed at least once this frame. */
    public boolean wasPressed(MouseButton button) {
        return pressedButtons.contains(button);
    }

    /** @return whether {@code button} was released at least once this frame. */
    public boolean wasReleased(MouseButton button) {
        return releasedButtons.contains(button);
    }

    /** @return whether {@code modifier} is active this frame. */
    public boolean hasModifier(Modifier modifier) {
        return modifiers.contains(modifier);
    }
}
