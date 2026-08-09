package sibarum.tactroller.api;

import java.util.Set;

/**
 * An immutable snapshot of the pointer at one instant: its position in virtual-screen
 * coordinates and the set of buttons currently held.
 *
 * @param x       horizontal position in virtual-screen pixels (origin is platform-defined,
 *                typically the top-left of the primary display)
 * @param y       vertical position in virtual-screen pixels
 * @param buttons the buttons held down at the moment of capture; never {@code null}
 */
public record PointerState(int x, int y, Set<MouseButton> buttons) {

    public PointerState {
        buttons = Set.copyOf(buttons);
    }

    /** @return {@code true} if {@code button} was held when this snapshot was taken. */
    public boolean isPressed(MouseButton button) {
        return buttons.contains(button);
    }
}
