# Test Plan

What is covered, where, and what is deliberately not.

Everything below runs inside `nix-build`, with no network. Three suites:

- **Rust**, `nix-build -A rust` — 48 tests. Unit tests beside each module, plus end-to-end
  transport tests over a real iroh pair on loopback.
- **Kotlin/Robolectric**, run by the APK build — 254 tests.
- **The injected passkey script**, `nix-build -A passkeyScript` — 29 tests, in Node.

Excluded on purpose: instrumentation tests, live network against the rpi5, and anything
that needs a real Chromium. Those are the on-device checks in [README.md](README.md).

## Rust

### Ticket parsing and origins — `src/ticket.rs`

- parses a bare endpoint id
- parses a ticket
- a ticket keeps its relay urls (this is what keeps DNS off the critical path)
- surrounding whitespace is ignored — these arrive by copy-paste
- empty input is reported as empty, distinctly from unrecognised
- garbage is rejected
- a truncated endpoint id is rejected
- an endpoint id with a bad character is rejected
- the label is a stable pure function of the id, and relay urls do not change it
- the label is a valid DNS label
- distinct endpoints get distinct labels
- the proxy url is a loopback origin
- the preferred port is stable for an endpoint, and ignores relay urls
- the preferred port avoids the privileged and ephemeral ranges
- an endpoint's identity is its label and preferred port; a ticket and its bare id have
  the same one; garbage has none
- distinct endpoints mostly get distinct ports

### The error page — `src/gateway.rs`

- starts with a 502 status line
- closes the connection
- is not cached — a cached error page would outlive the endpoint coming back
- `Content-Length` matches the body
- the reason reaches the page
- markup in the reason is escaped, and escaping stays length-consistent
- headers end with exactly one blank line

### Lifecycle — `src/runtime.rs`

- a malformed ticket fails before anything is bound
- an empty ticket is reported as empty
- starting binds a loopback port that accepts
- the label identifies the endpoint
- stopping releases the port
- the bound port is the endpoint's preferred one
- a taken preferred port falls back instead of failing

### Forwarding with a stall limit — `src/pipe.rs`

- everything is copied until the reader ends
- a writer that takes nothing fails the copy once the stall limit passes
- a slow writer that keeps taking is not a stalled one
- a reader that sends nothing for a while is not a stall
- the endpoint owes an answer from the last bytes sent until any come back
- copying notes every write

### Answers that don't come — `src/proxy.rs`

- losses count on the paths open before and after

### Transport, end to end — `tests/transport.rs`

Browser → proxy → iroh → far-side listener → origin server, and back. Each of these is a
property the design promises, and each passes for the same reason: nothing parses HTTP.

- a request reaches the origin and the response comes back
- request headers arrive verbatim — no added, dropped, reordered or rewritten headers
- `Set-Cookie` reaches the browser untouched, including `HttpOnly` and `Max-Age`
- keep-alive carries several requests over one connection
- a chunked response is forwarded as chunks, not silently de-chunked
- a `Range` request and its `206` pass through with `Content-Range` intact
- a redirect is passed through rather than followed
- a slow answer on a connection that loses nothing is waited for
- a request body is forwarded
- an 8 MB response streams rather than buffering, and arrives byte-exact
- six concurrent connections stay isolated, with all requests sent before any response is
  read
- one browser connection closing does not disturb the others
- an unreachable endpoint produces a 502 rather than a hang
- the origin going away closes the browser connection rather than hanging
- stopping the proxy stops answering
- closing the connections (what a network block does) ends those open, and connections
  made afterwards work, more than one request each

## Kotlin

### The saved-state model — `EndpointsTest`

The selection rules, which decide which server the app shows.

- a fresh list is empty with nothing selected
- adding selects what was added
- adding a ticket already saved selects it instead of duplicating; the saved name wins
- selecting moves the selection; out of range changes nothing
- removing an earlier endpoint keeps the same one selected
- removing a later endpoint keeps the same one selected
- removing the selected endpoint selects the one that took its place
- removing the last endpoint selects the new last one
- removing the only endpoint leaves nothing selected
- removing out of range changes nothing
- renaming changes only the name; out of range changes nothing
- state round-trips through JSON, including the empty state
- nothing saved reads as empty (null, empty, whitespace)
- unreadable JSON reads as empty rather than throwing
- entries without a ticket are dropped
- a selection pointing past the end reads as no selection
- an entry without a name has none, and is saved without one
- the old default name (the ticket cut to twelve characters) reads as no name, but a name
  that is the whole of a short ticket is kept
- renaming trims, and renaming to blank goes back to no name
- the display name is the user's, else the label, else the ticket
- autoplay is off until turned on, for that endpoint only; it round-trips through JSON,
  is saved only when on, and endpoints saved before it existed read as off

### Persistence — `EndpointStoreTest`

- nothing saved reads as empty
- saved state survives a reload
- a second store sees what the first wrote
- `update` applies a change and persists it, and can empty the list

### Origins — `OriginsTest`

The browsing-direction security boundary.

- the url is a loopback origin naming the endpoint
- our own pages are ours, at the root and deep
- a different label on the same port is **not** ours
- our label on a different port is not ours
- `https` on our own host is not ours
- a host that merely ends with ours is not ours
- bare `127.0.0.1` and `localhost` are not ours
- null and nonsense are not ours
- our own pages stay in the WebView
- external `http`/`https` go to the real browser
- `intent:`, `file:`, `content:`, `javascript:`, `data:`, `tel:`, `market:` are refused
- empty and null navigations are refused
- loopback is every way of naming this device — `localhost` and names under it, 127/8,
  `0.0.0.0`, `[::1]` and its IPv4-mapped forms — and nothing else
- the running proxy's origin may be requested at any path; the same host on another port,
  other loopback origins, and anything loopback while nothing runs may not
- requests off the device are left alone

### The activity — `MainActivityBehaviorTest`

Driven through a fake proxy that records call order.

- first run shows the ticket field and starts nothing
- connecting starts the proxy and loads its origin
- a pasted ticket is trimmed before it is saved
- an empty ticket is refused without starting anything
- a saved endpoint is reopened on the next launch
- switching stops the old proxy **before** starting the new one
- relaunching clears nothing — the regression that made every restart ask for the password
- switching away and back clears nothing, so both sessions survive
- adding an endpoint clears nothing
- each endpoint gets its own origin, differing in **hostname** not just port
- removing the last endpoint returns to the ticket field
- removing an endpoint clears that origin and only that origin
- removing an endpoint leaves the remaining one logged in
- removing one of several opens the one that remains
- a failed start shows an error and keeps the endpoint saved
- a rejected ticket says so, and is not saved
- pausing flushes cookies
- our own pages stay in the WebView
- an external link opens the real browser, asserted through the started Intent
- an `intent:` url starts nothing at all
- navigation is refused while nothing is running

### No requests to a squatted port — `LoopbackGuardTest`

Cookies ignore ports, so a request to the right host on the wrong port hands the session
to whatever app is listening there.

- requests to the running proxy go through
- the same host on another port is refused before anything is sent, with a response that
  is not cached
- after a switch, the previous endpoint's origin is refused
- other loopback addresses are refused, and the outside world is not
- with nothing running, every loopback request is refused
- service worker requests are checked the same way, and the check is removed with the
  activity
- switching drops the history once the new endpoint's page has loaded, and a late callback
  for the page being left does not count
- a restored page starts loading only after the proxy runs and both bridges' scripts are in
  (restored first, its request was refused, which the Pixel showed and Robolectric could not)
- a restored page is kept when the proxy got its port back, and replaced by the front page
  — history dropped — when it did not

### Downloads — `DownloadsTest`

- our own url is rewritten to loopback on the same port, path and query intact
- a url that is not ours is refused, so a page cannot aim the system downloader
- the filename comes from `Content-Disposition`, including the extended form
- the url supplies a name when the header does not
- a path in the filename is stripped — the header is written by the peer
- there is always a name
- a name a page gives the download bridge loses any folder and control characters; one
  that is no name becomes "download"; a long one is cut to 240 bytes of UTF-8, keeping its
  extension and whole characters

### WebView configuration — `WebViewConfigTest`

Several assert an absence: these are the settings most likely to be turned on by accident
later.

- JavaScript is enabled
- DOM storage is enabled
- file and content access are off
- file urls cannot reach other origins
- mixed content is never allowed
- pinch-to-zoom is enabled, and the on-screen zoom buttons are hidden
- pages are laid out the way a browser lays them out
- no JavaScript interface: `addJavascriptInterface` is checked against the compiled
  classes of the activity, the bridge installer and the media playback, since a call that
  never happens leaves nothing for reflection to find
- the passkey, clipboard, media session and download scripts each look for the bridge
  object the app installs, by the same name
- media waits for a tap by default

### Manifest and resources — `ManifestAndResourceTest`

Declarations, which nothing else would catch.

- the permissions are exactly `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`,
  `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; no `POST_NOTIFICATIONS`
- the playback service is the only service, is not exported, and is of the media playback
  type
- the manifest names our Application class, and it is an Application
- the manifest does not enable cleartext traffic globally
- backups are off
- the launcher activity is the only exported component
- no content providers are exported
- cleartext is permitted for loopback and refused everywhere else
- data extraction rules exclude everything and include nothing
- the launcher activity handles MAIN and LAUNCHER
- no intent filter claims http links — external links must not come straight back
- the strings the UI needs exist

### Passkey bytes — `WebAuthnTest`

The encodings an authenticator writes, field by field. When the whole-ceremony tests fail,
these say which field is wrong.

- CBOR integers, strings and maps match RFC 8949's own examples, including long lengths
- `clientDataJSON` is the specification's serialisation: member order, no whitespace,
  `crossOrigin:false`, and JSON escaping
- authenticator data is the RP ID hash, the flags and a zero counter
- attested credential data is a zero AAGUID, the id length, the id and the key
- the COSE key is EC2 / ES256 / P-256 in canonical order, and its coordinates are always
  32 bytes, whatever `BigInteger` makes of them
- the "none" and "packed" attestation objects, byte for byte
- what is signed is authenticator data plus the client data hash
- base64url has no padding, tolerates it on input, and refuses garbage
- a PRF input is salted with the specification's prefix, and a result is HMAC-SHA-256 of
  the salted input

### Passkey requests — `PasskeyRequestsTest`

The browser's rules. The page can post to the bridge without the script, so every field is
read as if from anyone.

- a creation and a sign-in request are read in full; an RP ID of JSON null is no RP ID
- missing or malformed members are a `TypeError`, including a user id over 64 bytes
- an RP ID left out is the page's host, and naming the host is accepted in any case
- any other RP ID is a `SecurityError`, including the parent `localhost` that a browser
  would allow
- ES256 is accepted when offered, or when nothing is offered
- candidates are this RP ID's passkeys, narrowed by the server's allow list
- "already registered" needs both this RP ID and an excluded id
- a registration asking for PRF says so; PRF inputs are read, both of them; per-passkey
  inputs are keyed by the canonical credential id
- per-passkey inputs without an allow list are a `NotSupportedError`; for a passkey not on
  it, or keyed by something that is not base64url, a `SyntaxError`; inputs without a
  `first` are a `TypeError`

### Passkey ceremonies — `PasskeyAuthenticatorTest`

Whole registrations and sign-ins with software keys, verified by webauthn4j playing the
server. The verifier really does check "packed" attestation, unlike
`createNonStrictWebAuthnManager()`.

- a registration verifies as a user-verified ES256 passkey for the page's own host, with
  the proxy's origin (port included) in the client data
- asking for attestation gets packed self attestation, which verifies
- the response is what a browser's `toJSON()` would give, `credProps` included
- verifying against the wrong origin or challenge fails, which shows the verifier is not
  lenient
- the passkey is remembered under the host, for the user
- prompts name the purpose, the account and the endpoint
- a sign-in verifies against the registered passkey, and returns the user handle
- refused **before any prompt**:
  - another RP ID
  - a server without ES256
  - an account that already has this phone's passkey
  - another endpoint's page
  - an allow list naming nothing here
  - a phone that cannot make a key
  - a declined account choice
- with two accounts the user chooses; the server's allow list skips the chooser
- refusing or withdrawing a prompt answers `NotAllowedError`. During registration it also
  leaves no key behind and nothing stored.
- an invalidated key is forgotten, with a message that names the fingerprint as the cause

### PRF — `PasskeyPrfTest`

The expected results are computed in the test from the fake vault's raw PRF key, not by the
code under test.

- registering with PRF makes a PRF key and says it is enabled; the touch goes to the PRF
  key and the signing key gets the window; the registration still verifies
- registering without PRF keeps one strict key and says nothing about PRF; a phone that
  cannot make a PRF key registers without it and says so; refusing the prompt leaves no
  keys
- a sign-in returns the result the specification defines, and still verifies
- both inputs are answered with one touch
- the same passkey and input always give the same result; a result does not depend on
  which slot the input came in (what rotation relies on); another input or another passkey
  gives another result
- per-passkey inputs take precedence, and inputs for another passkey leave this one with
  the general ones
- a passkey without PRF answers with no results and still signs in; a sign-in that does
  not ask hears nothing about PRF
- the signing key of a PRF passkey signs only on a fresh touch
- an invalidated PRF key forgets the whole passkey
- another site's page gets no PRF from this passkey

### The bridge — `PasskeyBridgeTest`

- replies carry the request's id
- a message from another origin, or the same host on another port, is a `SecurityError`
  with no prompt
- one ceremony at a time: a second is refused and the first still completes
- the page's cancel and the app's cancel both withdraw the prompt; cancelling another id
  does not
- malformed options and unknown request types are a `TypeError`; non-requests get no reply

### Where the bridge is offered — `PasskeyWiringTest`

- to the running endpoint's exact origin, and to nothing before one is running
- a switch withdraws it from the old origin **before** offering it to the new one, and
  withdraws a prompt the old page raised
- a failed start offers nothing; removing the endpoint or finishing the activity withdraws
  it
- a page's request reaches the authenticator for the endpoint on screen, and the prompt
  shows the endpoint's saved name
- a WebView without the bridge's features still browses

### The clipboard bridge — `ClipboardBridgeTest`

- a page's text is copied, and the reply carries its id
- another origin, the same host on another port, or a page not on screen is refused, and
  nothing is copied
- a message without text is a `TypeError`; non-requests get no reply
- a copy the system refuses is reported to the page as `NotAllowedError`
- the clip is written with `EXTRA_IS_SENSITIVE` set

### Where the clipboard bridge is offered — `ClipboardWiringTest`

- to the running endpoint's exact origin, moved on a switch, and to nothing before one is
  running; finishing the activity withdraws it
- a page's copy lands on the clipboard marked sensitive
- a page behind the settings screen, or in a window without focus, copies nothing

### The media session bridge — `MediaSessionBridgeTest`

- what a page plays is read: titles, playing or not, its buttons, and its position and
  duration in milliseconds
- another origin, or the same host on another port, is not heard
- no metadata means nothing plays; anything that is not a report means nothing at all
- titles are cut to 200 characters and lose control characters; values of the wrong type
  are empty or unknown, and the position stays within the duration
- a button goes back by its web name, and a seek in seconds
- play and pause are always offered, the rest only with the page's handler; seeking also
  needs a duration

### Network blocks — `NetworkBlockWatchTest`

- the first report, the state at registration, is only remembered
- a block, and the network coming back, are each passed on to the proxy once
- starting blocked, the network coming back is a change
- registering listens to the default network's blocked status

### Where the media session bridge is offered — `MediaWiringTest`

- to the running endpoint's exact origin, moved on a switch
- a page that never reports shows nothing
- what a page plays is shown, and the system's buttons come back to that page as
  commands; another origin's report is ignored
- a report without metadata, a switch, a new page in the main frame and closing the app
  each end it
- back on the first page sends the app to the background while something plays, and
  closes it otherwise, as before
- media needs a tap unless the endpoint allows otherwise, and that follows a switch

### The download bridge — `PageDownloadsTest`

- a page's file is written piece by piece, each piece answered with the bytes written so
  far, and saved; the page is told the name it was saved as, a taken one numbered; an
  empty file is saved; the name is cleaned before anything is made
- another origin is not heard, for text or for pieces; messages that make no sense, and
  pieces with no file being saved, are ignored
- a second file while one is saved is refused, and the first goes on
- each way a file ends unsaved discards what was written: more bytes than it has, a piece
  larger than the bridge takes, a piece before the last was written (one in flight at
  most, so a page cannot fill the app's memory), an end before the whole file came, the
  page giving up, the page going away (also while the file is still being made), a file
  that cannot be made, a write that fails

### A page's file inputs — `FileChooserTest`

- a file input opens the document picker, for several files of the types it accepts
  (blank types dropped), and the page gets every file picked
- a folder input (`MODE_OPEN_FOLDER`) opens the folder picker, and the page gets the folder
- a pick cancelled, or superseded by another, answers the page with nothing; so does
  closing the app while a page waits — an unanswered input stays disabled for good

### Where the download bridge is offered — `DownloadWiringTest`

- to the running endpoint's exact origin, moved on a switch
- a page's file is saved in Downloads, through the main thread's queue
- a switch, a new page in the main frame and closing the app each discard a file half
  saved

### The settings screen's model — `SettingsTest`

Plain JUnit: it is a pure function of the saved state.

- every endpoint is listed in order, by its name or else its label
- the open endpoint's origin comes from the running proxy, the others' from their
  preferred ports; a selected endpoint that is not running is not open
- passkeys are listed under the endpoint whose host they belong to
- a passkey whose endpoint is gone is listed on its own
- a ticket the app cannot read has no origin and claims no passkeys
- one endpoint saved twice lists its passkeys under both, and they are not orphans
- a passkey shows the display name, else the user name, and where its key lives
- a passkey with PRF is shown as such

### The settings screen — `SettingsScreenTest`

Driven through the menu, the buttons and the dialogs.

- the menu opens a list of every endpoint, the open one marked, each with its origin
- an unnamed endpoint is listed by its label
- passkeys are listed under their endpoint, with where the key lives
- removing another endpoint asks first, then forgets only its site data, at its
  preferred port, and **does not restart** the open one; declining removes nothing
- removing the open endpoint opens the next and stays on the list
- a removed endpoint's passkeys are listed as without an endpoint
- deleting a passkey asks first, then forgets it and its key; declining keeps it
- a rename shows in the list and in the very next fingerprint prompt
- opening another endpoint switches to it; opening the open one shows its page again
  without reconnecting
- the app bar says where you are, and its back arrow leaves the list
- back from the list, and from "Add endpoint", returns to the page
- the autoplay switch is off by default; turning it on saves it and applies to the open
  page at once, and turning it on for another endpoint leaves the open page as it is

### Persistence — `PasskeyStoreTest`

- saved passkeys read back the same; the key alias comes from the credential id
- a corrupt store reads as empty; unreadable entries are skipped and the rest kept
- whether a passkey has PRF is kept, and old entries have none; forgetting a passkey
  deletes both of its keys

## The injected script — `app/src/test/js/passkeys.test.mjs`

In Node, against a fake bridge and a fake `navigator`.

- `PublicKeyCredential` and the response classes are installed, and cannot be
  constructed by the page
- `create` and `get` relay options with binary fields as base64url; typed-array views
  relay only the bytes they cover
- an RP ID the page leaves out stays out; one it names is passed on for the app to check
- replies become `PublicKeyCredential`s with ArrayBuffers, working getters, and a
  `toJSON()` that returns the app's JSON unchanged
- error replies reject with the exception a browser would throw; replies are matched to
  requests by id; stray or non-JSON replies are ignored
- aborting rejects at once and tells the app to cancel; an already-aborted signal never
  reaches the app
- conditional mediation is a `TypeError`, and missing options are a `TypeError`; neither
  reaches the app
- other credential types go to the browser's own implementation
- `parseCreationOptionsFromJSON` / `parseRequestOptionsFromJSON` decode base64url
- without the bridge, the script changes nothing
- PRF: a registration says whether PRF is wanted; inputs are relayed as base64url, both of
  them and per passkey; a sign-in without PRF sends none; inputs without a `first` are a
  `TypeError` that never reaches the app
- PRF results reach the page as ArrayBuffers, `toJSON()` keeps them as sent, and an answer
  without results stays without; registration reports whether PRF is enabled;
  `extension:prf` is a capability; JSON options with PRF inputs parse into the binary form

## The clipboard script — `app/src/test/js/clipboard.test.mjs`

In Node, against a fake bridge and a fake `navigator.clipboard`.

- `writeText` hands the text to the app and resolves only once the app has copied it
- refusals reject with the exception a browser would throw; replies are matched to writes
  by id; stray or non-JSON replies are ignored
- `writeText` takes any value as a string, but no argument is a `TypeError` that never
  reaches the app
- `write` of one item with `text/plain` copies its text through the app; any other write
  goes to the browser's own `write`; a `write` of something not a list rejects
- without the bridge, or without a clipboard, the script changes nothing

## The media session script — `app/src/test/js/media-session.test.mjs`

In Node, against a fake bridge, a clock, a document whose media events the test fires,
and a recording `navigator.mediaSession`.

- a page that never sets metadata sends nothing, even while it plays
- what the page sets is sent together, once its code has run, with only the buttons the
  app knows
- the WebView's own session still gets every call, and its argument checks still throw;
  without one, the page gets one, and `MediaMetadata`
- the position is the page's, moved on while it plays and not while paused; without one,
  the playing media element's
- the same state is not sent twice; leaving the page sends "nothing plays", coming back
  from the back-forward cache sends it again
- a button from the app calls the page's handler with what a browser passes; without a
  handler, play and pause act on the media element; removed handlers are not called or
  offered; garbage is ignored
- without the bridge, or in a frame, the page is left as it was

## The download script — `app/src/test/js/downloads.test.mjs`

In Node, against fake links, fake `blob:` URLs and an app that answers as
`PageDownloads` does.

- a link's `click()` sends the file in 1 MiB pieces, each once the last was answered,
  then the end, and the browser downloads nothing; a link in the page, clicked, is saved
  the same way; an empty file is saved
- a URL revoked as soon as `click()` returns still saves; a `blob:` URL the page made
  elsewhere is fetched; one that is gone sends nothing
- a click the page cancels, a link without `download` and a link to anything but a blob
  are left to the browser
- a refused file sends nothing more; files go one at a time, in the order clicked; a file
  that cannot be read on is abandoned, and the app told
- without the bridge, the page is left as it was

## Gaps, stated rather than hidden

- **`*.localhost` resolution and cookie keying** are Chromium behaviour. Robolectric's
  WebView is a stub, so the tests assert we *build* distinct origins, not that the browser
  treats them as distinct. Device check.
- **`Profile` (multi-profile)** is not implemented; the hostname labels plus clear-on-switch
  do the isolation. Adding it needs a device to verify against.
- **The loopback port is unguarded**, and that is not a gap in the tests but in the
  platform: see DESIGN.md. There is nothing left to test here — the check was removed once
  a device showed it could never pass.
- **The JNI layer** (`rust/src/android.rs`) has no tests. It compiles only for Android and
  contains no judgement — every decision it could make is delegated to a module that is
  tested on the host. It is verified by the app running.
- **The platform half of passkeys** has no automated tests: Keystore and StrongBox key
  generation, per-use authentication, `BiometricPrompt`, and the WebView's message
  listener and document-start script. Robolectric has none of them. `AndroidPasskeys.kt`
  is kept thin for that reason. The passkey checks in README.md cover it.
- **The platform half of playback** has no automated tests: Android's `MediaSession`, the
  notification, the foreground service, and the renderer priority. `Playback.kt` is kept
  thin for that reason; README.md's playback checks cover it.
- **The window and back** from `targetSdk` 35 and 36: whether anything is drawn under the system
  bars or the keyboard, and the system delivering back to the dispatcher, are the
  platform's. The tests drive back through `onBackPressed`, which reaches the same callback;
  README.md's checks cover the rest.
- **The platform half of downloads** has no automated tests: the Downloads collection
  (`MediaStoreDownloads.kt`, kept thin), and the WebView delivering an `ArrayBuffer`.
  README.md's download checks cover it.
- **Whether a clip marked sensitive is hidden** is the system's and the keyboard's
  behaviour. The tests check that the flag is set, and README.md's clipboard checks that
  it does what it should.
- **The script and the app** are tested apart and meet first on the phone. The JSON
  between them is pinned on both sides: the app's output by webauthn4j, the script's
  handling of it by Node.
- **Relay versus direct path** is unmeasured. If hole-punching between phone and rpi5
  fails, everything relays through public relays, which are rate-limited.
