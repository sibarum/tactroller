package sibarum.tactroller.clipboard.windows;

import sibarum.tactroller.clipboard.ClipboardBackend;
import sibarum.tactroller.clipboard.ClipboardException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Windows clipboard backend via Panama, binding {@code user32.dll} and {@code kernel32.dll}.
 *
 * <p>Text is exchanged as {@code CF_UNICODETEXT} (UTF-16LE) through a moveable global memory block:
 * reads {@code GlobalLock} the handle from {@code GetClipboardData}; writes {@code GlobalAlloc} a
 * block, fill it, and hand ownership to the system via {@code SetClipboardData}.
 */
public final class WindowsClipboardBackend implements ClipboardBackend {

    private static final int CF_UNICODETEXT = 13;
    private static final int GMEM_MOVEABLE = 0x0002;
    private static final int OPEN_RETRIES = 6;

    private Arena arena;
    private MethodHandle openClipboard;
    private MethodHandle closeClipboard;
    private MethodHandle emptyClipboard;
    private MethodHandle getClipboardData;
    private MethodHandle setClipboardData;
    private MethodHandle isClipboardFormatAvailable;
    private MethodHandle globalAlloc;
    private MethodHandle globalLock;
    private MethodHandle globalUnlock;
    private MethodHandle globalSize;

    @Override
    public String name() {
        return "windows-user32";
    }

    @Override
    @SuppressWarnings("restricted")
    public void initialize() throws ClipboardException {
        try {
            arena = Arena.ofShared();
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

            openClipboard = dc(linker, user32, "OpenClipboard", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            closeClipboard = dc(linker, user32, "CloseClipboard", FunctionDescriptor.of(JAVA_INT));
            emptyClipboard = dc(linker, user32, "EmptyClipboard", FunctionDescriptor.of(JAVA_INT));
            getClipboardData = dc(linker, user32, "GetClipboardData", FunctionDescriptor.of(ADDRESS, JAVA_INT));
            setClipboardData = dc(linker, user32, "SetClipboardData", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
            isClipboardFormatAvailable = dc(linker, user32, "IsClipboardFormatAvailable", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            globalAlloc = dc(linker, kernel32, "GlobalAlloc", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_LONG));
            globalLock = dc(linker, kernel32, "GlobalLock", FunctionDescriptor.of(ADDRESS, ADDRESS));
            globalUnlock = dc(linker, kernel32, "GlobalUnlock", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            globalSize = dc(linker, kernel32, "GlobalSize", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        } catch (RuntimeException e) {
            throw new ClipboardException("Failed to bind Windows clipboard libraries", e);
        }
    }

    @Override
    public boolean hasText() throws ClipboardException {
        try {
            return (int) isClipboardFormatAvailable.invokeExact(CF_UNICODETEXT) != 0;
        } catch (Throwable t) {
            throw new ClipboardException("IsClipboardFormatAvailable failed", t);
        }
    }

    @Override
    @SuppressWarnings("restricted")
    public Optional<String> getText() throws ClipboardException {
        if (!hasText()) {
            return Optional.empty();
        }
        open();
        try {
            MemorySegment handle = (MemorySegment) getClipboardData.invokeExact(CF_UNICODETEXT);
            if (handle.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            MemorySegment ptr = (MemorySegment) globalLock.invokeExact(handle);
            if (ptr.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            try {
                long bytes = (long) globalSize.invokeExact(handle);
                MemorySegment data = MemorySegment.ofAddress(ptr.address()).reinterpret(bytes);
                long maxChars = bytes / 2;
                StringBuilder sb = new StringBuilder();
                for (long i = 0; i < maxChars; i++) {
                    short c = data.get(JAVA_SHORT, i * 2);
                    if (c == 0) {
                        break;
                    }
                    sb.append((char) c);
                }
                return Optional.of(sb.toString());
            } finally {
                int ignored = (int) globalUnlock.invokeExact(handle);
            }
        } catch (Throwable t) {
            throw new ClipboardException("Failed to read clipboard text", t);
        } finally {
            closeQuietly();
        }
    }

    @Override
    @SuppressWarnings("restricted")
    public void setText(String text) throws ClipboardException {
        open();
        try {
            int emptied = (int) emptyClipboard.invokeExact();
            if (emptied == 0) {
                throw new ClipboardException("EmptyClipboard failed");
            }
            long bytes = (text.length() + 1L) * 2L;
            MemorySegment hMem = (MemorySegment) globalAlloc.invokeExact(GMEM_MOVEABLE, bytes);
            if (hMem.equals(MemorySegment.NULL)) {
                throw new ClipboardException("GlobalAlloc failed");
            }
            MemorySegment ptr = (MemorySegment) globalLock.invokeExact(hMem);
            if (ptr.equals(MemorySegment.NULL)) {
                throw new ClipboardException("GlobalLock failed");
            }
            MemorySegment dst = MemorySegment.ofAddress(ptr.address()).reinterpret(bytes);
            for (int i = 0; i < text.length(); i++) {
                dst.set(JAVA_SHORT, i * 2L, (short) text.charAt(i));
            }
            dst.set(JAVA_SHORT, text.length() * 2L, (short) 0);
            int ignored = (int) globalUnlock.invokeExact(hMem);

            // On success the system owns hMem; do not free it.
            MemorySegment result = (MemorySegment) setClipboardData.invokeExact(CF_UNICODETEXT, hMem);
            if (result.equals(MemorySegment.NULL)) {
                throw new ClipboardException("SetClipboardData failed");
            }
        } catch (ClipboardException e) {
            throw e;
        } catch (Throwable t) {
            throw new ClipboardException("Failed to write clipboard text", t);
        } finally {
            closeQuietly();
        }
    }

    /** Open the clipboard, retrying briefly since another process may hold it transiently. */
    private void open() throws ClipboardException {
        Throwable last = null;
        for (int i = 0; i < OPEN_RETRIES; i++) {
            try {
                if ((int) openClipboard.invokeExact(MemorySegment.NULL) != 0) {
                    return;
                }
            } catch (Throwable t) {
                last = t;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ClipboardException("OpenClipboard failed (clipboard busy)", last);
    }

    private void closeQuietly() {
        try {
            int ignored = (int) closeClipboard.invokeExact();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    @Override
    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle dc(Linker linker, SymbolLookup lib, String symbol, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.findOrThrow(symbol), fd);
    }
}
