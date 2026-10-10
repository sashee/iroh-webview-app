# Design

## The problem

A monitoring platform runs on an rpi5 with no public IP, no DNS name and no TLS
certificate. Its HTTP interface is reachable only through an iroh endpoint. It needs to be
readable from a phone.

## The shape

```
┌─ Android app ─────────────────────────────┐
│  Kotlin shell                             │
│   ├── SharedPreferences: saved tickets    │
│   └── WebView → http://<label>.localhost:PORT/
│              │                            │
│  Rust cdylib (JNI)                        │
│   ├── TCP listener on 127.0.0.1:0         │
│   └── iroh Endpoint ──── QUIC ────────────┼──▶ iroh-uds-listen (rpi5)
└───────────────────────────────────────────┘              │
                                              monitoring platform's unix socket
```

The WebView believes it is talking to an ordinary HTTP origin on loopback. **That is the
single most important property of the design.** Cookies, caching, redirects, relative URL
resolution, form handling, `Range` requests and content negotiation are therefore the
browser's job, not ours — and a browser is much better at them than anything we would
write.

## The wire format is dumbpipe's, unchanged

The far side already exists: `packages/iroh-ssh` in
[sashee/nixos-test](https://github.com/sashee/nixos-test), deployed by the
`monitoring-platform-tunnel.nix` module. It speaks dumbpipe:

- **ALPN** `DUMBPIPEV0`
- one iroh **connection** per local connection, carrying one bi stream
- the dialer writes the five bytes `hello`, then the stream is a raw byte pipe
- the listener connects to the monitoring platform's unix socket and copies bytes

We adopt it exactly. The app is indistinguishable from `iroh-uds-connect`, and **no server
change is required**.

The consequence worth stating plainly: **the proxy never parses HTTP.** It is a TCP
listener that dials, handshakes, and runs `tokio::io::copy` in both directions. Everything
the handover document worried about — chunked bodies, `Set-Cookie`, `Range`, keep-alive,
streaming — works for the same reason, and `rust/tests/transport.rs` asserts each of them
end to end.

### What this costs

One QUIC handshake per TCP connection from the WebView. A browser opens around six
connections per origin and then reuses them across requests, so this is a handful of dials
on first load rather than one per request. Accepted; revisit only if measured.

### The one exception

A failed dial has no bytes to forward, and closing the socket in silence produces
Chromium's "webpage not available" with no explanation. So a dial failure writes one canned
`502` and closes (`rust/src/gateway.rs`). Nothing is read or interpreted; a fixed byte
string is written.

## Per-endpoint origins

Cookies key on **host**, and ports are not part of the key. If every endpoint were browsed
at `127.0.0.1:PORT`, switching endpoints would send server A's session cookie to server B.

Each endpoint is therefore browsed at `http://<label>.localhost:<port>/`, where the label
is derived from the endpoint id (`rust/src/ticket.rs`, `host_label`). Chromium resolves
`*.localhost` to loopback without DNS and treats each label as a distinct origin, so this
yields a separate cookie jar and a separate autofill identity per endpoint. `*.localhost`
is also a trustworthy origin, so secure contexts and `crypto.subtle` work normally.

The **port** is a pure function of the endpoint id too (`ticket::preferred_port`), and for a
reason that is easy to miss: cookies key on host alone, but `localStorage`, `sessionStorage`
and IndexedDB key on the whole origin *including the port*, and a restored WebView history
names the port it was saved with. A random port each launch would therefore wipe web
storage and break "reopen on the last page" while leaving cookies intact — a confusing
half-failure. The preferred port is a preference, not a requirement: if something else on
the device holds it, the proxy takes any free port and only that launch's web storage
suffers.

Together the label and the port make the whole origin a pure function of the endpoint id, so
returning to an endpoint returns to exactly the same origin.

Sessions are meant to **persist**: across switching endpoints, and across the app being
closed. Nothing is cleared on launch or on a switch — the distinct origins already keep the
jars apart, so clearing would only throw away a session the user still wants. The one place
anything is forgotten is removing an endpoint, which clears that origin alone
(`SiteData.clear`) and leaves every other endpoint logged in.

Any endpoint can be removed, not just the open one, because its origin can be worked out
without running it: `ticket::identity` is the same pure label-and-port derivation, called
through JNI. Removing an endpoint that is not open leaves the open one's proxy and page
untouched.

Two things have to hold for a session to survive the process dying, and only one of them is
ours: `CookieManager.flush()` in `onPause` (persistent cookies are written lazily), and the
server setting an explicit `Max-Age` or `Expires`. A cookie with neither is a *session*
cookie and is discarded when the process does, whatever the app does.

## Security

- **The loopback port is open to any app on the device, and cannot be closed.** This was
  designed the other way — look the peer socket up in `/proc/net/tcp{,6}`, require our own
  uid — and that does not work on Android. SELinux denies untrusted apps those files from
  Android 10 onwards (verified on Android 17: `Permission denied (os error 13)`), and the
  supported replacement, `ConnectivityManager.getConnectionOwnerUid`, is restricted to the
  active VpnService. There is no app-accessible way to identify a loopback peer.

  What it costs is reachability, not data. A session cookie lives in the WebView's own jar
  and is attached only to requests the WebView makes, so another app that finds the port
  gets an *unauthenticated* conversation with whatever is behind the endpoint — the same
  thing it would get from knowing the ticket. That is the posture the far side already
  takes: "this authenticates nobody ... what is behind the socket does the authenticating"
  (`monitoring-platform-tunnel.nix`).

  If a real boundary is wanted later, the one mechanism that works is a per-session secret:
  the app plants a random token with `CookieManager.setCookie` on the origin, and the proxy
  requires it in the first request head of each connection. That costs inspecting (not
  rewriting) that head, and is deliberately not built until it is needed.
- **Nothing the WebView does leaves the device except through the tunnel.** The page comes
  from an arbitrary peer. Anything it names on another host would otherwise be fetched
  straight from the internet: an image, a font, a beacon, a form post. That host would
  learn the phone's address and what the page is. Three layers stop it, each covering what
  the one before cannot see (`Confinement`):
  - `Origins.mayRequest` refuses every request that is not the running proxy's origin,
    with a 403, and logs it to logcat. `data:`, `blob:` and `about:` pass, because
    fetching one sends nothing anywhere. It runs in `shouldInterceptRequest` and the
    service-worker client, which between them see subresources, fetches, forms, frames and
    navigations however they started.
  - A process-wide proxy override, set in `IrohBrowserApp` before the first page, sends
    every connection except to `*.localhost` to `127.0.0.1:1`. No app may listen there,
    so each one is refused at once. It catches what neither hook sees: WebSockets, and the
    connections the browser opens speculatively. Chromium's own exceptions for loopback
    and link-local hosts are removed (`<-loopback>`), and `*.localhost` is bypassed after
    that, because later bypass rules override earlier ones. iroh is in the Rust half and
    never passes through the override.
  - `assets/no-webrtc.js` removes `RTCPeerConnection` at document start, in every frame
    it reaches, because WebRTC's UDP goes beneath both. This one is best-effort: a page
    set on having WebRTC can find a fresh copy in an iframe the script never ran in.

  A page that wants a CDN font or an external login therefore does not get it. The answer
  is to serve it from the far side.
- **The WebView talks to loopback only at the running proxy's exact origin.** Cookies are
  keyed by host, not port (RFC 6265), and any app can listen on a loopback port that our
  proxy isn't holding. Without this rule, two paths would send an endpoint's session to
  whoever listens there, along with a page on its exact origin that can read its storage:
  - pressing back into an endpoint whose proxy has stopped;
  - a restored page whose port the proxy didn't get back.

  `Origins.mayRequest` is checked on every request, in `shouldInterceptRequest` and in the
  service-worker client, so it holds however a navigation started. The history is dropped
  after a switch, and a restored page on the wrong port is replaced by the front page.
  WebSockets don't pass through either hook, and the proxy override lets `*.localhost`
  through on every port. But only a page already loaded from the endpoint can open one.
- **Two bridges from page JavaScript into the app: passkeys and the clipboard.** Each is a
  `WebMessageListener` that the WebView restricts to the running endpoint's exact origin,
  and each carries data, not callable methods. The passkey bridge accepts two operations,
  create and get, and each needs a fingerprint before it signs anything (see
  [Passkeys](#passkeys)). The clipboard bridge accepts text to copy, and nothing else (see
  [Clipboard](#clipboard)). `addJavascriptInterface` is still absent: `WebViewConfigTest`
  asserts that the compiled classes do not reference it at all.
- **External links leave the WebView, when the user taps them.** `http`/`https` outside
  our own origin go to the real browser, but only with a gesture
  (`WebResourceRequest.hasGesture`). Without one, a page could send itself elsewhere on a
  timer and carry whatever it liked out in the URL. `intent:`, `file:`, `content:`,
  `javascript:` and `data:` are refused outright (`Origins.externalDestination`).
- **Cleartext for loopback only**, via the network security config. A page that links to
  an `http://` site elsewhere still cannot load it.
- **No backups.** The tickets are addresses rather than secrets, but the cookie jar beside
  them is a live session.
- Pasted tickets are treated as untrusted: this is a browser for arbitrary peers, not a
  wrapper around one known-good server.

## Downloads and uploads

Neither works by default in a WebView; both fail silently, which is worse than failing.

A download goes to the system `DownloadManager`, with two adjustments. The URL is rewritten
to `127.0.0.1` on the same port, because `DownloadManager` runs in another process and
resolves through the system resolver, which knows nothing about `*.localhost` — that is a
Chromium special case. The proxy never reads the `Host` header, so the rewrite reaches the
same place. And the cookies are copied onto the request by hand, since the download is made
by a process with no access to this app's jar. `Downloads.systemUrl` refuses any URL that is
not our own origin: a download is triggered by the page, and a page from an arbitrary peer
should not get to aim the system downloader.

An upload uses `WebChromeClient.onShowFileChooser` into `OpenMultipleDocuments`. The only
subtlety is that a pending `ValueCallback` must always be answered — with null on
cancellation or teardown — or the page's file input stays disabled for good.

## Passkeys

A page served through the tunnel can create and use passkeys, and a sign-in is a
fingerprint. The key lives in the phone's secure hardware and only ever signs after one.
The WebView's own WebAuthn cannot do this here (see the rejected alternatives), so the app
plays both parts that a browser and its platform would:

- **The page's API** is `assets/passkeys.js`. It is injected at document start, for the
  running endpoint's origin only, and replaces `navigator.credentials.create` / `get` and
  `PublicKeyCredential`. It relays each call over a `WebMessageListener` named
  `__irohPasskeys`, restricted to the same origin. It only translates (ArrayBuffers to
  base64url and back, error names to exceptions) and decides nothing.
- **The browser's checks** are `PasskeyRequests`.
  - The RP ID is the page's host. A page that leaves the RP ID out gets the host; one that
    names anything else gets a `SecurityError`. That is stricter than a browser, which
    also accepts a parent domain, but each endpoint is exactly one host.
  - Only that RP ID's passkeys are offered.
  - The origin in `clientDataJSON` comes from the running proxy (`PasskeySite`), never
    from the page.
- **The authenticator** is `PasskeyAuthenticator` plus an Android Keystore key.
  - The key is P-256, in StrongBox (the Titan M2) where there is one.
  - Every single signature needs a strong biometric or the screen lock; there is no
    "unlocked for 30 seconds" window. The one exception is below, under PRF.
  - Registration signs too, so the user-verified flag is earned, not claimed.
  - Attestation is `none`, or `packed` self attestation if the server asks for more. The
    AAGUID is zero and the signature counter stays at zero.

Every refusal that needs no user (wrong RP ID, unsupported algorithm, no passkey for this
site, already registered) happens before the prompt. A page cannot make the phone ask for
a fingerprint on behalf of a request that was going to fail anyway.

**PRF: keys a site derives, which never leave the phone.** The WebAuthn PRF extension lets
a site ask a passkey for a secret that depends only on the passkey and an input the site
chooses. A site uses it to derive encryption keys in the page, so the data it stores is
ciphertext it cannot read. The app implements the whole extension:

- `prf.enabled` at registration;
- `eval` (`first`, and `second` for rotation) and `evalByCredential` at sign-in, held to
  the specification's rules about the allow list;
- `extension:prf` in `getClientCapabilities()`.

Results are given at sign-in only, as many authenticators do.

Asking for PRF at registration is what gets a passkey a second key: an HMAC key in
StrongBox that needs a touch for every operation. The phone ties each touch to exactly one
hardware operation, and a sign-in should be one touch, so:
- the touch goes to the PRF key, for one HMAC that derives the passkey's PRF secret;
- the signing key of such a passkey accepts that touch for 5 seconds and signs straight
  after;
- the results are HMAC-SHA-256 of SHA-256("WebAuthn PRF" ‖ 0x00 ‖ input) under that
  secret, which is exactly CTAP's hmac-secret, and the secret is wiped.

However many inputs a sign-in asks about, it is one touch, and every evaluation needs one.
Passkeys registered without PRF keep a single, strictly per-touch key. The message the
secret is derived from is fixed for good, since changing it would change every result a
site has derived a key from.

Adding a fingerprint invalidates the PRF key but, as Android does for windowed keys, not
the signing key. A passkey left half-working would only fail later, so the app forgets the
whole passkey, as it does when a signing key is invalidated. Recovering encrypted data
after that is the site's design: wrap the data key under the PRF-derived key and under
something else.

**What the server must do.** The RP ID is `<label>.localhost`, and the label comes from the
tunnel's endpoint id, which a web app behind the tunnel does not know. So the server:

- leaves the RP ID out of its options;
- records the origin each passkey was registered from, and checks sign-ins against that
  record, ignoring the port (the preferred port can fall back, see above);
- accepts registrations only from `http://localhost` and `http://*.localhost`. Those always
  resolve to the device the browser runs on, so no remote site can phish a registration.


**What the bridge exposes.** A page from an arbitrary peer can do two things: raise a
fingerprint prompt, and, with the user's finger, create or use a passkey for its own host.
The prompt names the endpoint, so a prompt from the wrong one is visible as such. A page
cannot reach another endpoint's passkeys. The bridge is installed for one origin and
withdrawn on every switch, and the RP ID rule and the origin check in `PasskeyBridge` would
each stop it independently. Other apps on the device do not see the bridge at all: it lives
in the WebView, not on the loopback port.

**Limits.**
- Passkeys are bound to this phone. Uninstalling the app or enrolling a new fingerprint
  ends them (Android invalidates the keys), so a server needs another way in for
  re-registering.
- ES256 only.
- No conditional mediation (autofill-style sign-in): sites are told so and show a button
  instead.
- No extensions beyond `credProps` and `prf`.
- One ceremony at a time.
- Removing an endpoint keeps its passkeys. The server still trusts them, and re-adding the
  endpoint (same id, same host) makes them usable again. Until then the "Endpoints and
  passkeys" screen lists them as "without an endpoint", where they can be deleted.

## Clipboard

A page served through the tunnel may be a password manager, and a password it copies
should be marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`). Android then shows dots
in the copy preview, and keyboards leave the clip out of their clipboard history. The
WebView never sets the flag, and nothing in WebView settings or androidx.webkit asks it to.
So the app writes the clip itself:

- `assets/clipboard.js` is injected at document start, for the running endpoint's origin
  only. It replaces `navigator.clipboard.writeText`, and `write` of a single item that has
  `text/plain`, with a message to a `WebMessageListener` named `__irohClipboard`. A `write`
  with other types besides `text/plain` copies only the text.
- `ClipboardBridge.answer` copies the text only for the running proxy's origin, and only
  while the page is on screen and its window has focus. That is the browser's own rule
  for `writeText`. Android lets a background app write the clipboard, and the page keeps
  running while the app is in the background.
- `copySensitive` writes the clip with the flag set. It is the only write, so the text is
  never on the clipboard unmarked.

**Limits.** Copying a selection (long-press, Copy) and `document.execCommand("copy")` go
through Chromium and are not marked, and neither is a `write` with no `text/plain`. A page
that wants its copies marked uses `navigator.clipboard.writeText`. The bridge gives a page
nothing it did not have: it could already replace the clipboard while on screen.

## Android specifics

**DNS.** iroh resolves a bare endpoint id through DNS, and Android has no
`/etc/resolv.conf` — `iroh_dns` reads the active network's nameservers through JNI, which
needs an Android context installed before the first resolver is built.
`IrohBrowserApp.onCreate` does that via `NativeProxy.installContext`. Without it iroh
catches the resulting panic and falls back to public nameservers, which is why the release
profile keeps `panic = "unwind"`: `abort` would turn that fallback into a crash. Tickets
carrying relay urls avoid the whole question, which is why `ticket::parse` keeps them.

**No `rustls-platform-verifier`.** The server enables it so a hermetic VM test can
impersonate the n0 relays with its own CA. The phone talks to the real public relays, so
the compiled-in roots are correct — and dropping it removes the entire Android trust-store
plumbing (`init_hosted`, an extra Gradle artifact).

## Build

`nix-build` is the whole gate, and CI runs exactly it. Four stages, all offline:

`minSdk` and `targetSdk` are both 34: the only device this targets runs Android 17, and
matching them leaves one platform's behaviour to reason about. It also means downloads need
no storage permission (scoped storage, from API 29) and Robolectric needs one runtime jar
rather than two.

1. **`-A rust`** — the proxy crate's tests on the host, via `buildRustPackage` with
   `cargoLock.lockFile` and a vendored crate directory.
2. **`-A nativeLibs`** — the cdylib for `arm64-v8a` and `x86_64`, using a
   [fenix](https://github.com/nix-community/fenix)-pinned toolchain for the Android
   `rust-std` and the NDK from `androidenv` for the linker. `cargo-ndk` is not used: it
   only sets the variables that `nix/native-libs.nix` sets directly.
3. **`-A pageScripts`** — the injected scripts' tests, in Node: passkeys against a fake
   bridge, and the one that removes WebRTC. Robolectric's WebView runs no JavaScript, so
   this is the only off-device run of the scripts inside the gate.
4. **the APK** — Gradle resolving from a vendored Maven repository built by
   `buildGradleApplication`'s `mkM2Repository`, running the Robolectric suite and signing
   with the committed keystore.

The libraries reach Gradle as a directory through `JNI_LIBS_DIR`, the same shape
sms-forwarder uses for its CA bundle. Gradle does not build Rust; keeping the halves
separate is what lets the Rust tests run on the host while the libraries are built for the
phone.

## Rejected alternatives

Kept so nobody re-litigates them.

**The handover's HTTP-framed protocol** (new ALPN, one stream per request, hyper on both
ends). Superseded by the byte pipe: it would need a matching change on the monitoring
platform before the app could talk to anything, and it would buy nothing the pipe does not
already give.

**Injecting an API key header in the proxy.** Would require parsing HTTP/1.1 and would
forfeit byte-transparency. The far side is growing a login page and session cookies, which
is the right place for it.

**Service worker + iroh in WASM.** iroh in browsers is relay-only, service workers are
killed when idle, and `Set-Cookie` on a synthesised `Response` never reaches the browser's
cookie jar — so you end up hand-rolling a cookie jar in IndexedDB and reimplementing the
boring parts of a browser.

**`WebViewClient.shouldInterceptRequest` instead of a loopback proxy.** Same defect:
`Set-Cookie` on a synthesised `WebResourceResponse` is not reliably fed into
`CookieManager`.

**Overriding `window.fetch`.** `<img>`, `<link>`, `<script>` and fonts never go through
`fetch`.

**The WebView's own WebAuthn** (`WebSettingsCompat.setWebAuthenticationSupport`). In "app"
mode the server sees the origin `android:apk-key-hash:…`, and the passkey must belong to a
domain that publishes `assetlinks.json` over HTTPS. We have no such domain. In "browser"
mode the app must be on the privileged-browser list of Google Password Manager (approval
by form) or of whichever provider the user has. Hence the emulation described under
[Passkeys](#passkeys).

**Credential Manager with `setOrigin`.** This passes the page's real origin to the user's
passkey provider. Google Password Manager and Proton Pass refuse a browser that is not on
their list. Bitwarden and Keyguard accept one after a "trust this browser" prompt. Rejected
because passkeys would depend on each provider's list, and because a key in the phone's
own hardware never leaves it, which a synced provider's key does.

**Marking the clipboard after the WebView wrote it** (an `OnPrimaryClipChangedListener`
that rewrites each new clip with the sensitive flag). This would cover every kind of copy,
selections included. But the unmarked clip exists first, and a keyboard can save it to its
clipboard history before the rewrite lands. Hence the bridge described under
[Clipboard](#clipboard).

**Tailscale or WireGuard instead of all of this.** Genuinely the lowest-effort way to get a
phone-readable dashboard. Rejected only because iroh-as-transport is a goal in itself here.
