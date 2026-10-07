"""A passkey-only web app, for testing the Android app's WebAuthn support.

Not part of the app. It gives the app's passkey emulation something real to talk
to: a server using an independent WebAuthn implementation (py_webauthn), reached
the way any backend is -- over iroh, through the app's loopback proxy.

It does not know its own site ID, and that is the point of it. The app browses
each endpoint at `http://<label>.localhost:<port>/`, where the label comes from
the tunnel's endpoint id, and the tunnel is a separate process the web app knows
nothing about. So the options it sends leave the RP ID out, the client fills in
its own host, and each passkey remembers the origin it was registered from.
Login is checked against that.

Trusting the client's origin at registration is acceptable because of two rules:

* Only `http://localhost` and `http://*.localhost` are accepted. Those always
  resolve to the device the browser runs on, so no remote site can serve a page
  there to phish a registration.
* The port is not part of the match. The app's proxy prefers a fixed port per
  endpoint but falls back to any free one, and an RP ID never has a port anyway.

Sign-up is open, which a real service would not do: anyone can create an
account. Adding a passkey to an existing account needs a session as it.

Structure: `handle` is a pure function of (state, request, world) returning the
new state and a response. Everything that touches the world -- the clock, the
random source, sockets, the state file -- is in the `serve` half at the bottom.
"""

import argparse
import dataclasses
import json
import secrets
import socketserver
import sys
import threading
import time
from dataclasses import dataclass, field
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Mapping
from urllib.parse import urlsplit

from webauthn import (
    generate_authentication_options,
    generate_registration_options,
    verify_authentication_response,
    verify_registration_response,
)
from webauthn.helpers import (
    base64url_to_bytes,
    bytes_to_base64url,
    options_to_json_dict,
    parse_authentication_credential_json,
    parse_client_data_json,
    parse_registration_credential_json,
)
from webauthn.helpers.exceptions import WebAuthnException
from webauthn.helpers.structs import (
    AuthenticatorSelectionCriteria,
    PublicKeyCredentialDescriptor,
    ResidentKeyRequirement,
    UserVerificationRequirement,
)

CHALLENGE_LIFETIME = 120.0
SESSION_LIFETIME = 30 * 24 * 3600

# py_webauthn insists on an RP ID when building options. This one is removed
# before the options leave the server, and `.invalid` can never resolve, so a
# slip that let it through would fail loudly rather than bind to something real.
PLACEHOLDER_RP_ID = "placeholder.invalid"


# --- origins ---------------------------------------------------------------


def site_of(origin: str) -> str | None:
    """`scheme://host` of an origin: the origin without its port."""
    parts = urlsplit(origin)
    if not parts.scheme or not parts.hostname:
        return None
    return f"{parts.scheme}://{parts.hostname}"


def acceptable_origin(origin: str) -> bool:
    """Whether a passkey may be registered from `origin`.

    Strict about shape as well as host: an origin is scheme, host and port, and
    anything carrying a path, credentials or a query is not one.
    """
    parts = urlsplit(origin)
    try:
        parts.port
    except ValueError:
        return False
    host = parts.hostname or ""
    return (
        parts.scheme == "http"
        and (host == "localhost" or host.endswith(".localhost"))
        and parts.username is None
        and parts.path == ""
        and not parts.query
        and not parts.fragment
    )


# --- state -----------------------------------------------------------------


@dataclass(frozen=True)
class Passkey:
    user: str
    user_handle: str  # base64url
    public_key: str  # base64url, COSE
    sign_count: int
    site: str  # the origin it was registered from, without the port
    rp_id: str
    created: float
    last_used: float | None = None


@dataclass(frozen=True)
class Pending:
    """A ceremony in progress, keyed by its challenge."""

    purpose: str  # "register" or "login"
    expires: float
    user: str | None = None
    user_handle: str | None = None


@dataclass(frozen=True)
class Session:
    user: str
    expires: float


@dataclass(frozen=True)
class State:
    users: Mapping[str, str] = field(default_factory=dict)  # name -> user handle
    passkeys: Mapping[str, Passkey] = field(default_factory=dict)  # credential id -> passkey
    pending: Mapping[str, Pending] = field(default_factory=dict)  # challenge -> ceremony
    sessions: Mapping[str, Session] = field(default_factory=dict)  # token -> session


def state_to_json(state: State) -> dict:
    """The part worth keeping across a restart. Ceremonies in flight are not."""
    return {
        "users": dict(state.users),
        "passkeys": {k: dataclasses.asdict(v) for k, v in state.passkeys.items()},
        "sessions": {k: dataclasses.asdict(v) for k, v in state.sessions.items()},
    }


def state_from_json(data: dict) -> State:
    return State(
        users=dict(data.get("users", {})),
        passkeys={k: Passkey(**v) for k, v in data.get("passkeys", {}).items()},
        sessions={k: Session(**v) for k, v in data.get("sessions", {}).items()},
    )


# --- requests and responses ------------------------------------------------


@dataclass(frozen=True)
class Request:
    method: str
    path: str
    body: bytes = b""
    cookies: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class Response:
    status: int
    body: bytes
    content_type: str = "application/json"
    cookie: str | None = None  # a Set-Cookie value
    note: str | None = None  # a line for the server's log


@dataclass(frozen=True)
class World:
    """Everything a request needs from outside: time, randomness, the page."""

    now: float
    random: Callable[[int], bytes]
    page: bytes


class Refused(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.response = json_response(status, {"error": message})


def json_response(status: int, body: object, **extra) -> Response:
    return Response(status, json.dumps(body).encode(), **extra)


def json_body(request: Request) -> dict:
    try:
        body = json.loads(request.body or b"{}")
    except ValueError:
        raise Refused(400, "the request body is not JSON")
    if not isinstance(body, dict):
        raise Refused(400, "the request body is not a JSON object")
    return body


def b64(data: bytes) -> str:
    return bytes_to_base64url(data)


# --- the pure core -----------------------------------------------------------


def current_user(state: State, request: Request, now: float) -> str | None:
    session = state.sessions.get(request.cookies.get("session", ""))
    return session.user if session and session.expires > now else None


def with_pending(state: State, challenge: bytes, pending: Pending, now: float) -> State:
    live = {c: p for c, p in state.pending.items() if p.expires > now}
    return dataclasses.replace(state, pending={**live, b64(challenge): pending})


def live_pending(state: State, challenge: bytes, purpose: str, now: float) -> Pending:
    pending = state.pending.get(b64(challenge))
    if pending is None or pending.purpose != purpose:
        raise Refused(400, "unknown challenge -- start again")
    if pending.expires <= now:
        raise Refused(400, "the challenge has expired -- start again")
    return pending


def without_pending(state: State, challenge: bytes) -> State:
    key = b64(challenge)
    return dataclasses.replace(
        state, pending={c: p for c, p in state.pending.items() if c != key}
    )


def signed_in(state: State, user: str, world: World, note: str) -> tuple[State, Response]:
    token = b64(world.random(32))
    live = {t: s for t, s in state.sessions.items() if s.expires > world.now}
    state = dataclasses.replace(
        state, sessions={**live, token: Session(user, world.now + SESSION_LIFETIME)}
    )
    cookie = f"session={token}; Path=/; Max-Age={SESSION_LIFETIME}; HttpOnly; SameSite=Lax"
    return state, json_response(200, {"user": user}, cookie=cookie, note=note)


def show_page(state: State, request: Request, world: World) -> tuple[State, Response]:
    return state, Response(200, world.page, content_type="text/html; charset=utf-8")


def no_icon(state: State, request: Request, world: World) -> tuple[State, Response]:
    # Every page load asks; a 404 for it each time buries the log lines that matter.
    return state, Response(204, b"", content_type="image/x-icon")


def me(state: State, request: Request, world: World) -> tuple[State, Response]:
    user = current_user(state, request, world.now)
    passkeys = [
        {
            "id": credential_id,
            "site": passkey.site,
            "rpId": passkey.rp_id,
            "signCount": passkey.sign_count,
            "created": passkey.created,
            "lastUsed": passkey.last_used,
        }
        for credential_id, passkey in state.passkeys.items()
        if user is not None and passkey.user == user
    ]
    return state, json_response(200, {"user": user, "passkeys": passkeys})


def begin_registration(state: State, request: Request, world: World) -> tuple[State, Response]:
    username = str(json_body(request).get("username", "")).strip()
    if not 0 < len(username) <= 64:
        raise Refused(400, "a username of 1 to 64 characters is needed")
    if username in state.users and current_user(state, request, world.now) != username:
        raise Refused(403, f"{username} already exists -- sign in as it to add a passkey")

    handle = state.users.get(username) or b64(world.random(16))
    challenge = world.random(32)
    options = options_to_json_dict(
        generate_registration_options(
            rp_id=PLACEHOLDER_RP_ID,
            rp_name="passkey-demo",
            user_name=username,
            user_id=base64url_to_bytes(handle),
            challenge=challenge,
            authenticator_selection=AuthenticatorSelectionCriteria(
                resident_key=ResidentKeyRequirement.REQUIRED,
                user_verification=UserVerificationRequirement.REQUIRED,
            ),
            exclude_credentials=[
                PublicKeyCredentialDescriptor(id=base64url_to_bytes(credential_id))
                for credential_id, passkey in state.passkeys.items()
                if passkey.user == username
            ],
        )
    )
    # Left out on purpose: the client fills in its own host.
    options["rp"] = {k: v for k, v in options["rp"].items() if k != "id"}
    pending = Pending("register", world.now + CHALLENGE_LIFETIME, username, handle)
    return with_pending(state, challenge, pending, world.now), json_response(200, options)


def finish_registration(state: State, request: Request, world: World) -> tuple[State, Response]:
    try:
        credential = parse_registration_credential_json(request.body.decode())
        client = parse_client_data_json(credential.response.client_data_json)
    except (WebAuthnException, ValueError) as cause:
        raise Refused(400, f"not a registration response: {cause}")
    pending = live_pending(state, client.challenge, "register", world.now)
    # Single use, whatever happens next.
    state = without_pending(state, client.challenge)

    try:
        if not acceptable_origin(client.origin):
            raise Refused(
                403, f"passkeys can only be registered from http://*.localhost, not {client.origin}"
            )
        # Checked again here: the name may have been taken since the options went out.
        if pending.user in state.users and current_user(state, request, world.now) != pending.user:
            raise Refused(403, f"{pending.user} already exists -- sign in as it to add a passkey")
        rp_id = urlsplit(client.origin).hostname
        try:
            verified = verify_registration_response(
                credential=credential,
                expected_challenge=client.challenge,
                expected_rp_id=rp_id,
                expected_origin=client.origin,
                require_user_verification=True,
            )
        except WebAuthnException as cause:
            raise Refused(400, f"registration rejected: {cause}")
    except Refused as refusal:
        return state, refusal.response

    passkey = Passkey(
        user=pending.user,
        user_handle=pending.user_handle,
        public_key=b64(verified.credential_public_key),
        sign_count=verified.sign_count,
        site=site_of(client.origin),
        rp_id=rp_id,
        created=world.now,
    )
    state = dataclasses.replace(
        state,
        users={**state.users, pending.user: pending.user_handle},
        passkeys={**state.passkeys, b64(verified.credential_id): passkey},
    )
    return signed_in(
        state, pending.user, world, f"registered a passkey for {pending.user} from {client.origin}"
    )


def begin_login(state: State, request: Request, world: World) -> tuple[State, Response]:
    challenge = world.random(32)
    options = options_to_json_dict(
        generate_authentication_options(
            rp_id=PLACEHOLDER_RP_ID,
            challenge=challenge,
            user_verification=UserVerificationRequirement.REQUIRED,
        )
    )
    # Left out on purpose, as in registration. With no allowCredentials either,
    # this is a usernameless login: the client offers whatever it holds for its
    # own host.
    options.pop("rpId", None)
    pending = Pending("login", world.now + CHALLENGE_LIFETIME)
    return with_pending(state, challenge, pending, world.now), json_response(200, options)


def finish_login(state: State, request: Request, world: World) -> tuple[State, Response]:
    try:
        credential = parse_authentication_credential_json(request.body.decode())
        client = parse_client_data_json(credential.response.client_data_json)
    except (WebAuthnException, ValueError) as cause:
        raise Refused(400, f"not a login response: {cause}")
    live_pending(state, client.challenge, "login", world.now)
    state = without_pending(state, client.challenge)

    try:
        credential_id = b64(credential.raw_id)
        passkey = state.passkeys.get(credential_id)
        if passkey is None:
            raise Refused(400, "this passkey is not registered here")
        # The client is not trusted to have scoped the passkey to the right
        # site: a misbehaving one could offer it anywhere.
        if site_of(client.origin) != passkey.site:
            raise Refused(
                403,
                f"this passkey was registered from {passkey.site}, not {site_of(client.origin)}",
            )
        # A usernameless login has nothing else to say whose passkey this is.
        user_handle = credential.response.user_handle
        if user_handle is None:
            raise Refused(400, "a usernameless login must return the user handle")
        if b64(user_handle) != passkey.user_handle:
            raise Refused(403, "the user handle does not belong to this passkey")
        try:
            verified = verify_authentication_response(
                credential=credential,
                expected_challenge=client.challenge,
                expected_rp_id=passkey.rp_id,
                expected_origin=client.origin,
                credential_public_key=base64url_to_bytes(passkey.public_key),
                credential_current_sign_count=passkey.sign_count,
                require_user_verification=True,
            )
        except WebAuthnException as cause:
            raise Refused(400, f"login rejected: {cause}")
    except Refused as refusal:
        return state, refusal.response

    used = dataclasses.replace(passkey, sign_count=verified.new_sign_count, last_used=world.now)
    state = dataclasses.replace(state, passkeys={**state.passkeys, credential_id: used})
    return signed_in(state, passkey.user, world, f"{passkey.user} signed in from {client.origin}")


def logout(state: State, request: Request, world: World) -> tuple[State, Response]:
    token = request.cookies.get("session", "")
    state = dataclasses.replace(
        state, sessions={t: s for t, s in state.sessions.items() if t != token}
    )
    return state, json_response(200, {"user": None}, cookie="session=; Path=/; Max-Age=0")


ROUTES = {
    ("GET", "/"): show_page,
    ("GET", "/favicon.ico"): no_icon,
    ("GET", "/api/me"): me,
    ("POST", "/api/register/options"): begin_registration,
    ("POST", "/api/register/verify"): finish_registration,
    ("POST", "/api/login/options"): begin_login,
    ("POST", "/api/login/verify"): finish_login,
    ("POST", "/api/logout"): logout,
}


def handle(state: State, request: Request, world: World) -> tuple[State, Response]:
    route = ROUTES.get((request.method, request.path))
    if route is None:
        return state, json_response(404, {"error": "no such page"})
    try:
        return route(state, request, world)
    except Refused as refusal:
        return state, refusal.response


# --- the edge: sockets, clock, randomness, the state file -------------------


class Store:
    """The one mutable thing: the current state, behind a lock, saved on change.

    A threading server rather than a single-threaded one because a browser opens
    connections it does not use straight away, and one idle socket would block
    a single-threaded server until it timed out.
    """

    def __init__(self, state: State, path: Path | None):
        self._state = state
        self._path = path
        self._lock = threading.Lock()

    def apply(self, request: Request, page: bytes) -> Response:
        with self._lock:
            state, response = handle(self._state, request, World(time.time(), secrets.token_bytes, page))
            if self._path is not None and state_to_json(state) != state_to_json(self._state):
                temporary = self._path.with_suffix(".tmp")
                temporary.write_text(json.dumps(state_to_json(state), indent=2))
                temporary.replace(self._path)
            self._state = state
        return response


def load(path: Path | None) -> State:
    if path is None or not path.exists():
        return State()
    return state_from_json(json.loads(path.read_text()))


def parse_cookies(header: str) -> dict[str, str]:
    try:
        return {name: morsel.value for name, morsel in SimpleCookie(header).items()}
    except CookieError:
        return {}


def handler_for(store: Store, page: bytes) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def address_string(self):
            # A unix socket's peer has no address, and the default would index
            # into an empty string.
            return self.client_address[0] if isinstance(self.client_address, tuple) else "unix"

        def do_GET(self):
            self.serve()

        def do_POST(self):
            self.serve()

        def serve(self):
            length = int(self.headers.get("Content-Length") or 0)
            request = Request(
                method=self.command,
                path=urlsplit(self.path).path,
                body=self.rfile.read(length),
                cookies=parse_cookies(self.headers.get("Cookie", "")),
            )
            response = store.apply(request, page)
            self.send_response(response.status)
            self.send_header("Content-Type", response.content_type)
            self.send_header("Content-Length", str(len(response.body)))
            self.send_header("Cache-Control", "no-store")
            if response.cookie is not None:
                self.send_header("Set-Cookie", response.cookie)
            self.end_headers()
            self.wfile.write(response.body)
            if response.note is not None:
                print(response.note, file=sys.stderr, flush=True)

    return Handler


def server(store: Store, page: bytes, host: str, port: int) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), handler_for(store, page))


class ThreadingUnixHTTPServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


def unix_server(store: Store, page: bytes, path: Path) -> ThreadingUnixHTTPServer:
    """Serve on a unix socket, as the monitoring platform does behind iroh-uds-listen."""
    path.unlink(missing_ok=True)  # a socket left behind by a previous run
    return ThreadingUnixHTTPServer(str(path), handler_for(store, page))


PAGE_PATH = Path(__file__).with_name("page.html")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--unix", type=Path, help="serve on this unix socket instead of TCP")
    parser.add_argument("--state", type=Path, help="keep accounts and passkeys in this file")
    args = parser.parse_args()

    store = Store(load(args.state), args.state)
    page = PAGE_PATH.read_bytes()
    if args.unix is not None:
        httpd = unix_server(store, page, args.unix)
        where = f"unix socket {args.unix}"
    else:
        httpd = server(store, page, args.host, args.port)
        where = f"{args.host}:{args.port} -- open http://demo.localhost:{args.port}/"
    print(f"serving on {where}", file=sys.stderr, flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
