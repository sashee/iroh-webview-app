"""The demo against real Chromium, with a virtual authenticator.

The unit tests prove the server against an authenticator written to match it.
This proves it against an implementation nobody here wrote: Chromium's own
WebAuthn, which fills in the RP ID when the options leave it out, scopes
passkeys to it, and treats `*.localhost` as a secure context. If the app's
emulation later disagrees with the server, this is what says which one is
wrong.

The demo is served on two ports sharing one store, so a port change between
registration and login -- which the app's proxy can cause -- is covered too.

    nix-shell passkey-demo --run 'python passkey-demo/check_chromium.py'
"""

import shutil
import sys
import threading

from playwright.sync_api import sync_playwright

from passkey_demo import PAGE_PATH, State, Store, server


def serve_on_two_ports() -> list[int]:
    store = Store(State(), None)
    page = PAGE_PATH.read_bytes()
    servers = [server(store, page, "127.0.0.1", 0) for _ in range(2)]
    for httpd in servers:
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return [httpd.server_address[1] for httpd in servers]


# Runs a demo function in the page and reports a rejection as data, so a
# NotAllowedError can be asserted on rather than aborting the run.
CALL = """async ([name, arg]) => {
  try { return { ok: await window.passkeyDemo[name](arg) }; }
  catch (e) { return { error: e.name, message: e.message }; }
}"""


def main() -> int:
    first, second = serve_on_two_ports()
    alpha = f"http://alpha.localhost:{first}/"
    alpha_elsewhere = f"http://alpha.localhost:{second}/"
    beta = f"http://beta.localhost:{first}/"
    passed = []

    def check(name: str, condition: bool, detail: object = "") -> None:
        if not condition:
            raise AssertionError(f"{name}: {detail}")
        passed.append(name)
        print(f"  ok  {name}")

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=shutil.which("chromium"), args=["--no-sandbox"]
        )
        context = browser.new_context()
        page = context.new_page()
        devtools = context.new_cdp_session(page)
        devtools.send("WebAuthn.enable", {"enableUI": False})
        authenticator = devtools.send(
            "WebAuthn.addVirtualAuthenticator",
            {
                "options": {
                    "protocol": "ctap2",
                    "ctap2Version": "ctap2_1",
                    "transport": "internal",
                    "hasResidentKey": True,
                    "hasUserVerification": True,
                    "isUserVerified": True,
                    "automaticPresenceSimulation": True,
                }
            },
        )["authenticatorId"]

        def call(name: str, arg: object = None) -> dict:
            return page.evaluate(CALL, [name, arg])

        def stored() -> list[dict]:
            return devtools.send("WebAuthn.getCredentials", {"authenticatorId": authenticator})[
                "credentials"
            ]

        try:
            page.goto(alpha)
            check("*.localhost is a secure context", page.evaluate("window.isSecureContext"))

            result = call("register", "alice")
            check("alice registers on alpha", result.get("ok") == {"user": "alice"}, result)
            [passkey] = call("whoami")["ok"]["passkeys"]
            check(
                "the server recorded alpha as the passkey's site",
                (passkey["site"], passkey["rpId"]) == ("http://alpha.localhost", "alpha.localhost"),
                passkey,
            )
            [credential] = stored()
            check(
                "Chromium filled in the RP ID from the host",
                credential["rpId"] == "alpha.localhost" and credential["isResidentCredential"],
                credential,
            )

            call("signOut")
            page.reload()
            page.click("#sign-in")
            page.wait_for_function(
                "document.getElementById('who').textContent.includes('alice')", timeout=10_000
            )
            check("the sign-in button signs alice back in", True)

            call("signOut")
            page.goto(alpha_elsewhere)
            result = call("signIn")
            check("alice signs in on another port", result.get("ok") == {"user": "alice"}, result)

            page.goto(beta)
            check("beta does not see alpha's session", call("whoami")["ok"]["user"] is None)
            result = call("signIn")
            check(
                "beta is not offered alpha's passkey",
                result.get("error") == "NotAllowedError",
                result,
            )

            result = call("register", "bob")
            check("bob registers on beta", result.get("ok") == {"user": "bob"}, result)
            call("signOut")
            result = call("signIn")
            check("beta signs in as bob, not alice", result.get("ok") == {"user": "bob"}, result)

            page.goto(alpha)
            call("signOut")
            result = call("signIn")
            check("alpha still signs in as alice", result.get("ok") == {"user": "alice"}, result)
        finally:
            browser.close()

    print(f"all {len(passed)} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
