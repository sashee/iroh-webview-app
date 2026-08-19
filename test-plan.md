# Test Plan

What is covered, where, and what is deliberately not.

Everything below runs inside `nix-build`, with no network. Two suites:

- **Rust**, `nix-build -A rust` — 40 tests. Unit tests beside each module, plus end-to-end
  transport tests over a real iroh pair on loopback.
- **Kotlin/Robolectric**, run by the APK build — 77 tests.

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
- a request body is forwarded
- an 8 MB response streams rather than buffering, and arrives byte-exact
- six concurrent connections stay isolated, with all requests sent before any response is
  read
- one browser connection closing does not disturb the others
- an unreachable endpoint produces a 502 rather than a hang
- the origin going away closes the browser connection rather than hanging
- stopping the proxy stops answering

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
- an entry without a name gets a readable default

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
- a rejected ticket says so
- pausing flushes cookies
- our own pages stay in the WebView
- an external link opens the real browser, asserted through the started Intent
- an `intent:` url starts nothing at all
- navigation is refused while nothing is running

### Downloads — `DownloadsTest`

- our own url is rewritten to loopback on the same port, path and query intact
- a url that is not ours is refused, so a page cannot aim the system downloader
- the filename comes from `Content-Disposition`, including the extended form
- the url supplies a name when the header does not
- a path in the filename is stripped — the header is written by the peer
- there is always a name

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
- no JavaScript bridge — asserted against the compiled class, since a call that never
  happens leaves nothing for reflection to find

### Manifest and resources — `ManifestAndResourceTest`

Declarations, which nothing else would catch.

- `INTERNET` is the only permission requested
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
- **Relay versus direct path** is unmeasured. If hole-punching between phone and rpi5
  fails, everything relays through public relays, which are rate-limited.
