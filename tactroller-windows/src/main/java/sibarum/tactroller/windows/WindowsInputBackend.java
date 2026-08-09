package sibarum.tactroller.windows;

import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.InputBackend;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.PointerState;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Windows {@link InputBackend} backed by {@code user32.dll} via the Panama Foreign Function &amp;
 * Memory API.
 *
 * <ul>
 *   <li>{@code BOOL GetCursorPos(LPPOINT)} — pointer position (a {@code POINT} is two 32-bit LONGs).</li>
 *   <li>{@code SHORT GetAsyncKeyState(int vKey)} — key/button state; the {@code 0x8000} bit is "down".</li>
 * </ul>
 *
 * user32 is a core system DLL present on every Windows install, so nothing needs to be bundled;
 * the library is resolved by name off the loader path.
 */
public final class WindowsInputBackend implements InputBackend {

    /** POINT { LONG x; LONG y; } */
    private static final MemoryLayout POINT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("x"),
            ValueLayout.JAVA_INT.withName("y"));

    private static final int DOWN_MASK = 0x8000;

    // Virtual-key codes (winuser.h).
    private static final int VK_LBUTTON = 0x01;
    private static final int VK_RBUTTON = 0x02;
    private static final int VK_MBUTTON = 0x04;

    private final Map<Key, Integer> vkByKey = buildKeyMap();

    private Arena arena;
    private MemorySegment pointBuffer;
    private MethodHandle getCursorPos;
    private MethodHandle getAsyncKeyState;

    @Override
    public String name() {
        return "windows-user32";
    }

    @Override
    @SuppressWarnings("restricted")
    public void initialize() throws BackendException {
        try {
            arena = Arena.ofShared();
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);

            getCursorPos = linker.downcallHandle(
                    user32.findOrThrow("GetCursorPos"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            getAsyncKeyState = linker.downcallHandle(
                    user32.findOrThrow("GetAsyncKeyState"),
                    FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));

            pointBuffer = arena.allocate(POINT);
        } catch (RuntimeException e) {
            throw new BackendException("Failed to bind user32.dll", e);
        }
    }

    @Override
    public PointerState pollPointer() throws BackendException {
        try {
            int ok = (int) getCursorPos.invokeExact(pointBuffer);
            if (ok == 0) {
                throw new BackendException("GetCursorPos returned FALSE");
            }
            int x = pointBuffer.get(ValueLayout.JAVA_INT, 0);
            int y = pointBuffer.get(ValueLayout.JAVA_INT, 4);

            Set<MouseButton> buttons = EnumSet.noneOf(MouseButton.class);
            if (down(VK_LBUTTON)) buttons.add(MouseButton.LEFT);
            if (down(VK_RBUTTON)) buttons.add(MouseButton.RIGHT);
            if (down(VK_MBUTTON)) buttons.add(MouseButton.MIDDLE);

            return new PointerState(x, y, buttons);
        } catch (BackendException e) {
            throw e;
        } catch (Throwable t) {
            throw new BackendException("Native pointer poll failed", t);
        }
    }

    @Override
    public boolean isKeyDown(Key key) throws BackendException {
        Integer vk = vkByKey.get(key);
        if (vk == null) {
            throw new BackendException("Key not mapped for Windows: " + key);
        }
        try {
            return down(vk);
        } catch (Throwable t) {
            throw new BackendException("Native key poll failed for " + key, t);
        }
    }

    private boolean down(int vk) throws Throwable {
        short state = (short) getAsyncKeyState.invokeExact(vk);
        return (state & DOWN_MASK) != 0;
    }

    @Override
    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    private static Map<Key, Integer> buildKeyMap() {
        Map<Key, Integer> m = new EnumMap<>(Key.class);
        // Letters and digits share ASCII-uppercase VK codes.
        for (char c = 'A'; c <= 'Z'; c++) {
            m.put(Key.valueOf(String.valueOf(c)), (int) c);
        }
        for (int d = 0; d <= 9; d++) {
            m.put(Key.valueOf("DIGIT_" + d), (int) ('0' + d));
        }
        m.put(Key.SPACE, 0x20);
        m.put(Key.ENTER, 0x0D);
        m.put(Key.ESCAPE, 0x1B);
        m.put(Key.TAB, 0x09);
        m.put(Key.BACKSPACE, 0x08);
        m.put(Key.DELETE, 0x2E);
        m.put(Key.LEFT, 0x25);
        m.put(Key.UP, 0x26);
        m.put(Key.RIGHT, 0x27);
        m.put(Key.DOWN, 0x28);
        m.put(Key.LEFT_SHIFT, 0xA0);
        m.put(Key.RIGHT_SHIFT, 0xA1);
        m.put(Key.LEFT_CONTROL, 0xA2);
        m.put(Key.RIGHT_CONTROL, 0xA3);
        m.put(Key.LEFT_ALT, 0xA4);
        m.put(Key.RIGHT_ALT, 0xA5);
        m.put(Key.LEFT_SUPER, 0x5B);
        m.put(Key.RIGHT_SUPER, 0x5C);
        return m;
    }
}
