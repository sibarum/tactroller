# OS-level drag-and-drop: `tactroller-dnd`

Design for the second half of drag-and-drop. The first half — recognizing a drag gesture — landed as
`DragGesture` in `tactroller-api` and needs nothing native. This document covers the other kind: exchanging
data with *other applications*, so a file dragged out of Explorer lands in your window and a thing dragged
out of your window lands in Explorer.

## Why this is a module, not a channel on `InputBackend`

The temptation is to add `dragEnter`/`dragOver`/`drop` events to `InputEvent` and let the existing gates
route them. That is the wrong shape, and the reason is the invariant the whole codebase rests on:

> The event loop lives in `tactroller-api`, not in the platform backends. It samples the backend and diffs
> consecutive snapshots to derive events. That derivation is the *same code* on Windows, macOS and Linux —
> the backends only ever report "current state", so there is no native event system to drift between
> platforms.

OS drag-and-drop has no polled form on any platform. It is a callback protocol in which the OS *asks a
question and blocks for the answer*:

| | Every existing input channel | OS drag-and-drop |
|---|---|---|
| Model | poll current state, diff snapshots | the OS calls you |
| Timing | you sample at your own rate | modal loop; a synchronous answer is required *now* |
| Return value | none — you observe | `DROPEFFECT_COPY`/`MOVE`/`NONE`, which drives the OS cursor |
| Payload | none | typed data, negotiated by format |
| Failure mode | a stale reading | the source app hangs |

There is nothing for `snapshot()` to sample and nothing for the shared loop to diff. Bolting it onto
`InputBackend` would put a modal COM loop inside a polled-state abstraction, and every platform would then
implement the whole thing natively anyway — paying the cost of the shared abstraction while getting none of
its benefit.

**`tactroller-clipboard` is the right precedent.** DnD is the clipboard with a cursor attached: payload-typed,
standalone, format-negotiated. On Windows it is *literally the same interface* — `IDataObject`, the same one
`OleSetClipboard` takes. Modeling on clipboard also inherits its packaging decision, which fits here for the
same reason: one always-built module with a runtime `os.name` factory, rather than the input modules'
compile-time Maven profile split.

## Scope classification (do this before writing code)

The README's channel-scope rule requires every channel to declare its OS scope before implementation.

| Channel | OS scope (Windows) | Where the code lives | Routing |
|---|---|---|---|
| Drop target registration | **per-window** (`RegisterDragDrop(hwnd)`) | per-window, in the DnD module | n/a — the OS routes by HWND |
| Drag source | per-gesture (`DoDragDrop`, blocks the calling thread) | per-window, in the DnD module | n/a |

Per-window, so it does **not** belong in `WindowsRawInputHub`. Two consequences:

1. **It needs no gate.** The OS routes drops to an HWND itself, resolving occlusion in the process — the one
   thing `isPointerTarget()` exists to do. This is a channel where the OS has already answered the question
   the gate asks.
2. **The guard test still needs updating.** `RawInputScopeGuardTest` fails the build when a *process-scoped*
   Win32 symbol is bound outside the hub. `RegisterDragDrop` and `DoDragDrop` are per-window and per-gesture,
   so they must **not** go on that list — but `OleInitialize` is per-*thread* apartment state, which is a
   third scope the guard does not currently model. Decide explicitly where OLE initialization lives
   (recommendation below) and record it in the README's scope table either way.

## Recommended surface

Two halves, mirroring the two directions, both standalone:

```java
// --- Receiving: this window accepts drops ---
try (DropTarget dt = DropTarget.register(NativeWindow.ofHwnd(hwnd))) {
    dt.setHandler(new DropHandler() {
        // Asked repeatedly as the pointer moves over the window. The return value drives the OS
        // cursor, so it must be cheap: no I/O, no reading the payload, just a look at the formats.
        public DropEffect effectFor(DragInfo info) {
            return info.hasFiles() ? DropEffect.COPY : DropEffect.NONE;
        }
        public void drop(DragInfo info) {
            for (Path p : info.files()) open(p);     // reading the payload is fine here
        }
        public void exit() { clearHighlight(); }
    });
}

// --- Sending: drag out of this window ---
// Started from DragEvent.DragStarted. Blocks until the drop resolves, so it must not be called
// on the render thread.
DropEffect result = DragSource.from(window).offerFiles(List.of(path)).start();
```

Design notes on that surface:

- **`DropEffect` is the return value, not a setter.** `IDropTarget::DragOver` returns the effect; making the
  handler return it keeps the Java signature honest about the fact that the OS is waiting. It is named
  `effectFor`, not `over`, to stay clear of `DragEvent.DragOver` — this callback answers "what would happen
  if you dropped here", which is a different question from "the pointer moved during a drag".
- **`DragInfo` exposes formats before data.** `effectFor()` is called on every mouse move inside a modal loop —
  materializing a payload there is how DnD becomes visibly laggy. Format inspection is cheap; extraction
  happens only in `drop()`.
- **Start with files (`CF_HDROP`) and UTF-16 text (`CF_UNICODETEXT`).** They cover most real use and reuse the
  clipboard module's existing format plumbing. Custom formats can come later behind
  `offerBytes(String formatName, byte[])`.

## Implementation notes, per platform

**Windows** is the real work and should be built first, matching the module's existing state.

- `OleInitialize` must be called on the thread that registers, and that thread must be an STA with a running
  message pump. **Recommendation: give the DnD module its own pump thread**, exactly as
  `WindowsRawInputHub` gives RawInput one, rather than depending on the host window's pump. That decision is
  already precedent in this codebase and it is why the input backend does not care what toolkit the host uses.
- `IDropTarget` is a COM vtable — five function pointers (`QueryInterface`, `AddRef`, `Release`, plus
  `DragEnter`/`DragOver`/`DragLeave`/`Drop`) built as FFM upcall stubs into an `Arena` that outlives
  registration. This is the single riskiest piece: an upcall that throws across the COM boundary is undefined
  behavior, so **every upcall body must be wrapped in a catch-all** that returns an `HRESULT` rather than
  propagating. The existing RawInput `WndProc` upcall is the model to copy.
- Refcounting: keep the object alive for the registration's lifetime and release in `close()`; do not try to
  make `AddRef`/`Release` actually drive the Java object's lifetime.
- `DoDragDrop` blocks for the entire gesture. It needs its own thread, and the API should say so loudly.

**macOS** — `NSDraggingDestination` on the content view, plus `registerForDraggedTypes:`. Requires an
Objective-C class created at runtime (`objc_allocateClassPair` + `class_addMethod` with FFM upcalls), which is
a heavier lift than the CoreGraphics polling the input backend does today.

**Linux** — XDND is a wire protocol over `ClientMessage`, not a library call: `XdndEnter`/`Position`/`Status`/
`Drop`/`Finished`, with the `XdndAware` property on the window. No COM, no upcalls, but the most protocol
state to get right, and it needs the X11 event stream — which the current Linux backend, being purely polled,
does not consume.

Stub macOS and Linux the way the clipboard module stubs them: fail clearly at `register()` rather than
silently accepting no drops.

## Suggested order

1. `DropTarget` + `DropHandler` + `DragInfo` + `DropEffect` API, with a windowless no-op backend.
2. Windows receive path (`RegisterDragDrop`, `IDropTarget`, `CF_HDROP`) — this alone is the majority of the
   value, since dragging files *in* is the common case.
3. Windows send path (`DoDragDrop`, `IDataObject`, `IDropSource`).
4. macOS, then Linux.
5. README: add the scope-table rows and a `## Drag and drop` section covering both the gesture recognizer and
   this module.

## The seam between the two halves

`DragGesture` and `tactroller-dnd` meet at exactly one point and are otherwise independent: a
`DragEvent.DragStarted` is the moment a consumer decides to call `DragSource.start()`, promoting an in-app
drag into an OS one. Everything else — threshold, cancellation, drop-target hit-testing inside your own
window — stays in the gesture layer and never touches native code. Keeping the promotion explicit, rather
than having the gesture recognizer start OS drags on its own, is what lets an app use one, the other, or both.
