# passkey-demo

A passkey-only web app for testing the Android app's WebAuthn support. Not part of the
app or its build.

It never learns its own RP ID. The options it sends leave the RP ID out, so the client
uses its own host. Each passkey records the origin it was registered from, and login is
checked against that origin, ignoring the port. Registration is accepted only from
`http://localhost` and `http://*.localhost`. Why, and why that is safe, is in the
docstring of `passkey_demo.py`.

```sh
nix-shell passkey-demo --run 'pytest passkey-demo'                     # unit tests
nix-shell passkey-demo --run 'python passkey-demo/check_chromium.py'   # against real Chromium
nix-shell passkey-demo --run 'python passkey-demo/check_injected_script.py'  # the app's script, in Chromium
nix-shell passkey-demo --run 'python passkey-demo/passkey_demo.py --state /tmp/passkeys.json'
nix-shell passkey-demo --run 'passkey-demo/over-iroh.sh .trial/state'   # behind iroh, for the phone
```

The unit tests drive the server with a software authenticator of the same shape as the
app's. `check_chromium.py` drives it with Chromium's own WebAuthn, through a virtual
authenticator, and covers what only a real browser can show:

- `*.localhost` is a secure context.
- An omitted RP ID becomes the host.
- One site's passkey is never offered on another.
- A port change between registration and login still works.

`check_injected_script.py` runs the app's injected script (`app/src/main/assets/passkeys.js`)
in Chromium, in place of Chromium's own WebAuthn, with the software authenticator behind a
stand-in for the app's bridge. It shows the script takes over before the page's own scripts
run, and that the objects it builds carry a real site's code all the way to py_webauthn.

## The encrypted note

Each account keeps one note that the server cannot read. Unlocking signs in with a PRF
request; the page turns the passkey's PRF result into an AES-GCM key with WebCrypto (HKDF)
and keeps it in memory only. The server stores the ciphertext, the nonce and which
passkey's key made it, nothing more. A note opens only with the passkey that wrote it:
there is no recovery here, by design, since that is the real service's job.

`check_chromium.py` runs this against Chromium's own PRF, and `check_injected_script.py`
through the app's script.

## On the phone

```sh
nix-shell passkey-demo --run 'passkey-demo/over-iroh.sh .trial/state'
```

This serves the demo on a unix socket behind two `iroh-uds-listen` endpoints, `alpha` and
`beta`. That is the same far side that fronts the monitoring platform, built from
sashee/nixos-test at a pinned commit. It is not nixpkgs' `dumbpipe`: that one speaks the
same wire format, but on iroh 0.35, which the app's iroh 1.0 cannot reach.

The endpoints' secret keys are kept in the state directory, because the app derives each
origin, and so each passkey's RP ID, from the endpoint id. Delete the directory to start
over. The README's passkey checks are written against this setup.

## Trying it by hand

Serve it, then open `http://demo.localhost:8080/` in desktop Chrome. Any `*.localhost`
name works, and each one is a separate site with its own passkeys. For a passkey without a
phone or security key, use DevTools → More tools → WebAuthn → "Enable virtual
authenticator environment", then add a ctap2 / internal authenticator with resident keys
and user verification.

The server prints one line per registration and sign-in, with the origin it came from. When
the phone is on the other end of a tunnel, that line is the quickest way to see which
origin the app presented.

`--state FILE` keeps accounts and passkeys across restarts. Without it, a restart forgets
every passkey, while the phone keeps its own copy.
