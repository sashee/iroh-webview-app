"""The app's injected passkey script, in real Chromium, against the demo.

Chromium stands in for the WebView. Playwright's init scripts run at document
start, as WebViewCompat.addDocumentStartJavaScript does, and an exposed
function stands in for the WebMessageListener. Behind it is the software
authenticator from the unit tests, applying the app's RP ID rule.

What is under test is the script, in a real browser engine. It has to:

* replace Chromium's own WebAuthn before the page's scripts run;
* give a site's code (the demo page's here) objects it can use, all the way to
  py_webauthn on the server.

Node's tests (app/src/test/js) cover the same script against fakes. This is the
real engine, with a real CredentialsContainer and DOMException.

No virtual authenticator is installed, so Chromium's own WebAuthn has nothing to
sign with. A sign-in that succeeds can only have gone through the script.

The Kotlin half is tested on its own (PasskeyAuthenticatorTest); the two meet
on the phone.

    nix-shell passkey-demo --run 'python passkey-demo/check_injected_script.py'
"""

import base64
import json
import shutil
import sys
import threading
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright

from passkey_demo import PAGE_PATH, State, Store, server
from test_passkey_demo import create, get

SCRIPT = Path(__file__).resolve().parent.parent / "app/src/main/assets/passkeys.js"

# What the WebView's WebMessageListener gives a page: an object with
# postMessage and message events. Here postMessage goes to an exposed Python
# function, and what it returns is the reply.
BRIDGE = """
(() => {
  const listeners = [];
  globalThis.__irohPasskeys = {
    postMessage(message) {
      window.__toApp(message).then((reply) => {
        if (reply) for (const listener of listeners) listener({ data: reply });
      });
    },
    addEventListener(type, listener) {
      if (type === "message") listeners.push(listener);
    },
  };
})();
"""

CALL = """async ([name, arg]) => {
  try { return { ok: await window.passkeyDemo[name](arg) }; }
  catch (e) { return { error: e.name, message: e.message }; }
}"""


def origin_of(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}"


def app_side(keys: list):
    """The app's end of the bridge, in Python: RP ID rule, then the software authenticator."""

    def refuse(request, name, message):
        return json.dumps({"id": request["id"], "error": {"name": name, "message": message}})

    def receive(source, message):
        request = json.loads(message)
        origin = origin_of(source["frame"].url)  # as the WebView reports the sender
        host = urlsplit(origin).hostname
        options = request.get("options") or {}
        if request["type"] == "create":
            if options["rp"].get("id") not in (None, host):
                return refuse(request, "SecurityError", "not this page's host")
            key, credential = create(options, origin)
            keys.append(key)
            return json.dumps({"id": request["id"], "credential": credential})
        if request["type"] == "get":
            if options.get("rpId") not in (None, host):
                return refuse(request, "SecurityError", "not this page's host")
            mine = [key for key in keys if key.rp_id == host]
            if not mine:
                return refuse(request, "NotAllowedError", f"no passkey for {host}")
            return json.dumps({"id": request["id"], "credential": get(options, origin, mine[-1])})
        return None  # "cancel": nothing to answer

    return receive


def main() -> int:
    httpd = server(Store(State(), None), PAGE_PATH.read_bytes(), "127.0.0.1", 0)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    port = httpd.server_address[1]
    alpha = f"http://alpha.localhost:{port}/"
    beta = f"http://beta.localhost:{port}/"
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
        context.expose_binding("__toApp", app_side([]))
        context.add_init_script(BRIDGE)
        context.add_init_script(path=str(SCRIPT))
        page = context.new_page()

        def call(name: str, arg: object = None) -> dict:
            return page.evaluate(CALL, [name, arg])

        try:
            page.goto(alpha)
            page.wait_for_function("document.getElementById('environment').textContent.includes('platform')")
            check(
                "the script replaced WebAuthn before the page's own scripts ran",
                "platform authenticator: true" in page.text_content("#environment"),
                page.text_content("#environment"),
            )
            capabilities = page.evaluate("PublicKeyCredential.getClientCapabilities()")
            check("PublicKeyCredential is the script's", capabilities.get("extension:credProps") is True, capabilities)

            result = call("register", "alice")
            check("alice registers through the bridge, with PRF", result.get("ok") == {"user": "alice", "prf": True}, result)
            [passkey] = call("whoami")["ok"]["passkeys"]
            check("the server saw the page's origin", passkey["site"] == "http://alpha.localhost", passkey)

            call("signOut")
            result = call("signIn")
            check("alice signs in through the bridge", result.get("ok") == {"user": "alice"}, result)

            # --- PRF: a note the server cannot read ---
            result = call("unlockNote")
            check("the note unlocks with a PRF result, empty at first", result.get("ok") == "", result)
            result = call("saveNote", "the secret plan")
            check("the note is encrypted and saved", result.get("ok") == {"saved": True}, result)
            stored = page.evaluate("fetch('/api/note').then(r => r.json())")["note"]
            ciphertext = base64.urlsafe_b64decode(stored["ciphertext"] + "==")
            check("the server holds only ciphertext", b"secret" not in ciphertext and len(ciphertext) > 16, stored)
            page.reload()
            result = call("readNote")
            check("after a reload the key is gone", "unlock first" in str(result.get("message")), result)
            result = call("unlockNote")
            check("the same passkey unlocks the same note", result.get("ok") == "the secret plan", result)

            shapes = page.evaluate(
                """async () => {
                  const options = await (await fetch("/api/login/options", { method: "POST", body: "{}" })).json();
                  const credential = await navigator.credentials.get({
                    publicKey: PublicKeyCredential.parseRequestOptionsFromJSON(options),
                  });
                  return {
                    credential: credential instanceof PublicKeyCredential,
                    response: credential.response instanceof AuthenticatorAssertionResponse,
                    rawId: credential.rawId instanceof ArrayBuffer,
                    toJSON: typeof credential.toJSON().response.signature,
                  };
                }"""
            )
            check(
                "the page gets real PublicKeyCredential objects",
                shapes == {"credential": True, "response": True, "rawId": True, "toJSON": "string"},
                shapes,
            )

            page.goto(beta)
            result = call("signIn")
            check(
                "beta gets the app's refusal as a NotAllowedError",
                result.get("error") == "NotAllowedError",
                result,
            )

            aborted = page.evaluate(
                """async () => {
                  const controller = new AbortController();
                  const pending = navigator.credentials.get({
                    publicKey: { challenge: new Uint8Array(16) }, signal: controller.signal,
                  });
                  controller.abort();
                  try { await pending; return "resolved"; } catch (e) { return e.name; }
                }"""
            )
            check("aborting rejects with AbortError", aborted == "AbortError", aborted)

            conditional = page.evaluate(
                """async () => {
                  try {
                    await navigator.credentials.get({ publicKey: { challenge: new Uint8Array(16) }, mediation: "conditional" });
                    return "resolved";
                  } catch (e) { return e.constructor.name; }
                }"""
            )
            check("conditional mediation is a TypeError", conditional == "TypeError", conditional)
        finally:
            browser.close()

    print(f"all {len(passed)} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
