package sibarum.tactroller.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * What libxkbcommon adds to {@code XLookupString}: every keysym as the character it types, and dead keys and compose
 * sequences, as the person's locale defines them.
 *
 * <p><b>Keysyms.</b> {@code xkb_keysym_to_utf32} knows every keysym, the legacy pre-Unicode blocks too, which layouts
 * such as Russian and Greek still emit. {@link Keysyms} is what is left where there is no libxkbcommon.
 *
 * <p><b>Compose.</b> A dead key types nothing by itself: it starts a sequence, fed keysym by keysym to the locale's
 * Compose table ({@code xkb_compose_*}, the same table every toolkit reads). A finished sequence types its result; one
 * the table has no entry for is dropped and the key that ended it types as itself, so no keystroke is lost.
 * Modifier keys pass through a sequence without touching it.
 *
 * <p>Optional: without libxkbcommon (a minimal system) or a Compose table for the locale, characters still type,
 * without dead keys. One per event connection, used from its pump thread only.
 */
final class Xkb implements AutoCloseable {

    // xkb_compose_feed_result and xkb_compose_status (xkbcommon-compose.h).
    private static final int XKB_COMPOSE_FEED_ACCEPTED = 1;
    private static final int XKB_COMPOSE_NOTHING = 0;
    private static final int XKB_COMPOSE_COMPOSING = 1;
    private static final int XKB_COMPOSE_COMPOSED = 2;
    private static final int XKB_COMPOSE_CANCELLED = 3;

    private static final Natives NATIVES = Natives.load();

    private final MemorySegment context;
    private final MemorySegment table;
    private final MemorySegment state;

    private Xkb(MemorySegment context, MemorySegment table, MemorySegment state) {
        this.context = context;
        this.table = table;
        this.state = state;
    }

    /** For the locale the process runs in; without compose where that cannot be had. */
    static Xkb open() {
        if (NATIVES == null) {
            return new Xkb(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        }
        MemorySegment context = MemorySegment.NULL;
        MemorySegment table = MemorySegment.NULL;
        MemorySegment state = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            context = (MemorySegment) NATIVES.contextNew.invokeExact(0);
            if (!context.equals(MemorySegment.NULL)) {
                table = (MemorySegment) NATIVES.composeTableNewFromLocale.invokeExact(context,
                        arena.allocateFrom(locale()), 0);
            }
            if (!table.equals(MemorySegment.NULL)) {
                state = (MemorySegment) NATIVES.composeStateNew.invokeExact(table, 0);
            }
        } catch (Throwable t) {
            System.err.println("tactroller: no dead keys (" + t + ")");
        }
        return new Xkb(context, table, state);
    }

    /** The locale the Compose table is chosen by, as libxkbcommon's own examples read it. */
    private static String locale() {
        for (String name : new String[]{"LC_ALL", "LC_CTYPE", "LANG"}) {
            String value = System.getenv(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "C";
    }

    /**
     * The characters a press of {@code keysym} types, as code points: none while a sequence is under way, or for a
     * key that types nothing; the result when one finishes.
     */
    int[] press(long keysym) {
        if (state.equals(MemorySegment.NULL)) {
            return single(keysym);
        }
        try {
            int fed = (int) NATIVES.composeStateFeed.invokeExact(state, (int) keysym);
            if (fed != XKB_COMPOSE_FEED_ACCEPTED) {
                return new int[0];   // a modifier: sequences go on through it
            }
            int status = (int) NATIVES.composeStateGetStatus.invokeExact(state);
            switch (status) {
                case XKB_COMPOSE_COMPOSING:
                    return new int[0];
                case XKB_COMPOSE_COMPOSED: {
                    int sym = (int) NATIVES.composeStateGetOneSym.invokeExact(state);
                    int[] out = composed(sym);
                    NATIVES.composeStateReset.invokeExact(state);
                    return out;
                }
                case XKB_COMPOSE_CANCELLED:
                    NATIVES.composeStateReset.invokeExact(state);
                    return single(keysym);
                case XKB_COMPOSE_NOTHING:
                default:
                    return single(keysym);
            }
        } catch (Throwable t) {
            return single(keysym);
        }
    }

    /** Forget a sequence under way: the keyboard went to another window. */
    void reset() {
        if (!state.equals(MemorySegment.NULL)) {
            try {
                NATIVES.composeStateReset.invokeExact(state);
            } catch (Throwable ignored) {
                // nothing under way to lose
            }
        }
    }

    /** A finished sequence: its one keysym's character, or, for a result that is a string, the string. */
    private int[] composed(int sym) throws Throwable {
        if (sym != 0) {
            return single(Integer.toUnsignedLong(sym));
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(64);
            int n = (int) NATIVES.composeStateGetUtf8.invokeExact(state, buf, 64L);
            return n <= 0 ? new int[0] : buf.getString(0).codePoints().filter(Xkb::types).toArray();
        }
    }

    private static int[] single(long keysym) {
        int cp = toCodePoint(keysym);
        return types(cp) ? new int[]{cp} : new int[0];
    }

    /** Whether {@code cp} is typed text: control characters ride the key channel, as on every backend. */
    private static boolean types(int cp) {
        return cp >= 0x20 && cp != 0x7F && !(cp >= 0x80 && cp < 0xA0);
    }

    /** The code point {@code keysym} types, or -1: libxkbcommon's answer where it is here, {@link Keysyms}' if not. */
    static int toCodePoint(long keysym) {
        if (NATIVES != null) {
            try {
                int cp = (int) NATIVES.keysymToUtf32.invokeExact((int) keysym);
                return cp == 0 ? -1 : cp;
            } catch (Throwable ignored) {
                // fall through to the table
            }
        }
        return Keysyms.toCodePoint(keysym);
    }

    @Override
    public void close() {
        if (NATIVES == null) {
            return;
        }
        try {
            if (!state.equals(MemorySegment.NULL)) {
                NATIVES.composeStateUnref.invokeExact(state);
            }
            if (!table.equals(MemorySegment.NULL)) {
                NATIVES.composeTableUnref.invokeExact(table);
            }
            if (!context.equals(MemorySegment.NULL)) {
                NATIVES.contextUnref.invokeExact(context);
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** libxkbcommon's entry points, or null where the library is not here. */
    private record Natives(MethodHandle contextNew, MethodHandle contextUnref, MethodHandle composeTableNewFromLocale,
                           MethodHandle composeTableUnref, MethodHandle composeStateNew, MethodHandle composeStateUnref,
                           MethodHandle composeStateFeed, MethodHandle composeStateReset,
                           MethodHandle composeStateGetStatus, MethodHandle composeStateGetOneSym,
                           MethodHandle composeStateGetUtf8, MethodHandle keysymToUtf32) {

        static Natives load() {
            try {
                SymbolLookup lib = SymbolLookup.libraryLookup("libxkbcommon.so.0", Arena.global());
                Linker linker = Linker.nativeLinker();
                return new Natives(
                        bind(linker, lib, "xkb_context_new", FunctionDescriptor.of(ADDRESS, JAVA_INT)),
                        bind(linker, lib, "xkb_context_unref", FunctionDescriptor.ofVoid(ADDRESS)),
                        bind(linker, lib, "xkb_compose_table_new_from_locale",
                                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)),
                        bind(linker, lib, "xkb_compose_table_unref", FunctionDescriptor.ofVoid(ADDRESS)),
                        bind(linker, lib, "xkb_compose_state_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)),
                        bind(linker, lib, "xkb_compose_state_unref", FunctionDescriptor.ofVoid(ADDRESS)),
                        bind(linker, lib, "xkb_compose_state_feed", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                        bind(linker, lib, "xkb_compose_state_reset", FunctionDescriptor.ofVoid(ADDRESS)),
                        bind(linker, lib, "xkb_compose_state_get_status", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        bind(linker, lib, "xkb_compose_state_get_one_sym", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        bind(linker, lib, "xkb_compose_state_get_utf8",
                                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG)),
                        bind(linker, lib, "xkb_keysym_to_utf32", FunctionDescriptor.of(JAVA_INT, JAVA_INT)));
            } catch (RuntimeException | LinkageError e) {
                System.err.println("tactroller: libxkbcommon not loaded (" + e.getMessage() + "); no dead keys");
                return null;
            }
        }

        @SuppressWarnings("restricted")
        private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor d) {
            return linker.downcallHandle(lib.findOrThrow(name), d);
        }
    }
}
