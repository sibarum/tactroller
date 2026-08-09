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
    PointerState p = t.pointer();
    System.out.println(p.x() + "," + p.y() + " " + p.buttons());
    boolean jump = t.isKeyDown(Key.SPACE);
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
