# iroh-webview-app

An Android app that browses an ordinary HTTP web app whose only reachable transport is an
[iroh](https://iroh.computer) endpoint — no public IP, no DNS, no TLS certificate.

Paste a ticket, get a browser pointed at whatever HTTP service sits behind it. It is not
specific to any one backend; the immediate one is the monitoring platform on the rpi5 in
[sashee/nixos-test](https://github.com/sashee/nixos-test), reached through the
`mp-tunnel-server` unit.

See [DESIGN.md](DESIGN.md) for how it works and why, and [AGENTS.md](AGENTS.md) if you are
an agent working on it.

## Build

```sh
nix-build                  # rust tests + cross-compiled libraries + robolectric tests + signed apk
adb install -r ./result/app-release.apk
```

The release signature is deterministic (keystore committed under `signing/`), so
`adb install -r` upgrades in place and preserves existing app data — including the cookie
jar, which is the live session.

Partial builds, when you want one half:

```sh
nix-build -A rust          # the proxy crate's own test suite, on the host
nix-build -A nativeLibs    # the cdylibs for arm64-v8a and x86_64
```

`nix-shell` gives you `gradle` with the Android SDK, signing variables and `JNI_LIBS_DIR`
already set — use it to regenerate the lockfiles after a dependency change:

```sh
nix-shell --run 'gradle --write-locks --write-verification-metadata sha256 testDebugUnitTest assembleRelease'
```

That step needs network. `nix-build` itself does not: Gradle resolves from a vendored
Maven repository and Cargo from a vendored crate directory.

## Getting a ticket

On the machine running the far side:

```sh
iroh-ssh-ticket            # prints the endpoint ticket for the configured secret
```

Both forms the tooling prints are accepted, as is a bare 64-character endpoint id. Prefer
a ticket that carries relay urls: it lets the phone dial without depending on DNS
discovery, which is the flakiest part of the path on Android.

## Debugging

```sh
adb logcat -s irohbrowser:V     # the Rust half logs here
```

A connection the proxy drops shows up in the browser as `ERR_SOCKET_NOT_CONNECTED` with no
explanation, so logcat is the only place the reason exists.

## What the app does not do

- **No credentials.** It is a browser. Whatever authentication the far side does happens
  in the page, in the browser's own cookie jar.
- **No HTTP parsing.** The proxy copies bytes. Chunked encoding, `Range`, `Set-Cookie`,
  redirects and keep-alive work because nothing here is a participant in them.
- **No JavaScript bridge.** The page comes from an arbitrary peer and gets no way into
  the app process. Asserted in `WebViewConfigTest`.

`minSdk` is 34 — this targets one Pixel 6a, not the world.

## On-device checks

The test suites cover everything reachable off a phone. These are the ones that are not,
and they are worth walking after a change to the WebView or the endpoint switching:

1. Log in; kill the app from the recents screen; reopen → still logged in, on the page you
   were on. Needs the server to set `Max-Age` or `Expires` on the session cookie: a cookie
   with neither dies with the process no matter what the app does.
2. Log in to endpoint A; switch to B → not logged in, and A's cookie is never sent to B.
   Switch back to A → **still logged in**, because each endpoint has its own origin.
3. Open Chrome and hit the same loopback port while the app holds a session → you reach the
   server, but **not** logged in. The port itself cannot be closed to other apps (DESIGN.md);
   what protects the session is that the cookie is in the WebView's own jar.
4. A page with images, a stylesheet and a font loads fully, with no asset rewriting.
5. A large response streams rather than buffering; media seeking works.
6. `Set-Cookie` from the server appears in `CookieManager`, and `HttpOnly` is respected.

Two open questions can only be answered on a device, and the answers decide whether the
per-endpoint origins work as designed:

- Does Android System WebView resolve `*.localhost` to loopback, and treat each label as a
  distinct cookie and autofill origin?
- Does `Profile` (androidx.webkit, WebView 113+) combine with that or duplicate it?

If the first turns out to be no, the fallback is `127.0.0.1` plus profiles, and the cookie
isolation moves entirely onto the clear-on-switch behaviour that is already there.
