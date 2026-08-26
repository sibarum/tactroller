# Tactroller

Cross-platform input-device middleware API for the JVM, built on the **Panama** Foreign Function &
Memory API (JEP 454) with **platform-specific native bindings**, and designed to compile cleanly
under **GraalVM native-image**.

Tactroller exposes one OS-agnostic API for polling pointer position, mouse buttons and key state.
Each supported OS is a separate Maven module that binds that platform's native input libraries
directly — no third-party runtime dependencies.

## Layout

| Module               | Role                                                                        |
|----------------------|-----------------------------------------------------------------------------|
| `tactroller`         | Parent POM. Selects the platform module for the host OS via profiles.       |
| `tactroller-api`     | OS-agnostic API + `InputBackend` SPI (`ServiceLoader`). No native code.      |
| `tactroller-windows` | Panama bindings to `user32.dll` (`GetCursorPos`, `GetAsyncKeyState`).       |
| `tactroller-macos`   | Panama bindings to CoreGraphics (`CGEventSourceKeyState`, `CGEventGetLocation`). |
| `tactroller-linux`   | Panama bindings to `libX11` (`XQueryPointer`, `XQueryKeymap`).              |
| `tactroller-clipboard` | Standalone system-clipboard text get/set. Independent of the input subsystem; Windows implemented. |

## OS auto-detection

The parent POM always builds `tactroller-api`. The three platform modules are attached through
`<os>`-activated Maven profiles, so a plain build compiles **only the module for the host OS**:

```bash
mvn install
```

On Windows the reactor builds `tactroller-api` + `tactroller-windows`; on macOS `+ tactroller-macos`;
on Linux `+ tactroller-linux`. The matching module ships a `META-INF/services` entry, so at runtime
`ServiceLoader` finds exactly one `InputBackend`.

Because the bindings target OS **system** libraries (always present), there are no binaries to
bundle. If a future backend needs a third-party native library, drop it under
`src/main/resources/natives/<os>-<arch>/` and load it via `SymbolLookup.libraryLookup(Path, arena)`.

## Usage

Two interchangeable styles over the same backend:

**Polling** — read current state on demand (immediate-mode GUIs, per-frame sampling):

```java
import sibarum.tactroller.api.*;

try (Tactroller t = Tactroller.open()) {
    boolean jump = t.isKeyDown(Key.SPACE);
    boolean fire = t.isButtonDown(MouseButton.LEFT);   // allocation-free
}
```

**Per-frame snapshot** — the first-class render-thread path: one immutable `InputFrame` per frame
with held state *and* edges, no background thread, no manual diffing:

```java
try (Tactroller t = Tactroller.open()) {
    while (running) {                       // once per frame, on the render thread
        InputFrame f = t.snapshot();
        if (f.wasPressed(Key.SPACE)) jump();          // edge, not held state
        if (f.isKeyDown(Key.W))      moveForward();
        if (f.wasPressed(MouseButton.LEFT)) fire();
        camera.turn(f.motion().dx(), f.motion().dy()); // relative delta since last frame
        zoom(f.scroll().y());
        if (f.hasModifier(Modifier.CONTROL)) ...
    }
}
```

**Events** — listen for key/mouse transitions (retained-mode GUIs, reactive input):

```java
try (Tactroller t = Tactroller.open()) {
    t.addListener(e -> {
        switch (e) {
            case InputEvent.KeyPressed k     -> System.out.println("down " + k.key());
            case InputEvent.KeyReleased k    -> System.out.println("up " + k.key());
            case InputEvent.PointerMoved m   -> System.out.println("move " + m.x() + "," + m.y());
            case InputEvent.ButtonPressed b  -> System.out.println("click " + b.button());
            case InputEvent.ButtonReleased b -> System.out.println("release " + b.button());
        }
    });
    t.start();          // daemon event loop, default 125 Hz; t.start(hz) to tune
    Thread.sleep(10_000);
}                       // close() stops the loop and releases native resources
```

### Mouselook, scroll, window & focus

```java
try (Tactroller t = Tactroller.open()) {
    t.attach(NativeWindow.ofHwnd(hwnd));          // focus gating + client coords
    t.setCoordinateSpace(CoordinateSpace.CLIENT); // event/poll coords relative to client area

    // Mouselook — two modes, pick per your needs:
    t.lockPointer(PointerLockMode.RAW);           // raw device deltas (no accel, no edge clip)
    // t.lockPointer(PointerLockMode.RECENTER);   // hide + warp-to-center each drain

    while (running) {                              // per frame
        PointerDelta look = t.pollPointerDelta();  // relative motion since last frame
        ScrollDelta wheel = t.pollScroll();        // notches since last frame
    }
    t.unlockPointer();
}
```

| Concern | API | Windows implementation |
|---------|-----|------------------------|
| Relative mouse / pointer-lock | `lockPointer(RAW\|RECENTER)`, `pollPointerDelta()` | RawInput deltas, or hide + `SetCursorPos` recenter |
| Scroll wheel | `pollScroll()`, `InputEvent.Scrolled` | RawInput `WM_INPUT` wheel notches |
| Window-relative coords | `attach()`, `setCoordinateSpace(CLIENT)` | `ScreenToClient` |
| HiDPI / framebuffer coords | `contentScale()`, `setCoordinateSpace(FRAMEBUFFER)` | `GetDpiForWindow` |
| Focus gating | `setFocusGated(true)`, `InputEvent.FocusChanged` | `GetForegroundWindow` |
| Per-frame edges | `snapshot()` → `InputFrame` | shared (diffed in the API layer) |
| Modifiers | `modifiers()`, `InputFrame.hasModifier` | derived from held keys |

**Thread affinity:** the Windows backend runs its own RawInput message-only window on its own pump
thread — it does *not* depend on the host window or its event pump, and the delta/scroll
accumulators are drained via atomic swaps, so filling (pump thread) and draining (render thread) are
safe across threads. For an engine with its own render loop, **poll `snapshot()` per frame and don't
call `start()`**; `start()` is for retained-mode/event-driven consumers.

Scroll and RAW pointer-lock are served by a **message-only window registered for RawInput** on a
dedicated pump thread; its `WndProc` (an FFM upcall) only *accumulates* into atomic counters, which
the shared event loop drains — so events still come from one place (see below). macOS and Linux
expose the identical API and inherit safe defaults (no scroll, lock unsupported) until their native
plumbing lands. Text/character + IME events are not yet implemented.

### Channel scope: the one rule to read before adding an input channel

`InputBackend` is a **per-window** abstraction: one instance per window, attached with `attach(NativeWindow)`.
Several OS input channels are not per-window, and **that mismatch is this codebase's most expensive bug
shape** — it has already produced one silent, hard-to-diagnose failure (a second window's backend stealing
RawInput from the first, so the first window's wheel and typing died with no error anywhere).

So every channel carries a declared **scope**, and the scope decides where its code lives and how its events
are routed:

| Channel | OS scope (Windows) | Where the code lives | Routing |
|---|---|---|---|
| Pointer position, buttons, key state | global (`GetCursorPos`, `GetAsyncKeyState` — polled) | per-window backend | positional / focal per event kind |
| Wheel, raw relative motion, typed text | **per-process** (RawInput: last registration wins) | `WindowsRawInputHub`, fanned to every backend | positional (wheel/motion), focal (text) |
| Focus (`GetForegroundWindow`) | per-window | per-window backend | always published |
| Pointer target (`WindowFromPoint` + client rect) | global read, per-window answer | per-window backend | n/a — it *is* the positional gate |
| Pointer lock, cursor clip/visibility | **global** (`ClipCursor`, `ShowCursor`, `SetCursorPos`) | per-window backend today; only one window may hold it | n/a |

Three rules follow:

1. **Process- or globally-scoped channels are owned once and fanned out.** They belong in the process hub
   (`WindowsRawInputHub`), never in the per-window backend. `RawInputScopeGuardTest` scans bytecode and fails
   the build if a process-scoped Win32 symbol is bound anywhere else, naming the symbol and the fix.
2. **Delivery is gated by event kind, in `InputPublisher` — the one seam.** Because the OS hands every window's
   share of a process-wide channel to every backend, publishing unfiltered puts one physical keystroke on two
   windows' buses. *Focal* events (keys, typed text) carry no position and are gated on `isFocused()`;
   *positional* events (wheel, pointer, buttons) are gated on `isPointerTarget()`. Windowless backends report
   `true` for both, so single-window and headless setups are unaffected.
3. **A gate answers the whole question, including occlusion.** Exactly one window is the pointer's target, and
   only the OS's stacking order says which — a consumer hit-tests its own tree and cannot see that another
   window is drawn over it. `isPointerTarget()` therefore asks both "is the cursor in my client rect?" *and*
   "is anything above me there?" (`WindowFromPoint`). Asking only the first is how one wheel notch came to
   scroll every overlapping window at once; `PointerTargetGateTest` stacks two real windows and holds that down.

When adding a channel, classify it **before** implementing: if the OS scope is wider than one window, it goes
in the hub and needs a routing decision. Adding a new process-scoped Win32 call also means adding its symbol to
the guard test's list.

### How events stay identical across OSes

The event loop lives in `tactroller-api`, not in the platform backends. It samples the backend
(`pollPointer` + `pollKeys`) at a fixed rate and diffs consecutive snapshots to derive press,
release and move events. That derivation is the *same code* on Windows, macOS and Linux — the
backends only ever report "current state", so there is no native event system to drift between
platforms. Backends may accelerate the snapshot (X11 reads the whole keyboard in one
`XQueryKeymap`) without changing the observable event semantics.

Add the API plus the host platform module as dependencies (or let the reactor build select them):

```xml
<dependency>
  <groupId>sibarum.tactroller</groupId>
  <artifactId>tactroller-api</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>sibarum.tactroller</groupId>
  <artifactId>tactroller-windows</artifactId> <!-- or -macos / -linux -->
  <version>1.0-SNAPSHOT</version>
</dependency>
```

## Clipboard

`tactroller-clipboard` is a **standalone** module — it depends on nothing (not even `tactroller-api`),
so a GUI can use it without the input subsystem. The host backend is picked at runtime from
`os.name`; Windows is implemented (`user32`/`kernel32`, `CF_UNICODETEXT`), macOS/Linux are stubs that
fail clearly until their bindings land.

```java
import sibarum.tactroller.clipboard.Clipboard;

try (Clipboard cb = Clipboard.open()) {
    cb.setText("copied");
    Optional<String> pasted = cb.getText();   // reads the real OS clipboard
    boolean has = cb.hasText();
}
```

Unlike the input backends (compile-time OS selection via Maven profiles), clipboard uses one
always-built module with a runtime OS factory — it binds only ever-present system libraries, so the
per-OS profile split would be overkill for four functions.

## Requirements

- JDK 25+ (Panama FFM is finalized; enforced by `maven-enforcer-plugin`).
- Run with `--enable-native-access=ALL-UNNAMED` (or the equivalent module directive) to silence the
  restricted-method warning.
- macOS additionally requires the process to hold Input-Monitoring (Accessibility) permission.

## Native-image

The GraalVM `native-maven-plugin` is preconfigured in the parent `pluginManagement`. Downcall
handles are created from constant `FunctionDescriptor`s, which native-image can register as FFM
downcall stubs; the `ServiceLoader` provider is picked up from `META-INF/services`. Add per-module
reachability metadata under `src/main/resources/META-INF/native-image/` when building images.
