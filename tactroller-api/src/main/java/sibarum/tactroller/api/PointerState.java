package sibarum.tactroller.api;

import java.util.Set;

/**
 * An immutable snapshot of the pointer at one instant: its position in the {@link Tactroller}'s
 * configured {@link CoordinateSpace} and the set of buttons currently held.
 *
 * @param x       horizontal position in the configured {@link CoordinateSpace} — virtual-screen
 *                pixels unless it was changed (origin platform-defined, typically the primary
 *                display's top-left)
 * @param y       vertical position, in the same space as {@code x}
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
