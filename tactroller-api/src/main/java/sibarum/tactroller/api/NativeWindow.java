package sibarum.tactroller.api;

/**
 * A handle to a host-created native window, handed to a backend so it can gate input on focus and
 * report client-area-relative coordinates. Tactroller does not create or own the window; the
 * embedding application (its GLFW/SDL/Win32 windowing layer) does, and passes the handle here.
 *
 * @param handle the raw OS handle: an {@code HWND} on Windows, an X11 {@code Window} XID on Linux,
 *               or an {@code NSView}/{@code NSWindow} pointer on macOS
 * @param kind   which kind of handle {@code handle} is
 */
public record NativeWindow(long handle, Kind kind) {

    public enum Kind {
        /** Win32 {@code HWND}. */
        HWND,
        /** X11 {@code Window} XID. */
        X11_WINDOW,
        /** Cocoa {@code NSView*}. */
        NS_VIEW,
        /** Cocoa {@code NSWindow*}. */
        NS_WINDOW
    }

    public NativeWindow {
        if (handle == 0L) {
            throw new IllegalArgumentException("native window handle must be non-null");
        }
    }

    public static NativeWindow ofHwnd(long hwnd) {
        return new NativeWindow(hwnd, Kind.HWND);
    }

    public static NativeWindow ofX11Window(long window) {
        return new NativeWindow(window, Kind.X11_WINDOW);
    }

    public static NativeWindow ofNSView(long nsView) {
        return new NativeWindow(nsView, Kind.NS_VIEW);
    }

    public static NativeWindow ofNSWindow(long nsWindow) {
        return new NativeWindow(nsWindow, Kind.NS_WINDOW);
    }
}
