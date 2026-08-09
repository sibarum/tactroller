package sibarum.tactroller.api;

/**
 * Platform-neutral identifiers for the keys Tactroller can report on.
 *
 * <p>These are logical keys, not scan codes: each platform binding is responsible for mapping
 * its OS-specific virtual-key or key-symbol space onto these constants. Consumers switch on the
 * enum rather than on raw integers, so new constants can be appended without breaking them. A key
 * a backend cannot map is simply never reported as pressed (rather than being an error), which
 * keeps polling robust as this set grows.
 *
 * <p>New constants are appended at the end so existing ordinals stay stable. All ordinals are kept
 * below 128 to leave room for a two-word bitmask representation.
 */
public enum Key {
    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,

    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4,
    DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,

    SPACE, ENTER, ESCAPE, TAB, BACKSPACE, DELETE,
    LEFT, RIGHT, UP, DOWN,

    LEFT_SHIFT, RIGHT_SHIFT,
    LEFT_CONTROL, RIGHT_CONTROL,
    LEFT_ALT, RIGHT_ALT,
    LEFT_SUPER, RIGHT_SUPER,

    // --- appended: function keys ---
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    // --- appended: navigation / editing cluster ---
    INSERT, HOME, END, PAGE_UP, PAGE_DOWN, CAPS_LOCK,

    // --- appended: numeric keypad ---
    NUMPAD_0, NUMPAD_1, NUMPAD_2, NUMPAD_3, NUMPAD_4,
    NUMPAD_5, NUMPAD_6, NUMPAD_7, NUMPAD_8, NUMPAD_9,
    NUMPAD_ADD, NUMPAD_SUBTRACT, NUMPAD_MULTIPLY, NUMPAD_DIVIDE,
    NUMPAD_DECIMAL, NUMPAD_ENTER,

    // --- appended: punctuation (US layout physical positions) ---
    MINUS, EQUAL, LEFT_BRACKET, RIGHT_BRACKET, BACKSLASH,
    SEMICOLON, APOSTROPHE, COMMA, PERIOD, SLASH, GRAVE_ACCENT
}
