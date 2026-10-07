"""Tests for passkey_demo, through `handle` alone: no sockets, no browser.

The other side of each ceremony is a software authenticator built here, the
same shape as the one the Android app emulates: it writes clientDataJSON and
authenticatorData itself and signs with a P-256 key. py_webauthn checks the
result, so these tests also pin down what a correct response looks like.
"""

import hashlib
import json
import random
from dataclasses import dataclass

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from webauthn.helpers import base64url_to_bytes, bytes_to_base64url, encode_cbor

from passkey_demo import (
    CHALLENGE_LIFETIME,
    Request,
    State,
    Store,
    World,
    acceptable_origin,
    handle,
    load,
    site_of,
    state_from_json,
    state_to_json,
    unix_server,
)

ALPHA = "http://alpha.localhost:21000"
BETA = "http://beta.localhost:21000"

USER_PRESENT = 0x01
USER_VERIFIED = 0x04
ATTESTED_DATA = 0x40


# --- a software authenticator -----------------------------------------------


@dataclass(frozen=True)
class Key:
    credential_id: bytes
    private_key: ec.EllipticCurvePrivateKey
    rp_id: str
    user_handle: bytes


def host(origin: str) -> str:
    return origin.split("://", 1)[1].split(":", 1)[0]


def client_data(kind: str, challenge: str, origin: str) -> bytes:
    return json.dumps(
        {"type": kind, "challenge": challenge, "origin": origin, "crossOrigin": False}
    ).encode()


def cose_key(private_key: ec.EllipticCurvePrivateKey) -> bytes:
    numbers = private_key.public_key().public_numbers()
    return encode_cbor(
        {1: 2, 3: -7, -1: 1, -2: numbers.x.to_bytes(32, "big"), -3: numbers.y.to_bytes(32, "big")}
    )


def create(options: dict, origin: str, flags: int = USER_PRESENT | USER_VERIFIED) -> tuple[Key, dict]:
    """navigator.credentials.create, as a client that does it properly."""
    rp_id = options["rp"].get("id") or host(origin)
    key = Key(
        credential_id=random.randbytes(16),
        private_key=ec.generate_private_key(ec.SECP256R1()),
        rp_id=rp_id,
        user_handle=base64url_to_bytes(options["user"]["id"]),
    )
    authenticator_data = (
        hashlib.sha256(rp_id.encode()).digest()
        + bytes([flags | ATTESTED_DATA])
        + (0).to_bytes(4, "big")
        + bytes(16)  # AAGUID
        + len(key.credential_id).to_bytes(2, "big")
        + key.credential_id
        + cose_key(key.private_key)
    )
    attestation = encode_cbor({"fmt": "none", "attStmt": {}, "authData": authenticator_data})
    # The full RegistrationResponseJSON, as the app sends it: the injected
    # script reads every field, even though the server needs only two.
    public_key = key.private_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    reply = {
        "id": bytes_to_base64url(key.credential_id),
        "rawId": bytes_to_base64url(key.credential_id),
        "type": "public-key",
        "authenticatorAttachment": "platform",
        "clientExtensionResults": {},
        "response": {
            "clientDataJSON": bytes_to_base64url(
                client_data("webauthn.create", options["challenge"], origin)
            ),
            "attestationObject": bytes_to_base64url(attestation),
            "authenticatorData": bytes_to_base64url(authenticator_data),
            "publicKey": bytes_to_base64url(public_key),
            "publicKeyAlgorithm": -7,
            "transports": ["internal"],
        },
    }
    return key, reply


def get(
    options: dict,
    origin: str,
    key: Key,
    flags: int = USER_PRESENT | USER_VERIFIED,
    user_handle: bytes | None = None,
    signing_key: ec.EllipticCurvePrivateKey | None = None,
    with_user_handle: bool = True,
) -> dict:
    """navigator.credentials.get. Signs for the key's own RP ID, whatever the origin."""
    data = client_data("webauthn.get", options["challenge"], origin)
    authenticator_data = (
        hashlib.sha256(key.rp_id.encode()).digest() + bytes([flags]) + (0).to_bytes(4, "big")
    )
    signature = (signing_key or key.private_key).sign(
        authenticator_data + hashlib.sha256(data).digest(), ec.ECDSA(hashes.SHA256())
    )
    response = {
        "clientDataJSON": bytes_to_base64url(data),
        "authenticatorData": bytes_to_base64url(authenticator_data),
        "signature": bytes_to_base64url(signature),
    }
    if with_user_handle:
        response["userHandle"] = bytes_to_base64url(user_handle or key.user_handle)
    return {
        "id": bytes_to_base64url(key.credential_id),
        "rawId": bytes_to_base64url(key.credential_id),
        "type": "public-key",
        "authenticatorAttachment": "platform",
        "clientExtensionResults": {},
        "response": response,
    }


# --- driving the server -----------------------------------------------------


def world(now: float = 1_000_000.0, seed: int = 1) -> World:
    return World(now, random.Random(seed).randbytes, b"<page>")


def call(state, method, path, body=None, cookies=None, at=None, seed=1):
    request = Request(
        method, path, b"" if body is None else json.dumps(body).encode(), cookies or {}
    )
    return handle(state, request, at or world(seed=seed))


def payload(response) -> dict:
    return json.loads(response.body)


def session_of(response) -> dict:
    token = response.cookie.split(";", 1)[0].split("=", 1)[1]
    return {"session": token}


def register(state, username, origin, cookies=None, flags=USER_PRESENT | USER_VERIFIED, seed=1):
    state, options = call(state, "POST", "/api/register/options", {"username": username}, cookies, seed=seed)
    assert options.status == 200, payload(options)
    key, reply = create(payload(options), origin, flags)
    state, response = call(state, "POST", "/api/register/verify", reply, cookies, seed=seed + 1)
    return state, response, key


def login_options(state, at=None):
    state, options = call(state, "POST", "/api/login/options", {}, at=at, seed=7)
    assert options.status == 200
    return state, payload(options)


def login(state, origin, key, **tweaks):
    state, options = login_options(state)
    return call(state, "POST", "/api/login/verify", get(options, origin, key, **tweaks), seed=8)


def whoami(state, cookies) -> dict:
    return payload(call(state, "GET", "/api/me", cookies=cookies)[1])


def registered(username="alice", origin=ALPHA):
    state, response, key = register(State(), username, origin)
    assert response.status == 200, payload(response)
    return state, key, session_of(response)


# --- origins ----------------------------------------------------------------


@pytest.mark.parametrize(
    "origin",
    ["http://localhost", "http://localhost:8080", "http://alpha.localhost:21000", "http://a.b.localhost"],
)
def test_localhost_origins_may_register(origin):
    assert acceptable_origin(origin)


@pytest.mark.parametrize(
    "origin",
    [
        "https://alpha.localhost",  # the app's proxy is http; nothing else should claim the name
        "http://example.com",
        "http://localhost.example.com",  # a suffix match, not a substring one
        "http://evillocalhost",
        "http://127.0.0.1:8080",
        "http://user@alpha.localhost",
        "http://alpha.localhost/path",
        "http://alpha.localhost:99999",
        "null",
        "",
    ],
)
def test_other_origins_may_not_register(origin):
    assert not acceptable_origin(origin)


def test_the_site_is_the_origin_without_its_port():
    assert site_of("http://alpha.localhost:21000") == "http://alpha.localhost"
    assert site_of("http://alpha.localhost") == "http://alpha.localhost"
    assert site_of("not an origin") is None


# --- registration -----------------------------------------------------------


def test_registration_options_leave_the_rp_id_out():
    _, options = call(State(), "POST", "/api/register/options", {"username": "alice"})
    assert "id" not in payload(options)["rp"]


def test_registration_asks_for_a_discoverable_verified_passkey():
    _, options = call(State(), "POST", "/api/register/options", {"username": "alice"})
    selection = payload(options)["authenticatorSelection"]
    assert selection["residentKey"] == "required"
    assert selection["userVerification"] == "required"


def test_registering_signs_in_and_records_where_from():
    state, _, cookies = registered()
    me = whoami(state, cookies)
    assert me["user"] == "alice"
    [passkey] = me["passkeys"]
    assert passkey["site"] == "http://alpha.localhost"
    assert passkey["rpId"] == "alpha.localhost"


def test_registration_is_refused_from_a_remote_origin():
    state, response, _ = register(State(), "alice", "http://example.com")
    assert response.status == 403
    assert "localhost" in payload(response)["error"]
    assert state.users == {}


def test_registration_needs_user_verification():
    state, response, _ = register(State(), "alice", ALPHA, flags=USER_PRESENT)
    assert response.status == 400
    assert state.users == {}


def test_an_existing_name_cannot_be_claimed_without_its_session():
    state, _, _ = registered()
    _, response = call(state, "POST", "/api/register/options", {"username": "alice"})
    assert response.status == 403


def test_a_second_passkey_can_be_added_with_the_session():
    state, _, cookies = registered()
    state, response, _ = register(state, "alice", BETA, cookies, seed=3)
    assert response.status == 200, payload(response)
    assert {p["site"] for p in whoami(state, cookies)["passkeys"]} == {
        "http://alpha.localhost",
        "http://beta.localhost",
    }


def test_registration_excludes_passkeys_the_user_already_has():
    state, key, cookies = registered()
    _, options = call(state, "POST", "/api/register/options", {"username": "alice"}, cookies)
    excluded = [c["id"] for c in payload(options)["excludeCredentials"]]
    assert excluded == [bytes_to_base64url(key.credential_id)]


@pytest.mark.parametrize("username", ["", "   ", "x" * 65])
def test_a_username_is_required(username):
    _, response = call(State(), "POST", "/api/register/options", {"username": username})
    assert response.status == 400


# --- login ------------------------------------------------------------------


def test_login_options_leave_the_rp_id_out_and_name_no_credentials():
    _, options = login_options(State())
    assert "rpId" not in options
    assert options["allowCredentials"] == []
    assert options["userVerification"] == "required"


def test_a_registered_passkey_signs_in():
    state, key, _ = registered()
    state, response = login(state, ALPHA, key)
    assert response.status == 200, payload(response)
    assert payload(response) == {"user": "alice"}
    assert whoami(state, session_of(response))["user"] == "alice"


def test_the_port_may_change_between_registration_and_login():
    state, key, _ = registered(origin="http://alpha.localhost:21000")
    _, response = login(state, "http://alpha.localhost:39999", key)
    assert response.status == 200, payload(response)


def test_a_passkey_is_refused_from_another_site():
    # A client that offers alpha's passkey on beta's page. A browser would not,
    # and the app must not -- but the server does not rely on that.
    state, key, _ = registered(origin=ALPHA)
    _, response = login(state, BETA, key)
    assert response.status == 403
    assert "alpha.localhost" in payload(response)["error"]


def test_login_needs_user_verification():
    state, key, _ = registered()
    _, response = login(state, ALPHA, key, flags=USER_PRESENT)
    assert response.status == 400


def test_login_needs_the_user_handle():
    state, key, _ = registered()
    _, response = login(state, ALPHA, key, with_user_handle=False)
    assert response.status == 400


def test_a_user_handle_from_another_account_is_refused():
    state, key, _ = registered()
    _, response = login(state, ALPHA, key, user_handle=b"someone else")
    assert response.status == 403


def test_a_signature_from_another_key_is_refused():
    state, key, _ = registered()
    other = ec.generate_private_key(ec.SECP256R1())
    _, response = login(state, ALPHA, key, signing_key=other)
    assert response.status == 400


def test_an_unknown_passkey_is_refused():
    state, _, _ = registered()
    stranger, _ = create({"rp": {}, "user": {"id": "AA"}, "challenge": "AA"}, ALPHA)
    _, response = login(state, ALPHA, stranger)
    assert response.status == 400


# --- challenges -------------------------------------------------------------


def test_a_challenge_is_single_use():
    state, key, _ = registered()
    state, options = login_options(state)
    reply = get(options, ALPHA, key)
    state, first = call(state, "POST", "/api/login/verify", reply, seed=8)
    _, second = call(state, "POST", "/api/login/verify", reply, seed=9)
    assert first.status == 200
    assert second.status == 400


def test_a_refused_attempt_still_uses_up_its_challenge():
    state, key, _ = registered()
    state, options = login_options(state)
    state, refused = call(state, "POST", "/api/login/verify", get(options, BETA, key))
    _, retried = call(state, "POST", "/api/login/verify", get(options, ALPHA, key))
    assert refused.status == 403
    assert retried.status == 400
    assert "unknown challenge" in payload(retried)["error"]


def test_an_expired_challenge_is_refused():
    state, key, _ = registered()
    start = 2_000_000.0
    state, options = login_options(state, at=world(now=start))
    late = world(now=start + CHALLENGE_LIFETIME + 1)
    _, response = call(state, "POST", "/api/login/verify", get(options, ALPHA, key), at=late)
    assert response.status == 400
    assert "expired" in payload(response)["error"]


def test_a_registration_challenge_cannot_finish_a_login():
    state, key, _ = registered()
    state, options = call(state, "POST", "/api/register/options", {"username": "bob"})
    _, response = call(state, "POST", "/api/login/verify", get(payload(options), ALPHA, key))
    assert response.status == 400


# --- sessions and the rest ------------------------------------------------------


def test_signing_out_ends_the_session():
    state, _, cookies = registered()
    state, response = call(state, "POST", "/api/logout", {}, cookies)
    assert response.cookie.startswith("session=;")
    assert whoami(state, cookies)["user"] is None


def test_a_session_cookie_outlives_the_browser_process():
    # Without Max-Age the cookie dies with the app's process, and every launch
    # asks for the fingerprint again even though the session is still good.
    _, response, _ = register(State(), "alice", ALPHA)
    assert "Max-Age=" in response.cookie
    assert "HttpOnly" in response.cookie


def test_the_state_survives_a_save_and_a_load():
    state, key, cookies = registered()
    restored = state_from_json(json.loads(json.dumps(state_to_json(state))))
    assert whoami(restored, cookies)["user"] == "alice"
    _, response = login(restored, ALPHA, key)
    assert response.status == 200, payload(response)


def test_the_store_writes_a_registration_to_its_file_and_reads_it_back(tmp_path):
    path = tmp_path / "state.json"
    store = Store(State(), path)
    options = store.apply(
        Request("POST", "/api/register/options", json.dumps({"username": "alice"}).encode()), b""
    )
    _, reply = create(payload(options), ALPHA)
    response = store.apply(Request("POST", "/api/register/verify", json.dumps(reply).encode()), b"")
    assert response.status == 200, payload(response)
    assert whoami(load(path), session_of(response))["user"] == "alice"


def test_a_missing_state_file_is_an_empty_state(tmp_path):
    assert load(tmp_path / "absent.json") == State()


def test_the_demo_serves_over_a_unix_socket(tmp_path):
    # What iroh-uds-listen forwards to, as it does for the monitoring platform.
    import http.client
    import socket
    import threading

    path = tmp_path / "demo.sock"
    httpd = unix_server(Store(State(), None), b"<page>", path)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        connection = http.client.HTTPConnection("localhost")
        connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.sock.connect(str(path))
        connection.request("GET", "/api/me")
        response = connection.getresponse()
        assert response.status == 200
        assert json.loads(response.read()) == {"user": None, "passkeys": []}
    finally:
        httpd.shutdown()
        httpd.server_close()


def test_the_page_is_served():
    _, response = call(State(), "GET", "/")
    assert response.status == 200
    assert response.content_type.startswith("text/html")


def test_an_unknown_path_is_not_found():
    _, response = call(State(), "GET", "/nope")
    assert response.status == 404


def test_a_body_that_is_not_json_is_refused():
    request = Request("POST", "/api/register/options", b"{not json", {})
    _, response = handle(State(), request, world())
    assert response.status == 400


def test_garbage_in_place_of_a_response_is_refused():
    _, response = call(State(), "POST", "/api/login/verify", {"id": "x"})
    assert response.status == 400
