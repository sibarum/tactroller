# Tactroller — TODO

## Queued key and button edges: try them with a real hand, then move Linux over

Windows now queues every key and button press and release from RawInput (`drainEdges()`), and `EdgeLedger` settles
the queue against the poll, so a click quicker than a frame, or two of them, arrives whole. `EdgeLedgerTest` holds
the settling down, but no real click has gone through it yet: an agent's runs are unattended and get nothing from the
devices. Throttle a GUI to a few frames a second (`GuiApp.maxFrameRate`) and confirm that quick clicks and double
clicks all land, that a drag still starts and ends, that a left-handed button swap behaves as it did, and that the
numeric keypad with Num Lock off types no stray keys (the raw report's virtual key has to match the one
`GetAsyncKeyState` reports, or the ledger reads a spurious tap). `RawInputFanoutTest` could inject a click with
`SendInput` the way it injects a wheel notch.

`tactroller-linux` still does what it did before the queue: it latches the XI2 raw presses and releases, and its poll
reports a click spread over two frames (`LinuxInputBackend.settle`). That loses the second click of a double click
inside one frame. Its latches should become a queue that `drainEdges()` returns, and `settle` should go: the ledger
does that job now.

## Dead keys, tried in a running application on Linux

`tactroller-linux` now composes dead keys and compose sequences through libxkbcommon (`Xkb`), and maps every keysym
with `xkb_keysym_to_utf32`. `Xkb` was checked directly (dead acute + `e` gives `é`, dead diaeresis + Shift+`U` gives
`Ü`, a legacy Cyrillic keysym gives `а`), but no real key press has gone through `X11WindowEvents` into an
application yet: the VM had no way to synthesise one. With a dead-key layout (US International, German, French), type
into Vex or the GUI demo and confirm: accented letters arrive once, a dead key followed by a letter it cannot accent
types just that letter, a dead key followed by space types the accent, and a dead key pressed before switching windows
does not carry over.

Also try a non-Latin layout (Russian, Greek) and switching layouts while the application is running.

## IME on Linux

There is no input-method support on Linux: Chinese, Japanese, Korean and other languages that compose text through an
IME cannot be typed. Compose (above) covers accents only. The X11 route is XIM (`XOpenIM`/`XCreateIC`,
`XmbLookupString`), which IBus and Fcitx both serve under XWayland; the modern route is the IBus/Fcitx D-Bus portals
or, once there is a native Wayland backend, `zwp_text_input_v3`. Either needs the API to carry preedit text and a
cursor rectangle for the candidate window, which it does not today, so decide that API before choosing the backend.
That API change will touch every OS module: Windows has IMM32/TSF for the same thing.
