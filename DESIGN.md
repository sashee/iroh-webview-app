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
- **No `addJavascriptInterface`.** There is no bridge from page JavaScript into the app.
  `WebViewConfigTest` asserts the compiled class does not so much as reference it.
- **External links leave the WebView.** `http`/`https` outside our own origin go to the
  real browser; `intent:`, `file:`, `content:`, `javascript:` and `data:` are refused
  outright (`Origins.externalDestination`).
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

`nix-build` is the whole gate, and CI runs exactly it. Three stages, all offline:

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
3. **the APK** — Gradle resolving from a vendored Maven repository built by
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

**Passkeys / WebAuthn.** Blocked rather than merely harder: in embedded WebView the origin
the server sees is `android:apk-key-hash:…` and needs an `assetlinks.json` fetchable at a
public domain we do not have. Revisit after the domain exists.

**Tailscale or WireGuard instead of all of this.** Genuinely the lowest-effort way to get a
phone-readable dashboard. Rejected only because iroh-as-transport is a goal in itself here.
