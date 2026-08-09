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
