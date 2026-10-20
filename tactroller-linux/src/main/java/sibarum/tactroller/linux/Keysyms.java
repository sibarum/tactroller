package sibarum.tactroller.linux;

/**
 * X11 keysyms (keysymdef.h) as Unicode code points, for the typed-character channel.
 *
 * <p>Covers what layouts actually produce through {@code XLookupString}: Latin-1, whose keysyms are their code
 * points; the {@code 0x01000000 + U} range every keysym defined after Unicode uses; and the keypad, whose
 * printable keys have keysyms of their own. The legacy non-Latin blocks (pre-Unicode Greek, Cyrillic and so on)
 * are not mapped yet; current xkeyboard-config layouts emit the Unicode form for most of them.
 */
final class Keysyms {

    private Keysyms() {
    }

    /** The code point {@code keysym} types, or -1 when it types nothing. */
    static int toCodePoint(long keysym) {
        if ((keysym >= 0x20 && keysym <= 0x7E) || (keysym >= 0xA0 && keysym <= 0xFF)) {
            return (int) keysym;
        }
        if (keysym >= 0x01000100L && keysym <= 0x0110FFFFL) {
            return (int) (keysym - 0x01000000L);
        }
        if (keysym >= 0xFFB0 && keysym <= 0xFFB9) {          // KP_0 .. KP_9
            return '0' + (int) (keysym - 0xFFB0);
        }
        if (keysym >= 0xFFAA && keysym <= 0xFFAF) {          // KP_Multiply, Add, Separator, Subtract, Decimal, Divide
            return "*+,-./".charAt((int) (keysym - 0xFFAA));
        }
        if (keysym == 0xFF80) {                              // KP_Space
            return ' ';
        }
        if (keysym == 0xFFBD) {                              // KP_Equal
            return '=';
        }
        if (keysym == 0x20AC) {                              // EuroSign
            return 0x20AC;
        }
        return -1;
    }
}
