# Handover: generic iroh → HTTP browser (Android)

## 1. What this is

A small Android app that lets you browse an ordinary HTTP web app whose only reachable
transport is an **iroh** endpoint (QUIC over public-key addressing, no public IP, no DNS,
no TLS certificate).

The app is **not specific to any one backend.** It is a generic client: paste an iroh
endpoint ID, get a browser pointed at whatever HTTP service sits behind it.

Immediate driver: a self-hosted monitoring platform (Rust) that currently exposes an
HTTP API behind iroh, protected by API keys, and needs to be readable from a phone.

**Long-term target state** (important — it constrains today's decisions): the same
server will eventually run on a publicly reachable host with a real domain, real TLS,
username + password login, and normal session cookies. Everything below is designed so
that migration changes the *transport and hostname only*, not the auth model or the app.

## 2. Architecture (decided)

```
┌─ Android app ────────────────────────────┐
│  Kotlin shell                            │
│   ├── SharedPreferences: endpoint IDs    │
│   ├── WebView (one Profile per endpoint) │
│   └── loads http://…localhost:PORT/      │
│              │                           │
│  Rust cdylib (JNI / uniffi)              │
│   ├── hyper server on 127.0.0.1:0        │
│   └── iroh Endpoint ──── QUIC ───────────┼──▶ server's iroh endpoint
└──────────────────────────────────────────┘                │
                                              hyper service (shared)
                                                            │
                                              same service later also on TCP+TLS
```

The WebView believes it is talking to an ordinary HTTP origin on loopback. Therefore
cookies, caching, redirects, relative URL resolution, and form handling are **the
browser's job, not ours**. This is the single most important property of the design.

### Components

**Rust cdylib** — one library, two responsibilities:
- an `iroh::Endpoint`
- a `hyper` server bound to `127.0.0.1:0` that forwards each inbound HTTP request over
  an iroh stream and streams the response back

Public FFI surface, deliberately tiny:
- `start(endpoint_id: String) -> u16` (returns the bound port)
- `stop()`

**Kotlin shell** — roughly 50–100 lines:
- text field for the endpoint ID on first run; persisted in `SharedPreferences`
- calls `start()`, then `webView.loadUrl("http://<label>.localhost:$port/")`
- `javaScriptEnabled = true`, `domStorageEnabled = true`
- menu action to switch/add/remove endpoints
- `saveState`/`restoreState` so relaunch returns to the last page rather than the index

## 3. The wire contract

This is the whole spec. Anything implementing it gets this app as a client for free.

- **ALPN:** a single agreed string, e.g. `x-http/1.1` (see open questions — prefer an
  existing community ALPN if one exists, for interoperability).
- **Framing:** one bi-directional QUIC stream per HTTP request/response. Client writes
  raw HTTP/1.1 request bytes, then half-closes (`finish()`). Server writes raw HTTP/1.1
  response bytes, then finishes.
- **No keep-alive semantics needed upstream.** Streams are cheap in QUIC; one per
  request avoids head-of-line blocking and any pipelining ambiguity. The proxy may
  still speak keep-alive downstream to the WebView.
- **Pass bytes through, don't re-model them.** `Content-Length`, `Transfer-Encoding:
  chunked`, `Set-Cookie`, `Range`, redirects — all opaque to the proxy.

**Server side:** expose one `hyper` service behind two front ends — an iroh acceptor
today, TCP + TLS later. If the current server uses a custom RPC shape over iroh rather
than HTTP bytes, change that first; otherwise the translation gets written twice.

## 4. Auth model

Do **not** invent a transport-specific auth scheme.

- `POST /login` validates username + password (argon2) and returns a session token in
  the JSON body, **and** sets it as a cookie
- accept **either** `Authorization: Bearer <token>` **or** `Cookie: session=<token>`,
  both validated against one server-side session table
- cookie attributes: `HttpOnly`, `SameSite=Lax`, `Secure`, and an **explicit `Max-Age`**
  (e.g. 30 days) — see §5, session cookies do not survive app restart
- server-side session expiry remains authoritative regardless of cookie lifetime

This works unchanged on loopback today and on a real domain later. Bearer also means no
CSRF surface during the phase where the API is called programmatically.

Existing API keys become the "password" slot: exchange the key for a session token
rather than replaying the key on every request, so what's stored on the device is
revocable and expiring.

**Free bonus:** iroh connections are mutually authenticated by public key, and an
endpoint ID cannot be impersonated. The server can allowlist client endpoint IDs as a
strong outer layer with essentially no code. Keep it separate from the HTTP session
layer so it can be dropped at migration.

## 5. Cookie behaviour on Android WebView — read this carefully

These caused most of the design decisions. Getting them wrong produces bugs that look
like "random logouts" and "sessions leaking between servers".

1. **The cookie jar is per-app, not per-WebView.** `CookieManager.getInstance()` is a
   process-wide singleton; storage is in the app's private data dir. Chrome and other
   apps see nothing. Every WebView in *this* app shares one jar by default.

2. **Session cookies do not survive process death.** A cookie with no
   `Expires`/`Max-Age` is gone when the app is killed. Fix: explicit `Max-Age`
   server-side, plus `CookieManager.getInstance().flush()` in `onPause` (persistent
   cookies are written lazily and an abrupt kill can lose them).

3. **Cookies key on host, and every endpoint is `localhost`.** The endpoint ID appears
   nowhere in the jar. Switching endpoints without action would send server A's session
   cookie to server B. Two mitigations, use both:
   - **`Profile` API** (androidx.webkit, WebView 113+): one profile per endpoint ID →
     separate cookies, localStorage, and cache, isolated by the framework.
   - **Distinct hostnames:** load `http://<endpoint-id-prefix>.localhost:PORT/`.
     Chromium resolves `*.localhost` to loopback without DNS, so this yields a distinct
     cookie jar *and* a distinct password-manager/autofill origin per endpoint, and the
     proxy can read the `Host` header to route. **Verify this on real WebView before
     committing** (see open questions); fall back to `127.0.0.1` + profiles.
   - On explicit endpoint change/removal, call `removeAllCookies()` (and clear other
     WebView storage for that profile) regardless. This also gives the desired "logged
     out even when switching back to the same backend" behaviour.

4. **Ports are not part of the cookie key.** A fresh random proxy port each launch is
   therefore harmless. It also means port alone can never isolate two endpoints.

5. `http://127.0.0.1` and `http://*.localhost` are **trustworthy origins**, so secure
   contexts, `crypto.subtle`, and even `Secure` cookies work normally.

## 6. Rejected alternatives, and why

Kept so nobody re-litigates these.

**Static wrapper page + iroh compiled to WASM + service worker.** Technically possible.
Rejected because: iroh in browsers is relay-only (browsers can't send UDP) and ships no
NPM bundle; service workers are killed when idle, taking the iroh endpoint with them;
`Set-Cookie` on a SW-synthesised `Response` is *not* fed into the browser cookie jar and
is filtered from the Headers API, so you must hand-roll a cookie jar in IndexedDB; SW
scope on `user.github.io/repo/` breaks server-absolute paths; the first navigation and
hard reloads bypass the SW; and you end up owning redirects, content types, and `Range`
requests — i.e. reimplementing the boring parts of a browser.

**Overriding `window.fetch` and injecting HTML.** Doesn't work: `<img>`, `<link>`,
`<script>`, and fonts never go through `fetch`, and `innerHTML` doesn't execute injected
scripts. Would require inlining every asset.

**`WebViewClient.shouldInterceptRequest` instead of a loopback proxy.** Same defect as
the service worker: `Set-Cookie` on a synthesised `WebResourceResponse` is not reliably
fed into `CookieManager`. The loopback proxy avoids the entire class of problem.

**Passkeys / WebAuthn instead of passwords.** Blocked, not merely harder. In embedded
WebView (`WebSettingsCompat.setWebAuthenticationSupport`, androidx.webkit 1.12.1+) app
mode routes through Play Services FIDO2, so the origin the server sees is
`android:apk-key-hash:…` and a valid `assetlinks.json` must be fetchable by Google at
`https://your-domain/.well-known/assetlinks.json` — i.e. it requires the public domain
we don't have yet. Conditional UI is also unsupported in embedded WebView. Separately,
RP IDs must be domains (never IPs); `localhost` is special-cased and would work in real
Chrome, but credentials bind to RP ID `localhost` forever and would all need
re-registering at migration. Revisit only after the public domain exists.

**Tailscale / WireGuard instead of all of this.** Genuinely the lowest-effort way to get
a phone-readable dashboard: real network path, real TLS hostname, zero client code, and
the final web app can be written today. Rejected only because iroh-as-transport is a
goal in itself here. If that stops being true, this whole app is unnecessary.

**"Press the sensor to log in", if wanted later:** not passkeys — use `BiometricPrompt`
gating a long-lived token in the Android Keystore, then `CookieManager.setCookie()`
before `loadUrl`. ~40 lines, transport-independent.

## 7. Security notes

- **Loopback is shared across apps on Android.** Any installed app can reach the proxy
  port. Mitigate with a random per-session path prefix required on every request, or by
  checking the connecting socket's uid against the app's own via `/proc/net/tcp`
  (Android assigns each app a distinct uid, so this is a real boundary).
- **No `addJavascriptInterface`.** The shell must expose no bridge to page JS.
- **Override `shouldOverrideUrlLoading`** so external `https://` links open in the real
  browser rather than inside the privileged WebView.
- Treat pasted endpoints as untrusted: this is a browser for arbitrary peers, not a
  wrapper around one known-good server.
- Never accept an endpoint ID or credential via a URL query string.

## 8. Build plumbing

- `cargo-ndk`; targets `aarch64-linux-android` (+ `x86_64-linux-android` for emulator)
- Expect the first build to fight over NDK clang and `ring`. On macOS, Apple Clang does
  not support the needed targets — install llvm.org clang via Homebrew.
- `AndroidManifest.xml`: `android.permission.INTERNET`
- **Android-specific iroh gotcha:** iroh's default DNS resolver expects a JNI context to
  be installed. Without one it relies on panic unwinding to fall back to Google's DNS —
  which means a profile with `panic = "abort"` will panic instead. Install the JNI
  context at startup, or configure a resolver explicitly. Check the current
  `iroh::endpoint::Endpoint` docs for the exact API.
- iroh is at 1.x; pin the version and check release notes rather than trusting older
  blog posts or examples (the API was renamed repeatedly pre-1.0 — `NodeId` →
  `EndpointId`, etc.).

## 9. Suggested build order

**Phase 0 — prove the transport without Android.** Cross-compile the proxy for
`aarch64-linux-android`, run it under Termux, open `http://localhost:8080` in Chrome.
Same architecture, none of the SDK/WebView/signing work. If this turns out to be good
enough in daily use, the APK was never a requirement.

**Phase 1 — server.** Refactor to one `hyper` service; add the iroh acceptor with the
agreed ALPN and stream framing; add `POST /login` + session table + dual
bearer/cookie acceptance; serve a minimal HTML dashboard.

**Phase 2 — proxy library.** `start`/`stop` FFI, streaming bodies both directions,
correct handling of chunked responses and `Range`.

**Phase 3 — Kotlin shell.** Endpoint entry, `SharedPreferences`, WebView + settings,
`flush()` on pause, external-link handling.

**Phase 4 — multi-endpoint.** `Profile` per endpoint, `*.localhost` hostnames if
verified, endpoint switcher, cookie clearing on change.

**Phase 5 — polish.** Biometric unlock, `saveState`/`restoreState`, download/upload
handling, connection status UI.

## 10. Open questions to resolve before/while building

1. Does an ALPN for HTTP-over-iroh already exist in the community (check
   `n0-computer/awesome-iroh` — Oku browser, the APT repository web console)? Match it
   rather than inventing one.
2. Does Android System WebView actually resolve `*.localhost` to loopback, and does it
   treat each label as a distinct cookie/autofill origin? Test before designing around
   it.
3. Is `Profile` available on the target WebView versions, and does `Profile` +
   `*.localhost` combine cleanly or redundantly?
4. Confirm whether `CookieManager.setCookie()` honours an `HttpOnly` attribute in the
   attribute string (matters only for the biometric-injection path).
5. Behaviour when the iroh peer is offline or the endpoint ID is malformed — decide on a
   proxy-generated error page vs. a native error state.
6. Relay-only vs. direct path: measure. If hole-punching fails between phone and server,
   all traffic relays through public relays, which are rate-limited.

## 11. Acceptance tests

- Log in; kill the app from the recents screen; reopen → still logged in, on the last
  page viewed.
- Log in to endpoint A; switch to endpoint B → not logged in, and A's cookie is never
  sent to B. Switch back to A → logged out.
- Open Chrome and hit the same loopback port while the app holds a session → not logged
  in (and, if the uid/prefix guard is implemented, refused entirely).
- A page with images, a stylesheet, and a font loads fully with no asset rewriting.
- A large response streams rather than buffering; media seeking (`Range`) works.
- `Set-Cookie` from the server appears in `CookieManager`; `HttpOnly` is respected.
