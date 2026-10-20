package sibarum.tactroller.clipboard.linux;

import sibarum.tactroller.clipboard.ClipboardBackend;
import sibarum.tactroller.clipboard.ClipboardException;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The Linux clipboard: the X11 {@code CLIPBOARD} selection, through {@code libX11} (XWayland under a Wayland session,
 * whose compositor bridges it to Wayland applications).
 *
 * <p><b>An X11 clipboard is a conversation, not a buffer.</b> Copying does not put text anywhere: it makes this
 * client the selection's <em>owner</em>, and every paste elsewhere is a request this client must answer, for as long
 * as it owns the selection. Pasting is the same conversation the other way: ask the owner to convert the selection
 * onto a property of one of our windows, and wait for it to say it has. So this backend keeps a connection, an
 * unmapped window and a pump thread of its own; every Xlib call is made on that thread, and callers hand it tasks and
 * wait on futures. The text goes when the process does, as it does for every X11 client without a clipboard
 * manager.
 *
 * <p>Text is offered as {@code UTF8_STRING} (and {@code TEXT}), with Latin-1 {@code STRING} for old clients; it is
 * asked for as {@code UTF8_STRING}, then {@code STRING}. Large selections arrive {@code INCR}ementally and are put
 * back together here.
 */
public final class X11ClipboardBackend implements ClipboardBackend {

    /** How long a paste waits for the owner to answer before reporting an empty clipboard. */
    private static final long PASTE_TIMEOUT_MILLIS = 1000;

    // Event types (X.h).
    private static final int PropertyNotify = 28;
    private static final int SelectionClear = 29;
    private static final int SelectionRequest = 30;
    private static final int SelectionNotify = 31;

    private static final long PropertyChangeMask = 1L << 22;
    private static final int PropertyNewValue = 0;
    private static final int PropModeReplace = 0;
    private static final long None = 0L;
    private static final long CurrentTime = 0L;
    private static final long AnyPropertyType = 0L;
    private static final long XA_ATOM = 4L;
    private static final long XA_STRING = 31L;
    private static final long XEVENT_SIZE = 24 * 8;

    // ---- Struct layouts ---------------------------------------------------------------------------------------------

    private static final GroupLayout XAnyEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window"));

    private static final GroupLayout XSelectionRequestEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("owner"),
            JAVA_LONG.withName("requestor"),
            JAVA_LONG.withName("selection"),
            JAVA_LONG.withName("target"),
            JAVA_LONG.withName("property"),
            JAVA_LONG.withName("time"));

    private static final GroupLayout XSelectionEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("requestor"),
            JAVA_LONG.withName("selection"),
            JAVA_LONG.withName("target"),
            JAVA_LONG.withName("property"),
            JAVA_LONG.withName("time"));

    private static final GroupLayout XPropertyEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window"),
            JAVA_LONG.withName("atom"),
            JAVA_LONG.withName("time"),
            JAVA_INT.withName("state"), MemoryLayout.paddingLayout(4));

    private static final GroupLayout POLLFD = MemoryLayout.structLayout(
            JAVA_INT.withName("fd"),
            JAVA_SHORT.withName("events"),
            JAVA_SHORT.withName("revents"));

    private static long off(GroupLayout layout, String field) {
        return layout.byteOffset(groupElement(field));
    }

    // ---- Native functions -------------------------------------------------------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();

    private MethodHandle xOpenDisplay;
    private MethodHandle xCloseDisplay;
    private MethodHandle xDefaultRootWindow;
    private MethodHandle xCreateSimpleWindow;
    private MethodHandle xDestroyWindow;
    private MethodHandle xSelectInput;
    private MethodHandle xInternAtom;
    private MethodHandle xSetSelectionOwner;
    private MethodHandle xGetSelectionOwner;
    private MethodHandle xConvertSelection;
    private MethodHandle xChangeProperty;
    private MethodHandle xGetWindowProperty;
    private MethodHandle xSendEvent;
    private MethodHandle xPending;
    private MethodHandle xNextEvent;
    private MethodHandle xFlush;
    private MethodHandle xFree;
    private MethodHandle xConnectionNumber;
    private MethodHandle poll;
    private MethodHandle pipe2;
    private MethodHandle read;
    private MethodHandle write;
    private MethodHandle closeFd;

    // ---- State ------------------------------------------------------------------------------------------------------

    private Arena arena;
    private MemorySegment display;
    private long window;
    private long clipboardAtom;
    private long utf8Atom;
    private long textAtom;
    private long targetsAtom;
    private long incrAtom;
    private long propertyAtom;
    private int[] wakePipe;
    private Thread thread;
    private volatile boolean stopping;

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    /** What this client is offering while it owns the selection; pump thread only. Null when it owns nothing. */
    private String owned;

    /** The paste in progress; pump thread only. */
    private CompletableFuture<Optional<String>> pending;
    private long pendingTarget;
    private ByteArrayOutputStream incr;

    @Override
    public String name() {
        return "linux-x11";
    }

    @Override
    @SuppressWarnings("restricted")
    public void initialize() throws ClipboardException {
        try {
            arena = Arena.ofShared();
            SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena);
            SymbolLookup libc = SymbolLookup.libraryLookup("libc.so.6", arena);
            xOpenDisplay = dc(x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
            xCloseDisplay = dc(x11, "XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            xDefaultRootWindow = dc(x11, "XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            xCreateSimpleWindow = dc(x11, "XCreateSimpleWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));
            xDestroyWindow = dc(x11, "XDestroyWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            xSelectInput = dc(x11, "XSelectInput", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
            xInternAtom = dc(x11, "XInternAtom", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
            xSetSelectionOwner = dc(x11, "XSetSelectionOwner",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            xGetSelectionOwner = dc(x11, "XGetSelectionOwner", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
            xConvertSelection = dc(x11, "XConvertSelection", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                    JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            xChangeProperty = dc(x11, "XChangeProperty", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                    JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            xGetWindowProperty = dc(x11, "XGetWindowProperty", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                    JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                    ADDRESS));
            xSendEvent = dc(x11, "XSendEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT,
                    JAVA_LONG, ADDRESS));
            xPending = dc(x11, "XPending", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            xNextEvent = dc(x11, "XNextEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            xFlush = dc(x11, "XFlush", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            xFree = dc(x11, "XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            xConnectionNumber = dc(x11, "XConnectionNumber", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            poll = dc(libc, "poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
            pipe2 = dc(libc, "pipe2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            read = dc(libc, "read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
            write = dc(libc, "write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
            closeFd = dc(libc, "close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

            display = (MemorySegment) xOpenDisplay.invokeExact(MemorySegment.NULL);
            if (display.equals(MemorySegment.NULL)) {
                throw new ClipboardException("XOpenDisplay failed: no X display (is $DISPLAY set?)");
            }
            long root = (long) xDefaultRootWindow.invokeExact(display);
            // Never mapped: a window only so there is something to own the selection and receive the answers.
            window = (long) xCreateSimpleWindow.invokeExact(display, root, 0, 0, 1, 1, 0, 0L, 0L);
            int ignored = (int) xSelectInput.invokeExact(display, window, PropertyChangeMask);
            clipboardAtom = atom("CLIPBOARD");
            utf8Atom = atom("UTF8_STRING");
            textAtom = atom("TEXT");
            targetsAtom = atom("TARGETS");
            incrAtom = atom("INCR");
            propertyAtom = atom("TACTROLLER_CLIPBOARD");
            int ignored2 = (int) xFlush.invokeExact(display);
            wakePipe = pipe();
        } catch (ClipboardException e) {
            throw e;
        } catch (Throwable t) {
            throw new ClipboardException("Failed to bind/initialise the X11 clipboard: " + t);
        }
        thread = new Thread(this::pump, "tactroller-x11-clipboard");
        thread.setDaemon(true);
        thread.start();
    }

    private MethodHandle dc(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lib.findOrThrow(name), descriptor);
    }

    private long atom(String name) throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            return (long) xInternAtom.invokeExact(display, temp.allocateFrom(name), 0);
        }
    }

    private int[] pipe() throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment fds = temp.allocate(JAVA_INT, 2);
            int rc = (int) pipe2.invokeExact(fds, 0x800 | 0x80000);   // O_NONBLOCK | O_CLOEXEC
            if (rc != 0) {
                throw new ClipboardException("pipe2 failed");
            }
            return new int[]{fds.getAtIndex(JAVA_INT, 0), fds.getAtIndex(JAVA_INT, 1)};
        }
    }

    // ---- The API, from any thread -----------------------------------------------------------------------------------

    @Override
    public Optional<String> getText() throws ClipboardException {
        CompletableFuture<Optional<String>> answer = new CompletableFuture<>();
        submit(() -> startPaste(answer));
        try {
            return answer.get(PASTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // An owner that never answers is an empty clipboard, not an error worth a dialog.
            submit(() -> {
                if (pending == answer) {
                    pending = null;
                    incr = null;
                }
            });
            return Optional.empty();
        } catch (Exception e) {
            throw new ClipboardException("Reading the X11 clipboard failed: " + e);
        }
    }

    @Override
    public void setText(String text) throws ClipboardException {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        submit(() -> {
            try {
                owned = text;
                int ignored = (int) xSetSelectionOwner.invokeExact(display, clipboardAtom, window, CurrentTime);
                boolean ours = (long) xGetSelectionOwner.invokeExact(display, clipboardAtom) == window;
                if (!ours) {
                    owned = null;
                }
                done.complete(ours);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            if (!done.get(PASTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new ClipboardException("The X server did not give this client the clipboard");
            }
        } catch (ClipboardException e) {
            throw e;
        } catch (Exception e) {
            throw new ClipboardException("Writing the X11 clipboard failed: " + e);
        }
    }

    private void submit(Runnable task) throws ClipboardException {
        if (stopping) {
            throw new ClipboardException("the clipboard is closed");
        }
        tasks.add(task);
        try (Arena temp = Arena.ofConfined()) {
            long ignored = (long) write.invokeExact(wakePipe[1], temp.allocate(1), 1L);
        } catch (Throwable t) {
            throw new ClipboardException("could not wake the clipboard thread: " + t);
        }
    }

    // ---- The pump thread ---------------------------------------------------------------------------------------------

    private void pump() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment event = temp.allocate(XEVENT_SIZE, 8);
            MemorySegment fds = temp.allocate(POLLFD, 2);
            MemorySegment drain = temp.allocate(64);
            fds.set(JAVA_INT, off(POLLFD, "fd"), (int) xConnectionNumber.invokeExact(display));
            fds.set(JAVA_SHORT, off(POLLFD, "events"), (short) 1);
            fds.set(JAVA_INT, POLLFD.byteSize() + off(POLLFD, "fd"), wakePipe[0]);
            fds.set(JAVA_SHORT, POLLFD.byteSize() + off(POLLFD, "events"), (short) 1);
            while (!stopping) {
                for (Runnable task; (task = tasks.poll()) != null; ) {
                    task.run();
                }
                while ((int) xPending.invokeExact(display) > 0) {
                    int ignored = (int) xNextEvent.invokeExact(display, event);
                    handle(event);
                }
                int ignored = (int) xFlush.invokeExact(display);
                int ready = (int) poll.invokeExact(fds, 2L, -1);
                while ((long) read.invokeExact(wakePipe[0], drain, 64L) > 0) {
                    // drain the wakes
                }
            }
        } catch (Throwable t) {
            if (!stopping) {
                System.err.println("tactroller: X11 clipboard stopped: " + t);
            }
        }
    }

    private void handle(MemorySegment e) throws Throwable {
        int type = e.get(JAVA_INT, 0);
        if (type == SelectionRequest) {
            answer(e);
        } else if (type == SelectionClear) {
            owned = null;   // someone else copied
        } else if (type == SelectionNotify) {
            received(e);
        } else if (type == PropertyNotify && incr != null
                && e.get(JAVA_LONG, off(XPropertyEvent, "atom")) == propertyAtom
                && e.get(JAVA_INT, off(XPropertyEvent, "state")) == PropertyNewValue) {
            incrementalChunk();
        }
    }

    /** Ask the owner for the selection, or answer at once when the owner is us. */
    private void startPaste(CompletableFuture<Optional<String>> answer) {
        if (owned != null) {
            answer.complete(Optional.of(owned));
            return;
        }
        if (pending != null) {
            pending.complete(Optional.empty());   // superseded
        }
        pending = answer;
        incr = null;
        convert(utf8Atom);
    }

    private void convert(long target) {
        pendingTarget = target;
        try {
            int ignored = (int) xConvertSelection.invokeExact(display, clipboardAtom, target, propertyAtom, window,
                    CurrentTime);
        } catch (Throwable t) {
            finish(Optional.empty());
        }
    }

    /** The owner has answered (or refused): read what it put on our property. */
    private void received(MemorySegment e) throws Throwable {
        if (pending == null) {
            return;
        }
        if (e.get(JAVA_LONG, off(XSelectionEvent, "property")) == None) {
            if (pendingTarget == utf8Atom) {
                convert(XA_STRING);   // an old owner that only speaks Latin-1
            } else {
                finish(Optional.empty());
            }
            return;
        }
        Property p = readProperty(true);
        if (p == null) {
            finish(Optional.empty());
        } else if (p.type == incrAtom) {
            // Too big for one property: the owner now sends it in chunks, each announced by a PropertyNotify once
            // we have deleted the last one (which readProperty just did).
            incr = new ByteArrayOutputStream();
        } else {
            finish(Optional.of(decode(p)));
        }
    }

    private void incrementalChunk() throws Throwable {
        Property p = readProperty(true);
        if (p == null || p.bytes.length == 0) {
            byte[] all = incr.toByteArray();
            incr = null;
            finish(Optional.of(decode(new Property(pendingTarget, all))));
        } else {
            incr.writeBytes(p.bytes);
        }
    }

    private String decode(Property p) {
        return new String(p.bytes, p.type == XA_STRING ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private void finish(Optional<String> text) {
        CompletableFuture<Optional<String>> answer = pending;
        pending = null;
        if (answer != null) {
            answer.complete(text);
        }
    }

    private record Property(long type, byte[] bytes) {
    }

    /** Our property, as bytes, deleting it when asked; null when it is absent. */
    private Property readProperty(boolean delete) throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment actualType = temp.allocate(JAVA_LONG);
            MemorySegment actualFormat = temp.allocate(JAVA_INT);
            MemorySegment nItems = temp.allocate(JAVA_LONG);
            MemorySegment bytesAfter = temp.allocate(JAVA_LONG);
            MemorySegment data = temp.allocate(ADDRESS);
            // The length is in 32-bit units; ask for all of it in one go.
            int status = (int) xGetWindowProperty.invokeExact(display, window, propertyAtom, 0L, 0x1FFFFFFFL,
                    delete ? 1 : 0, AnyPropertyType, actualType, actualFormat, nItems, bytesAfter, data);
            MemorySegment values = data.get(ADDRESS, 0);
            if (status != 0 || values.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                long type = actualType.get(JAVA_LONG, 0);
                int format = actualFormat.get(JAVA_INT, 0);
                long count = nItems.get(JAVA_LONG, 0);
                // Format 32 is C longs (8 bytes here); INCR's payload is one, and its size does not matter.
                long unit = format == 32 ? 8 : format / 8;
                byte[] bytes = values.reinterpret(Math.max(0, count * unit)).toArray(JAVA_BYTE);
                return new Property(type, bytes);
            } finally {
                int ignored = (int) xFree.invokeExact(values);
            }
        }
    }

    /** Another client wants our selection: put it on the property it named, and say so. */
    private void answer(MemorySegment request) throws Throwable {
        long requestor = request.get(JAVA_LONG, off(XSelectionRequestEvent, "requestor"));
        long selection = request.get(JAVA_LONG, off(XSelectionRequestEvent, "selection"));
        long target = request.get(JAVA_LONG, off(XSelectionRequestEvent, "target"));
        long property = request.get(JAVA_LONG, off(XSelectionRequestEvent, "property"));
        long time = request.get(JAVA_LONG, off(XSelectionRequestEvent, "time"));
        if (property == None) {
            property = target;   // an obsolete client (ICCCM 2.2)
        }
        boolean served = false;
        if (owned != null && selection == clipboardAtom) {
            try (Arena temp = Arena.ofConfined()) {
                if (target == targetsAtom) {
                    MemorySegment atoms = temp.allocateFrom(JAVA_LONG, targetsAtom, utf8Atom, textAtom, XA_STRING);
                    int ignored = (int) xChangeProperty.invokeExact(display, requestor, property, XA_ATOM, 32,
                            PropModeReplace, atoms, 4);
                    served = true;
                } else if (target == utf8Atom || target == textAtom || target == XA_STRING) {
                    byte[] bytes = owned.getBytes(target == XA_STRING
                            ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
                    MemorySegment data = temp.allocateFrom(JAVA_BYTE, bytes);
                    int ignored = (int) xChangeProperty.invokeExact(display, requestor, property,
                            target == XA_STRING ? XA_STRING : utf8Atom, 8, PropModeReplace, data, bytes.length);
                    served = true;
                }
            }
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment notify = temp.allocate(XEVENT_SIZE, 8);
            notify.set(JAVA_INT, off(XSelectionEvent, "type"), SelectionNotify);
            notify.set(JAVA_INT, off(XSelectionEvent, "send_event"), 1);
            notify.set(ADDRESS, off(XSelectionEvent, "display"), display);
            notify.set(JAVA_LONG, off(XSelectionEvent, "requestor"), requestor);
            notify.set(JAVA_LONG, off(XSelectionEvent, "selection"), selection);
            notify.set(JAVA_LONG, off(XSelectionEvent, "target"), target);
            notify.set(JAVA_LONG, off(XSelectionEvent, "property"), served ? property : None);
            notify.set(JAVA_LONG, off(XSelectionEvent, "time"), time);
            int ignored = (int) xSendEvent.invokeExact(display, requestor, 0, 0L, notify);
        }
    }

    @Override
    public void close() {
        if (stopping || thread == null) {
            return;
        }
        stopping = true;
        try (Arena temp = Arena.ofConfined()) {
            long ignored = (long) write.invokeExact(wakePipe[1], temp.allocate(1), 1L);
            thread.join(1000);
            int a = (int) xDestroyWindow.invokeExact(display, window);
            int b = (int) xCloseDisplay.invokeExact(display);
            int c = (int) closeFd.invokeExact(wakePipe[0]);
            int d = (int) closeFd.invokeExact(wakePipe[1]);
        } catch (Throwable ignored) {
            // best effort
        } finally {
            arena.close();
        }
    }
}
