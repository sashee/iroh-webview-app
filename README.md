# iroh-webview-app

An Android app that browses an ordinary HTTP web app whose only reachable transport is an
[iroh](https://iroh.computer) endpoint — no public IP, no DNS, no TLS certificate.

Paste a ticket, get a browser pointed at whatever HTTP service sits behind it. Pages can
sign in with passkeys whose keys stay in the phone's secure hardware: a fingerprint is the
whole login. It is not specific to any one backend; the immediate one is the monitoring platform on the rpi5 in
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
nix-build -A pageScripts   # the injected page scripts' tests, in Node
```

`nix-shell` gives you `gradle` with the Android SDK, signing variables and `JNI_LIBS_DIR`
already set — use it to regenerate the lockfiles after a dependency change:

```sh
rm gradle/verification-metadata.xml
nix-shell --run 'GRADLE_USER_HOME=$(mktemp -d) gradle --write-locks --write-verification-metadata sha256 testDebugUnitTest assembleRelease'
```

Deleting the file and starting from an empty Gradle home matter: Gradle only adds to the
file, and on a warm cache it leaves out the parent POMs and BOMs the offline build needs.

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

## Endpoints and passkeys

The menu's **Endpoints and passkeys** screen lists every saved endpoint:

- **Each endpoint:** its name and its origin, `http://<label>.localhost:<port>`, with
  buttons to open it, rename it or remove it.
- **Under each endpoint:** its passkeys, with the account, when it was created and where
  the key is kept ("in the StrongBox security chip" on a Pixel). Each one can be deleted.
- **Names:** an endpoint the user hasn't named is listed by its label, the same hex its
  origin starts with.
- **Removing an endpoint:** this signs you out of it, but its passkeys stay on the phone,
  listed under "Passkeys without an endpoint". Adding the endpoint again makes them usable
  again.
- **Deleting a passkey:** this removes the phone's key. The server's record of it stays,
  but can never be used.
- **Media plays without a tap:** off by default. On, the endpoint's pages may start media
  on their own, which the notification's buttons need to start the next track while the
  app is hidden. Turn it on only for endpoints you trust.

## Playing in the background

A page that sets `navigator.mediaSession.metadata` and plays is shown as Android shows a
music app: a notification with the page's title and buttons, the same controls on the lock
screen, and the headset's buttons. The app keeps running with the screen off while it
plays. Back on the first page sends the app to the background instead of closing it.
Closing the app, or swiping it away, stops the page and the music. Pages that set no
metadata are unaffected. See DESIGN.md, Media.

## Debugging

```sh
adb logcat -s irohbrowser:V     # both halves log here
```

iroh's own crates are filtered to Warn. At Info they log every path event and every send,
which buries everything else. Raise them in `android.rs` when chasing a transport problem.

A connection the proxy drops shows up in the browser as `ERR_SOCKET_NOT_CONNECTED` with no
explanation, so logcat is the only place the reason exists.

## What the app does not do

- **No passwords.** It is a browser. Whatever authentication the far side does happens in
  the page, in the browser's own cookie jar. The app does hold passkey keys, in the Android
  Keystore, and they sign only after a fingerprint. See DESIGN.md for what a server needs
  to do to use them.
- **No HTTP parsing.** The proxy copies bytes. Chunked encoding, `Range`, `Set-Cookie`,
  redirects and keep-alive work because nothing here is a participant in them.
- **No JavaScript interface.** The page comes from an arbitrary peer. Its four channels
  into the app are for passkeys, for copying text marked sensitive, for showing what it
  plays, and for saving the files it holds. All are restricted to the endpoint's own origin and carry data, not methods.
  `addJavascriptInterface` is never used, as `WebViewConfigTest` asserts.

`minSdk` is 34 and `targetSdk` 37 — this targets one Pixel 6a, not the world.

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
7. On a network whose DNS blocks `iroh.link` (a Pi-hole, or a laptop hotspot with
   `dnsmasq --address=/iroh.link/0.0.0.0`), both a bare endpoint id and a ticket still
   connect: the proxy resolves over DoH and never asks the network's nameservers.

Two open questions can only be answered on a device, and the answers decide whether the
per-endpoint origins work as designed:

- Does Android System WebView resolve `*.localhost` to loopback, and treat each label as a
  distinct cookie and autofill origin?
- Does `Profile` (androidx.webkit, WebView 113+) combine with that or duplicate it?

If the first turns out to be no, the fallback is `127.0.0.1` plus profiles, and the cookie
isolation moves entirely onto the clear-on-switch behaviour that is already there.

### Passkeys

These need a passkey-capable web app behind two endpoints: two `iroh-uds-listen` listeners,
each with its own key, in front of the same service. The service must leave the RP ID out
and check each passkey against the origin it was registered from (DESIGN.md, Passkeys).
Each check depends on the one before it.

7. Create an account on the first endpoint. The fingerprint prompt names that endpoint and
   the account. The server sees `http://<label>.localhost:<port>` as the origin.
8. Sign out, then sign in with a passkey: one fingerprint, no username typed.
9. Start a sign-in and cancel the prompt. The page reports `NotAllowedError` and still
   works.
10. Kill the app and reopen it: still signed in. Sign out and back in: still one
    fingerprint.
11. Switch to the second endpoint and sign in. It is refused with no prompt, because this
    endpoint has no passkey. Create one there, then switch back: the first endpoint still
    signs in as before.
12. Open **Endpoints and passkeys**. Each endpoint lists its own passkey, "in the
    StrongBox security chip · with PRF". Remove the second endpoint: its passkey moves to
    "Passkeys without an endpoint", and the first endpoint's page stays as it was.
13. PRF: on the first endpoint, have the site derive a key from a PRF result (one
    fingerprint) and store something encrypted. The server holds only ciphertext. Reload
    the page, so the key is gone, and derive it again (one fingerprint): the data
    decrypts.
14. Add a fingerprint in Settings, then sign in. It is refused, and the message says
    adding a fingerprint invalidated the passkey. Creating a new one works.

### Port squatting

`adb shell` can listen on a loopback port just as an app can. These checks use it to play
an app that grabs a port the proxy has let go. Each endpoint's port is shown in its origin
on the **Endpoints and passkeys** screen. Use a listener that stays up and logs every
connection; `nc -l` exits after the first one, sometimes before any bytes arrive:

```sh
adb shell "rm -f /data/local/tmp/squat.log; setsid toybox nc -L -p <port> \
  sh -c 'echo == connection >> /data/local/tmp/squat.log; timeout 3 cat >> /data/local/tmp/squat.log' \
  </dev/null >/dev/null 2>&1 &"
adb shell cat /data/local/tmp/squat.log
```

Finish each check with a control, `adb shell "echo probe | toybox nc -w 2 127.0.0.1 <port>"`,
which must show up in the log. That proves the listener was really there.

15. Open endpoint A, then switch to B. Listen on A's port, and press back in the app until
    it closes. The log shows nothing but the probe: the history was dropped after the
    switch, and any request there would be refused anyway.
16. With B open, send the app to the background and run `adb shell am kill
    com.example.irohbrowser`. Listen on B's port, then reopen the app from recents. The
    proxy logs "preferred port … unavailable", the page comes back on another port, and the
    log shows nothing but the probe. (A build from before this fix delivers `GET /` with
    the endpoint's cookies to the listener here.)

### Off the device

The WebView must reach nothing but the tunnel (DESIGN.md, Security). These need a page
behind the endpoint that tries each of the following and prints what happened. Run
`adb logcat -s irohbrowser:V` alongside.

17. On launch, logcat shows `WebView network confined to *.localhost`. The endpoint's own
    pages load as before, and so do their own WebSockets, if they have any.
18. An `<img>` from another host does not load, and logcat shows `refused a request to
    https://<host>`.
19. `new WebSocket("wss://<another host>/")` fails with an `error` event, and `typeof
    RTCPeerConnection` is `"undefined"`.
20. Tap a link to another site: the real browser opens it. A page that sets `location` to
    another site on load, with no tap, stays where it is, and logcat shows `refused a
    navigation to https://<host>`.

### Clipboard

These need a page behind the endpoint with a button that calls
`navigator.clipboard.writeText` (DESIGN.md, Clipboard).

21. Tap the button. The copy preview shows dots instead of the text, and the keyboard's
    clipboard history (Gboard: the clipboard icon) does not list it. Paste it elsewhere:
    the text is intact.
22. Have the page copy on a timer, then open the menu's **Endpoints and passkeys**
    before it fires, or send the app to the background. The clipboard keeps what it had.
23. Long-press text on the page and copy it. It is copied, unmarked, as in any browser.

### Playing in the background

These need a page behind the endpoint that plays audio and sets the Media Session's
metadata and handlers, for example media-store (DESIGN.md, Media).

24. Play a track. A notification shows its title, with play/pause, previous and next as
    the page offers them, and a seek bar. Pressing them acts on the page.
25. Turn the screen off for a few minutes: the music keeps playing, and the lock screen
    shows the same controls. Pause and resume from there.
26. With "Media plays without a tap" off, pause and resume from the notification. Then let
    a track end, or press next while the app is hidden: if the next track does not start,
    turn the switch on for the endpoint and try again.
27. Press back on the page's first screen while it plays: the app goes to the background
    and keeps playing. With nothing playing, back closes the app as before.
28. Swipe the app away from the recents screen: the music stops and the notification goes.
29. Pause and wait ten minutes: the notification goes.
30. Open an endpoint whose pages set no Media Session metadata, such as pw-mgr: no
    notification, and everything works as before.

### Downloads

These need a page behind the endpoint that saves files it holds, through a link to a
`blob:` URL, for example media-store's Download (DESIGN.md, Downloads and uploads).

31. Download a small file. "Saving …" then "Saved … to Downloads" show, and the Files app
    lists it in Downloads, its bytes as uploaded. Download it again: the second is
    numbered, "name (1).ext".
32. Download a large track (hundreds of MB). The page shows how much it has read; the app
    stays responsive while the file is written, and the saved file plays.
33. Start a large download and switch to another endpoint before it is saved: "Could not
    save …" shows, and nothing half written appears in Downloads.
34. A download link to the endpoint's own server (not a `blob:` URL) still goes to the
    system downloader, with its notification, as before.

### Network blocked in the background

35. Open the file picker from a page and wait 10 seconds before picking, then upload
    something large. It goes through at full speed. `adb logcat -s irohbrowser:V` shows
    `network blocked by Android: closing the proxy's connections` and then `network back`.
36. Start a large upload and switch to another app before it finishes: once the block
    comes, about five seconds later, the upload fails with an error rather than hanging.
37. Switch to another app for ten seconds, come back, and save something at once (in
    media-store, Sort again). It saves. If a request does get stuck, `adb logcat -s
    irohbrowser:V` shows `an answer has been owed for …` with each path's QUIC stats, and
    within about half a minute `closing the connection for …, stuck:`, after which the
    request goes through again on a fresh connection.

### The window, back and folders

These follow from `targetSdk` 37 (DESIGN.md, The window and back; Downloads and uploads).

38. Nothing on the page, the settings screen or the ticket field is under the status bar,
    the navigation bar or the gesture handle; the app bar sits below the status bar.
    media-store's player bar is fully above the navigation bar.
39. Tap a field near the bottom of a page: the keyboard pushes the page up, and the field
    stays in view while typing.
40. Back, in turn: from the settings screen it returns to the page; on a page with history
    it goes back in it; on the first page while something plays the app goes to the
    background and keeps playing; with nothing playing it leaves the app.
41. In media-store's Add music, Or a folder → Choose Folder: the system's folder picker
    opens, and the preview lists the folder's files under the folder's name, its subfolders
    kept.
