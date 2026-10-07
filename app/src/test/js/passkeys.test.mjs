// Tests for app/src/main/assets/passkeys.js, the script injected into pages.
//
// It runs here against a fake bridge, in Node's own realm, so what it hands a
// page can be compared with ordinary ArrayBuffers. Robolectric cannot run it --
// its WebView executes no JavaScript -- and the app side is tested on its own,
// so these pin down the half in between: what goes over the bridge and what
// comes back out of it.
//
//   node --test app/src/test/js/passkeys.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  process.env.PASSKEYS_JS ?? new URL("../../main/assets/passkeys.js", import.meta.url),
  "utf8",
);

const GLOBALS = [
  "PublicKeyCredential",
  "AuthenticatorResponse",
  "AuthenticatorAttestationResponse",
  "AuthenticatorAssertionResponse",
];

/** Run the script in a fresh page: a fake bridge and a fake navigator. */
function page({ credentials, bridge = true } = {}) {
  const sent = [];
  const listeners = [];
  for (const name of GLOBALS) delete globalThis[name];
  globalThis.__irohPasskeys = bridge
    ? {
        postMessage: (message) => sent.push(JSON.parse(message)),
        addEventListener: (type, listener) => type === "message" && listeners.push(listener),
      }
    : undefined;
  const navigator = credentials === undefined ? {} : { credentials };
  Object.defineProperty(globalThis, "navigator", { value: navigator, configurable: true, writable: true });
  vm.runInThisContext(source);
  return {
    sent,
    navigator,
    reply: (message) => listeners.forEach((listener) => listener({ data: JSON.stringify(message) })),
    raw: (data) => listeners.forEach((listener) => listener({ data })),
  };
}

const bytes = (...values) => new Uint8Array(values);
const b64url = (values) => Buffer.from(values).toString("base64url");
const same = (buffer, values) => assert.deepEqual([...new Uint8Array(buffer)], values);

const creation = (extra = {}) => ({
  rp: { name: "demo" },
  user: { id: bytes(1, 2, 3), name: "alice", displayName: "Alice" },
  challenge: bytes(9, 8, 7, 6).buffer,
  pubKeyCredParams: [{ type: "public-key", alg: -7 }],
  ...extra,
});

const registration = {
  id: b64url([0xaa, 0xbb]),
  rawId: b64url([0xaa, 0xbb]),
  type: "public-key",
  authenticatorAttachment: "platform",
  response: {
    clientDataJSON: b64url([1]),
    attestationObject: b64url([2]),
    authenticatorData: b64url([3]),
    publicKey: b64url([4]),
    publicKeyAlgorithm: -7,
    transports: ["internal"],
  },
  clientExtensionResults: { credProps: { rk: true } },
};

const assertion = {
  id: b64url([0xaa, 0xbb]),
  rawId: b64url([0xaa, 0xbb]),
  type: "public-key",
  authenticatorAttachment: "platform",
  response: {
    clientDataJSON: b64url([1]),
    authenticatorData: b64url([3]),
    signature: b64url([5, 6]),
    userHandle: b64url([1, 2, 3]),
  },
  clientExtensionResults: {},
};

const flush = () => new Promise((resolve) => setImmediate(resolve));

test("the WebAuthn classes are installed", async () => {
  page();
  for (const name of GLOBALS) assert.equal(typeof globalThis[name], "function", name);
  assert.equal(await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable(), true);
  assert.equal(await PublicKeyCredential.isConditionalMediationAvailable(), false);
  assert.equal((await PublicKeyCredential.getClientCapabilities()).passkeyPlatformAuthenticator, true);
});

test("the page cannot construct a credential of its own", () => {
  page();
  assert.throws(() => new PublicKeyCredential(), TypeError);
  assert.throws(() => new AuthenticatorAssertionResponse(), TypeError);
});

test("create relays the options with binary fields as base64url", async () => {
  const { sent, navigator } = page();
  navigator.credentials.create({
    publicKey: creation({ excludeCredentials: [{ type: "public-key", id: bytes(0xaa) }] }),
  });
  const [message] = sent;
  assert.equal(message.type, "create");
  assert.equal(message.options.challenge, b64url([9, 8, 7, 6]));
  assert.equal(message.options.user.id, b64url([1, 2, 3]));
  assert.equal(message.options.excludeCredentials[0].id, b64url([0xaa]));
  assert.deepEqual(message.options.pubKeyCredParams, [{ type: "public-key", alg: -7 }]);
  assert.equal(message.options.attestation, "none");
});

test("an RP ID the page leaves out is left out for the app to fill in", () => {
  const { sent, navigator } = page();
  navigator.credentials.create({ publicKey: creation() });
  navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  assert.equal("id" in sent[0].options.rp, false);
  assert.equal("rpId" in sent[1].options, false);
});

test("an RP ID the page names is passed on for the app to check", () => {
  const { sent, navigator } = page();
  navigator.credentials.get({ publicKey: { challenge: bytes(1), rpId: "elsewhere.example" } });
  assert.equal(sent[0].options.rpId, "elsewhere.example");
});

test("a registration reply becomes a PublicKeyCredential", async () => {
  const { sent, navigator, reply } = page();
  const pending = navigator.credentials.create({ publicKey: creation() });
  reply({ id: sent[0].id, credential: registration });
  const credential = await pending;

  assert.ok(credential instanceof PublicKeyCredential);
  assert.ok(credential.response instanceof AuthenticatorAttestationResponse);
  assert.ok(credential.response instanceof AuthenticatorResponse);
  assert.equal(credential.id, registration.id);
  assert.equal(credential.type, "public-key");
  assert.equal(credential.authenticatorAttachment, "platform");
  same(credential.rawId, [0xaa, 0xbb]);
  same(credential.response.clientDataJSON, [1]);
  same(credential.response.attestationObject, [2]);
  same(credential.response.getAuthenticatorData(), [3]);
  same(credential.response.getPublicKey(), [4]);
  assert.equal(credential.response.getPublicKeyAlgorithm(), -7);
  assert.deepEqual(credential.response.getTransports(), ["internal"]);
  assert.deepEqual(credential.getClientExtensionResults(), { credProps: { rk: true } });
  assert.deepEqual(credential.toJSON(), registration);
});

test("a sign-in reply becomes an assertion", async () => {
  const { sent, navigator, reply } = page();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  reply({ id: sent[0].id, credential: assertion });
  const credential = await pending;

  assert.ok(credential.response instanceof AuthenticatorAssertionResponse);
  same(credential.response.authenticatorData, [3]);
  same(credential.response.signature, [5, 6]);
  same(credential.response.userHandle, [1, 2, 3]);
  assert.deepEqual(credential.toJSON(), assertion);
});

test("an assertion without a user handle reports null", async () => {
  const { sent, navigator, reply } = page();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  const { userHandle, ...response } = assertion.response;
  reply({ id: sent[0].id, credential: { ...assertion, response } });
  assert.equal((await pending).response.userHandle, null);
});

test("an error reply rejects with the exception a browser would throw", async () => {
  const { sent, navigator, reply } = page();
  const refused = navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  reply({ id: sent[0].id, error: { name: "NotAllowedError", message: "cancelled" } });
  await assert.rejects(refused, (e) => e instanceof DOMException && e.name === "NotAllowedError");

  const malformed = navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  reply({ id: sent[1].id, error: { name: "TypeError", message: "bad options" } });
  await assert.rejects(malformed, TypeError);
});

test("replies are matched to their requests by id", async () => {
  const { sent, navigator, reply } = page();
  const first = navigator.credentials.create({ publicKey: creation() });
  const second = navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  reply({ id: sent[1].id, credential: assertion });
  reply({ id: sent[0].id, error: { name: "InvalidStateError", message: "already registered" } });
  assert.ok((await second).response instanceof AuthenticatorAssertionResponse);
  await assert.rejects(first, (e) => e.name === "InvalidStateError");
});

test("replies that match nothing, or are not JSON, are ignored", async () => {
  const { sent, navigator, reply, raw } = page();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  raw("not json");
  raw("null");
  reply({ id: 9999, credential: assertion });
  reply({ id: sent[0].id, credential: assertion });
  assert.ok(await pending);
});

test("aborting rejects at once and tells the app to cancel", async () => {
  const { sent, navigator, reply } = page();
  const controller = new AbortController();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1) }, signal: controller.signal });
  controller.abort();
  await assert.rejects(pending, (e) => e.name === "AbortError");
  assert.deepEqual(sent[1], { id: sent[0].id, type: "cancel" });
  // The app's answer to a cancelled request arrives later and must go nowhere.
  reply({ id: sent[0].id, credential: assertion });
  await flush();
});

test("an already aborted signal never reaches the app", async () => {
  const { sent, navigator } = page();
  const pending = navigator.credentials.create({ publicKey: creation(), signal: AbortSignal.abort() });
  await assert.rejects(pending, (e) => e.name === "AbortError");
  assert.equal(sent.length, 0);
});

test("conditional mediation is refused without asking the app", async () => {
  const { sent, navigator } = page();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1) }, mediation: "conditional" });
  await assert.rejects(pending, TypeError);
  assert.equal(sent.length, 0);
});

test("missing required options are a TypeError without asking the app", async () => {
  const { sent, navigator } = page();
  const { challenge, ...noChallenge } = creation();
  await assert.rejects(navigator.credentials.create({ publicKey: noChallenge }), TypeError);
  await assert.rejects(navigator.credentials.get({ publicKey: {} }), TypeError);
  await assert.rejects(
    navigator.credentials.create({ publicKey: creation({ challenge: "not a buffer" }) }),
    TypeError,
  );
  assert.equal(sent.length, 0);
});

test("typed array views relay only the bytes they cover", () => {
  const { sent, navigator } = page();
  const backing = bytes(0, 1, 2, 3, 4);
  navigator.credentials.get({ publicKey: { challenge: new Uint8Array(backing.buffer, 1, 3) } });
  assert.equal(sent[0].options.challenge, b64url([1, 2, 3]));
});

test("requests for other credential types go to the browser's own implementation", async () => {
  const calls = [];
  const credentials = {
    create: (options) => (calls.push(["create", options]), Promise.resolve("native")),
    get: (options) => (calls.push(["get", options]), Promise.resolve("native")),
  };
  const { sent, navigator } = page({ credentials });
  assert.equal(await navigator.credentials.get({ password: true }), "native");
  assert.equal(calls.length, 1);
  assert.equal(sent.length, 0);
  assert.equal(navigator.credentials, credentials);
});

test("without navigator.credentials, one is provided", async () => {
  const { navigator } = page();
  assert.equal(typeof navigator.credentials.create, "function");
  await assert.rejects(navigator.credentials.get({ password: true }), (e) => e.name === "NotSupportedError");
});

test("JSON options parse into the binary form", () => {
  page();
  const creationOptions = PublicKeyCredential.parseCreationOptionsFromJSON({
    challenge: b64url([1, 2]),
    rp: { name: "demo" },
    user: { id: b64url([3]), name: "alice", displayName: "Alice" },
    pubKeyCredParams: [{ type: "public-key", alg: -7 }],
    excludeCredentials: [{ type: "public-key", id: b64url([4]) }],
  });
  same(creationOptions.challenge, [1, 2]);
  same(creationOptions.user.id, [3]);
  same(creationOptions.excludeCredentials[0].id, [4]);

  const requestOptions = PublicKeyCredential.parseRequestOptionsFromJSON({
    challenge: b64url([5]),
    allowCredentials: [{ type: "public-key", id: b64url([6]) }],
  });
  same(requestOptions.challenge, [5]);
  same(requestOptions.allowCredentials[0].id, [6]);
});

test("without the bridge the script changes nothing", () => {
  const { navigator } = page({ bridge: false });
  assert.equal(globalThis.PublicKeyCredential, undefined);
  assert.equal(navigator.credentials, undefined);
});

// --- PRF ---

test("a registration asking for PRF says so, and one that does not, does not", () => {
  const { sent, navigator } = page();
  navigator.credentials.create({ publicKey: creation({ extensions: { prf: {} } }) });
  navigator.credentials.create({ publicKey: creation() });
  assert.equal(sent[0].options.extensions.prf, true);
  assert.equal(sent[1].options.extensions.prf, false);
});

test("PRF inputs are relayed as base64url, both of them, and per passkey", () => {
  const { sent, navigator } = page();
  navigator.credentials.get({
    publicKey: {
      challenge: bytes(1),
      allowCredentials: [{ type: "public-key", id: bytes(0xaa) }],
      extensions: {
        prf: {
          eval: { first: bytes(1, 2), second: bytes(3).buffer },
          evalByCredential: { [b64url([0xaa])]: { first: bytes(4) } },
        },
      },
    },
  });
  const { prf } = sent[0].options.extensions;
  assert.deepEqual(prf.eval, { first: b64url([1, 2]), second: b64url([3]) });
  assert.deepEqual(prf.evalByCredential, { [b64url([0xaa])]: { first: b64url([4]) } });
});

test("a sign-in without PRF sends no PRF request", () => {
  const { sent, navigator } = page();
  navigator.credentials.get({ publicKey: { challenge: bytes(1) } });
  assert.equal("prf" in sent[0].options.extensions, false);
});

test("PRF inputs without a first are a TypeError without asking the app", async () => {
  const { sent, navigator } = page();
  const pending = navigator.credentials.get({
    publicKey: { challenge: bytes(1), extensions: { prf: { eval: { second: bytes(1) } } } },
  });
  await assert.rejects(pending, TypeError);
  assert.equal(sent.length, 0);
});

test("PRF results reach the page as ArrayBuffers, and toJSON keeps them as the app sent them", async () => {
  const { sent, navigator, reply } = page();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1), extensions: { prf: { eval: { first: bytes(1) } } } } });
  const withPrf = {
    ...assertion,
    clientExtensionResults: { prf: { results: { first: b64url([7, 7]), second: b64url([8]) } } },
  };
  reply({ id: sent[0].id, credential: withPrf });
  const credential = await pending;

  const { results } = credential.getClientExtensionResults().prf;
  same(results.first, [7, 7]);
  same(results.second, [8]);
  assert.deepEqual(credential.toJSON().clientExtensionResults, withPrf.clientExtensionResults);
});

test("a PRF answer without results stays without results", async () => {
  const { sent, navigator, reply } = page();
  const pending = navigator.credentials.get({ publicKey: { challenge: bytes(1), extensions: { prf: { eval: { first: bytes(1) } } } } });
  reply({ id: sent[0].id, credential: { ...assertion, clientExtensionResults: { prf: {} } } });
  assert.deepEqual((await pending).getClientExtensionResults(), { prf: {} });
});

test("registration reports whether PRF is enabled", async () => {
  const { sent, navigator, reply } = page();
  const pending = navigator.credentials.create({ publicKey: creation({ extensions: { prf: {} } }) });
  reply({ id: sent[0].id, credential: { ...registration, clientExtensionResults: { prf: { enabled: true } } } });
  assert.deepEqual((await pending).getClientExtensionResults(), { prf: { enabled: true } });
});

test("PRF is among the capabilities", async () => {
  page();
  assert.equal((await PublicKeyCredential.getClientCapabilities())["extension:prf"], true);
});

test("JSON options with PRF inputs parse into the binary form", () => {
  page();
  const options = PublicKeyCredential.parseRequestOptionsFromJSON({
    challenge: b64url([1]),
    extensions: {
      prf: { eval: { first: b64url([2]), second: b64url([3]) }, evalByCredential: { abc: { first: b64url([4]) } } },
    },
  });
  same(options.extensions.prf.eval.first, [2]);
  same(options.extensions.prf.eval.second, [3]);
  same(options.extensions.prf.evalByCredential.abc.first, [4]);

  const creationOptions = PublicKeyCredential.parseCreationOptionsFromJSON({
    challenge: b64url([1]),
    rp: { name: "demo" },
    user: { id: b64url([3]), name: "a", displayName: "A" },
    pubKeyCredParams: [],
    extensions: { prf: {}, credProps: true },
  });
  assert.deepEqual(creationOptions.extensions, { prf: {}, credProps: true });
});
