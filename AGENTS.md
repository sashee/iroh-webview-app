# AGENTS.md

Guidance for AI agents working on this repo. See [DESIGN.md](DESIGN.md) for architecture
and [README.md](README.md) for user-facing docs.

## What this app is

An Android app (package `com.example.irohbrowser`, minSdk 34 — one target device) that browses an HTTP service
reachable only over an iroh endpoint. A Rust cdylib runs a loopback TCP proxy that carries
each connection over iroh; a thin Kotlin shell points a WebView at it. Built with Nix.

- Build + test: `nix-build` → Rust tests, cross-compiled cdylibs, Robolectric tests, then
  `./result/app-release.apk`.
- `nix-build -A rust` for just the Rust suite; `-A nativeLibs` for just the libraries.
- Do **not** run `gradle` in `nix-shell` for a full offline build — dependency resolution
  needs the vendored Maven repo and init script that `default.nix` wires up. `nix-shell` +
  `gradle` is for regenerating `app/gradle.lockfile` and `gradle/verification-metadata.xml`
  after a dependency change, and that needs network.
- The release signature is deterministic (keystore in `signing/`), so `adb install -r`
  preserves on-device data.

## The rule that governs everything

**The proxy copies bytes and never parses HTTP.** That is not an implementation detail, it
is the design. Chunked encoding, `Range`, `Set-Cookie`, redirects, keep-alive and
streaming all work for that one reason, and `rust/tests/transport.rs` has a test for each.

If you find yourself about to read a request line, inject a header, or rewrite a URL: stop.
The answer is almost always on the far side (which is growing a login page and session
cookies) or in the browser. The single exception is `rust/src/gateway.rs`, which *writes* a
canned 502 when a dial fails — it still reads nothing.

## Things that will bite you

**`noq::RecvStream` has an inherent `read_to_end(size_limit) -> Vec<u8>`** that shadows
`tokio::io::AsyncReadExt::read_to_end`. Calling the tokio one by UFCS compiles and then
hangs. Use the inherent API on iroh streams.

**An `Endpoint` is an Arc handle, and a `Connection` closes when its last handle drops.**
`send.finish()` does not flush synchronously. Dropping either while a response is still in
flight loses it, and the peer sees `ConnectionLost`. `proxy::handle` holds the connection
until both directions of `forward_bidi` are done, on purpose.

**iroh endpoints bound to `0.0.0.0` churn between local interfaces** in tests — repeated
`path::abandoned` with error code 62, and the exchange stalls. Test endpoints bind
`127.0.0.1:0` and use `presets::Minimal`, which is also what makes them work in the Nix
sandbox with no network.

**Do not await `Connection::closed()` in a test.** It does not resolve when the peer merely
drops its handle, and turns a passing test into a hang.

**The loopback port has no access control, and cannot have the obvious one.**
`/proc/net/tcp` is `EACCES` for untrusted apps from Android 10 on, so a uid check is not
available — do not reintroduce one without testing it on a device. See the note in
`rust/src/lib.rs` for what the exposure is and what would actually work.

**Robolectric must not load the native library.** `app/src/test/resources/robolectric.properties`
substitutes `testing.TestApp` for `IrohBrowserApp` precisely so `System.loadLibrary` is
never called on the build host. If you add a test that needs the real Application, it will
fail with `UnsatisfiedLinkError` before anything runs.

**WebView defaults that are wrong for a browser.** `builtInZoomControls` is false by
default, which silently disables pinch-to-zoom; a download listener and an
`onShowFileChooser` are absent by default, which makes downloads and file inputs do nothing
at all. All three fail silently, so nothing points at them.

**Release must keep `panic = "unwind"`.** iroh's Android DNS path relies on unwinding to
fall back to public nameservers when no JNI context is installed. `abort` turns that
fallback into a crash.

## Where things live

Rust (`rust/src/`) — each module is small and its tests are beside it:

- `proxy.rs` — the accept loop: dial, handshake, pipe. The only orchestration.
- `pipe.rs` — `forward_bidi`, generic over the reader/writer pair so it is testable.
- `ticket.rs` — ticket/endpoint-id parsing, plus `host_label` and `preferred_port`, which
  between them decide the origin. Both must stay pure functions of the endpoint id: the
  origin has to be identical across launches or web storage and restored history are lost.
- `Downloads.kt` (Kotlin) — the `127.0.0.1` rewrite for `DownloadManager`, which cannot
  resolve `*.localhost`.
- `gateway.rs` — the canned 502.
- `runtime.rs` — start/stop for a non-async caller; owns the tokio runtime.
- `android.rs` — JNI only, compiled for Android alone. No judgement in it.

Kotlin (`app/src/main/java/com/example/irohbrowser/`):

- `Endpoints.kt` — the saved-state model. Immutable, pure, and where the selection rules
  live. Most of the behaviour worth testing is here rather than in the activity.
- `Origins.kt` — which URLs are ours and where the rest go. The browsing-direction
  security boundary.
- `MainActivity.kt` — orchestration only.
- `AppContainer.kt` — dependency seam; tests install their own.

## Debugging on a device

The Rust half logs to logcat under the tag `irohbrowser`, initialised in
`nativeInstallContext`:

```sh
adb logcat -s irohbrowser:V
```

`android_logger` routes the whole `log` facade, so the level is Info — Debug pulls in
iroh, rustls and hickory and buries our own lines. Raise it in `android.rs` when chasing a
transport problem; that is how the `/proc/net` denial above was found.

A refused or dropped connection reaches the browser as `ERR_SOCKET_NOT_CONNECTED` with no
detail, so logcat is the only place the reason exists.

## Testing notes

- `rust/tests/support/mod.rs` builds a hermetic pair of ends: an in-test `iroh-uds-listen`
  and a hand-rolled HTTP origin. Hand-rolled on purpose — the tests need to send responses
  a well-behaved server library would not.
- Kotlin tests never touch the real proxy. `testing/FakeProxy` records the call *order*,
  which is what the activity has to get right: stop, start, load.
- **Sessions must survive a relaunch and a switch.** `SiteData` has no "clear everything"
  on purpose; the only clear is per-origin, on removal. An earlier version cleared on every
  `openSelected`, which ran at startup too and silently logged the user out on every launch.
  `relaunching keeps the session` is the guard against that coming back.
- The Robolectric suite cannot reach `*.localhost` resolution or real cookie behaviour.
  Those are the on-device checks in README.md, and they are the ones to run after touching
  the WebView or endpoint switching.

## Style

`AGENTS.md` at the repo root of the wider workspace applies: prefer pure functions, avoid
mutation, keep state minimal and at the edges. The two deliberate exceptions are the
`OnceLock<Mutex<Option<Running>>>` in `android.rs` (the JNI boundary has nowhere else for
it to live) and the `Endpoints` field on `MainActivity` (an Android activity is a mutable
object by construction).
