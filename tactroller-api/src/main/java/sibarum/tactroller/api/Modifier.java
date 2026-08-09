package sibarum.tactroller.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Keyboard modifier, collapsing the left/right variants of a physical modifier into one logical
 * flag. Derived from held {@link Key}s; see {@link #from(Set)}.
 */
public enum Modifier {
    SHIFT,
    CONTROL,
    ALT,
    /** The "super"/meta key: Windows key, macOS Command, Linux Super. */
    SUPER;

    /** Derive the active modifiers from a set of held keys. */
    public static Set<Modifier> from(Set<Key> heldKeys) {
        EnumSet<Modifier> mods = EnumSet.noneOf(Modifier.class);
        if (heldKeys.contains(Key.LEFT_SHIFT) || heldKeys.contains(Key.RIGHT_SHIFT)) {
            mods.add(SHIFT);
        }
        if (heldKeys.contains(Key.LEFT_CONTROL) || heldKeys.contains(Key.RIGHT_CONTROL)) {
            mods.add(CONTROL);
        }
        if (heldKeys.contains(Key.LEFT_ALT) || heldKeys.contains(Key.RIGHT_ALT)) {
            mods.add(ALT);
        }
        if (heldKeys.contains(Key.LEFT_SUPER) || heldKeys.contains(Key.RIGHT_SUPER)) {
            mods.add(SUPER);
        }
        return mods;
    }
}
