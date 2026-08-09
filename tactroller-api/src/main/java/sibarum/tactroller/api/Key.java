package sibarum.tactroller.api;

/**
 * Platform-neutral identifiers for the keys Tactroller can report on.
 *
 * <p>These are logical keys, not scan codes: each platform binding is responsible for mapping
 * its OS-specific virtual-key or key-symbol space onto these constants. The set is intentionally
 * small for the initial cut and can grow without breaking the wire contract, since consumers
 * switch on the enum rather than on raw integers.
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
    LEFT_SUPER, RIGHT_SUPER
}
