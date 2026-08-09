package sibarum.tactroller.api;

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
        long timestampNanos) {

    public InputFrame {
        heldKeys = Set.copyOf(heldKeys);
        pressedKeys = Set.copyOf(pressedKeys);
        releasedKeys = Set.copyOf(releasedKeys);
        heldButtons = Set.copyOf(heldButtons);
        pressedButtons = Set.copyOf(pressedButtons);
        releasedButtons = Set.copyOf(releasedButtons);
        modifiers = Set.copyOf(modifiers);
    }

    /** @return whether {@code key} is held this frame. */
    public boolean isKeyDown(Key key) {
        return heldKeys.contains(key);
    }

    /** @return whether {@code key} transitioned to down between the previous frame and this one. */
    public boolean wasPressed(Key key) {
        return pressedKeys.contains(key);
    }

    /** @return whether {@code key} transitioned to up between the previous frame and this one. */
    public boolean wasReleased(Key key) {
        return releasedKeys.contains(key);
    }

    /** @return whether {@code button} is held this frame. */
    public boolean isButtonDown(MouseButton button) {
        return heldButtons.contains(button);
    }

    /** @return whether {@code button} transitioned to down this frame. */
    public boolean wasPressed(MouseButton button) {
        return pressedButtons.contains(button);
    }

    /** @return whether {@code button} transitioned to up this frame. */
    public boolean wasReleased(MouseButton button) {
        return releasedButtons.contains(button);
    }

    /** @return whether {@code modifier} is active this frame. */
    public boolean hasModifier(Modifier modifier) {
        return modifiers.contains(modifier);
    }
}
