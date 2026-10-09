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
- Pages can use passkeys backed by the phone's secure hardware. The app emulates WebAuthn
  itself; DESIGN.md's Passkeys section explains why and how.

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

**The passkey bridge is the one door from page JavaScript into the app.** Keep it narrow:

- The origin a passkey signs for comes from the running proxy (`PasskeySite`), never from a
  message. Do not add an origin, RP ID or "trusted" field to the bridge protocol.
- Every refusal that needs no user goes before the prompt, so a page cannot raise a
  fingerprint prompt for a request that was always going to fail.
- `PasskeyAuthenticatorTest` asserts "no prompt" for each refusal. Keep those assertions
  when adding one.
- Never use `addJavascriptInterface`. `WebViewConfigTest` checks the bytecode.

**`addDocumentStartJavaScript` only reaches documents that start after it.** That is why
`openSelected` offers passkeys before `loadUrl`. The script and the listener are installed
per origin and withdrawn on every switch, together with any prompt still showing.

**`passkeys.js` and `PasskeyBridge.NAME` are joined by a string.** If they drift, pages
silently have no passkeys. `WebViewConfigTest` checks that they agree.

**Adding a fingerprint invalidates every passkey key.** Android does this to keys bound to
biometrics. `signIn` then forgets the passkey and says why. That is expected behaviour,
not a bug to work around.

**Do not verify passkeys with `createNonStrictWebAuthnManager()`.** It passes every
attestation format except "none" without checking it, which once let a test with a broken
public key pass. `PasskeyAuthenticatorTest` builds its own verifier.

**Cookies ignore ports.** A request to `<label>.localhost` on any port carries that
endpoint's cookies, and any app can listen on a loopback port our proxy isn't holding. So
the WebView must never request a loopback origin other than the running proxy's.
`MainActivity.refusal` (`Origins.mayRequest`) enforces this on every request, service
workers included. Any new way of making requests needs it too: another client, another
hook, a restore path. Clearing the history after a switch and replacing a restored page
on the wrong port keep the UI from walking into a refusal; they are not the protection.
A restored WebView starts loading at once, on its own thread, so `openSelected` restores
the state only after the proxy is running and the passkey script is installed. Restoring
earlier, as `onCreate` once did, gets the restored page refused.

**The WebView reaches nothing but the tunnel, in three layers** (`Confinement.kt`):
`Origins.mayRequest` refuses every request that is not the running proxy's origin; a
process-wide proxy override, set in `IrohBrowserApp`, sends everything but `*.localhost` to
a dead port, which is what stops WebSockets; `no-webrtc.js` removes `RTCPeerConnection`.
The bypass rules are order-sensitive: Chromium lets later rules override earlier ones, so
`<-loopback>` must come before `*.localhost`, or the tunnel itself goes to the dead port.
External links open only on a tap (`hasGesture`). A page wanting a CDN font or an external
login is expected to fail. Serve it from the far side; do not loosen the check.

**Every endpoint has an origin, running or not.** `ProxyController.identify` reads it from
the ticket in Rust, starting nothing. Removing an endpoint that is not on screen depends
on it: its site data is cleared at its preferred port. The open endpoint uses the running
proxy's port instead, because the proxy may have fallen back to another one.

**Endpoint names are optional.** `Endpoint.name` is null until the user names one, and
the label stands in. Earlier versions saved the ticket cut to twelve characters as a
default ("endpointaank"), and `Endpoints.fromJson` reads exactly that as no name.

**A passkey with PRF has two keys and one touch.** The touch is tied to the PRF key's single
HMAC operation (`KeyOperation.Hmac`). The signing key of such a passkey is a window key, and
priming it (`KeyVault.signer`) is what needs the recent touch, so it must happen after the
prompt; before the prompt it throws. `FakeKeyVault` enforces this too. The PRF results come
from a per-passkey secret, derived from `WebAuthn.PRF_SECRET_MESSAGE`. Never change that
string: every key a site has derived from a result would change with it.

**To put a local service behind iroh for testing, use `iroh-uds-listen`** from
sashee/nixos-test's `packages/iroh-ssh`, the same far side the monitoring platform has.
nixpkgs' `dumbpipe` speaks the same wire format, but the pinned one is built on iroh 0.35,
which the app's iroh 1.0 cannot reach.

**Release must keep `panic = "unwind"`.** iroh's Android DNS path relies on unwinding to
fall back to public nameservers when no JNI context is installed. `abort` turns that
fallback into a crash.

## Where things live

Rust (`rust/src/`) — each module is small and its tests are beside it:

- `proxy.rs` — the accept loop: dial, handshake, pipe. The only orchestration.
- `pipe.rs` — `forward_bidi`, generic over the reader/writer pair so it is testable.
- `ticket.rs` — ticket/endpoint-id parsing, plus `host_label` and `preferred_port`, which
  between them decide the origin. Both must stay pure functions of the endpoint id: the
  origin has to be identical across launches or web storage and restored history are lost,
  and the label is also the RP ID of the endpoint's passkeys. `identity` combines them
  for the app, which needs the origin of endpoints that are not running.
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
- `Confinement.kt` — keeping the WebView off the network: the proxy override, and the seam
  that installs `assets/no-webrtc.js`.
- `MainActivity.kt` — orchestration only.
- `AppContainer.kt` — dependency seam; tests install their own.
- `Settings.kt` — what the "Endpoints and passkeys" screen lists: each endpoint's origin,
  its passkeys, and the passkeys whose endpoint is gone. Pure.
- `SettingsScreen.kt` — draws that and asks before anything irreversible. Holds no state:
  the activity renders it again after every change.

Passkeys (`app/src/main/java/com/example/irohbrowser/` and `app/src/main/assets/`):

- `assets/passkeys.js` — injected into pages. It translates calls for the bridge and
  decides nothing.
- `PasskeyRequests.kt` — parsing the bridge's messages, plus the browser's rules: the RP ID
  is the host, which passkeys are candidates, ES256. Pure.
- `WebAuthn.kt` — the bytes: CBOR, the COSE key, `clientDataJSON`, authenticator data,
  attestation objects. Pure.
- `PasskeyAuthenticator.kt` — the two ceremonies, and the response JSON.
- `PasskeyBridge.kt` — the message protocol: ids, the origin check, one ceremony at a time,
  cancellation.
- `PasskeyStore.kt` — what is remembered about each passkey, including whether it has a
  PRF key. The keys themselves stay in the Keystore.
- `PasskeyPlatform.kt` — the seams: `KeyVault`, `PasskeyUi`, `PasskeyInstaller`.
- `AndroidPasskeys.kt` — the platform side of those seams: Keystore/StrongBox,
  BiometricPrompt, `WebViewCompat`. Thin, and only exercised on a device.

## Debugging on a device

The Rust half logs to logcat under the tag `irohbrowser`, initialised in
`nativeInstallContext`:

```sh
adb logcat -s irohbrowser:V
```

`android_logger` routes the whole `log` facade, so the dependencies log under our tag too.
Our crate is at Info and iroh, noq, hickory and rustls at Warn: at Info, iroh logs every
path event and every send, which on a Pixel 6a buried everything else. A tracing span
without fields arrives under the target `tracing::span`, not its own module, so that needs
its own `tracing=warn`. logcat still shows such a line as `iroh::…`, because that is the
module path android_logger prints, and the filter only sees the target. Raise them in
`android.rs` when chasing a transport problem; that is how the `/proc/net` denial above was
found. The Kotlin side logs under the same tag, including where each new passkey key ended
up (`passkey key created in StrongBox`).

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
- Passkeys are tested at three levels:
  - **`PasskeyAuthenticatorTest`**: whole ceremonies with software keys, verified by
    webauthn4j acting as the server.
  - **`app/src/test/js/passkeys.test.mjs`** (Node, `nix-build -A pageScripts`): the
    injected script against a fake bridge.
  - **On the device** (README.md): the Keystore, BiometricPrompt and the WebView's bridge.

## Style

`AGENTS.md` at the repo root of the wider workspace applies: prefer pure functions, avoid
mutation, keep state minimal and at the edges. The deliberate exceptions are:

- the `OnceLock<Mutex<Option<Running>>>` in `android.rs`: the JNI boundary has nowhere
  else for it to live;
- the `Endpoints` field on `MainActivity`: an Android activity is a mutable object by
  construction;
- the ceremony in progress in `PasskeyBridge`: callbacks from the prompt are the only way
  it ends, and "one at a time" needs something to remember that one.
