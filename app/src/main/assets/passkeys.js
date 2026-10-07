// Passkeys for the pages of the endpoint being browsed.
//
// The app installs this at document start, for the endpoint's own origin only
// (WebViewCompat.addDocumentStartJavaScript), next to a WebMessageListener
// named __irohPasskeys. It replaces the WebAuthn entry points with ones that
// relay to the app, where the passkey's key lives in the phone's secure
// hardware and only signs after a fingerprint.
//
// This file only translates: BufferSources become base64url on the way out
// and ArrayBuffers again on the way back, and error names become the
// exceptions a browser would throw. Every decision -- which RP ID, which
// passkey, whether the user agreed -- is made by the app, which knows the real
// origin. Nothing here is trusted: the page can read and replace all of it,
// and gains nothing by doing so that it could not get by calling the bridge
// itself.
(() => {
  "use strict";

  const bridge = globalThis.__irohPasskeys;
  if (!bridge || typeof bridge.postMessage !== "function") return;

  // --- base64url -----------------------------------------------------------

  const isArrayBuffer = (value) => Object.prototype.toString.call(value) === "[object ArrayBuffer]";

  function bytesOf(source, what) {
    if (isArrayBuffer(source)) return new Uint8Array(source);
    if (ArrayBuffer.isView(source)) return new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
    throw new TypeError(`${what} must be an ArrayBuffer or a typed array`);
  }

  function toBase64Url(source, what) {
    const bytes = bytesOf(source, what);
    let binary = "";
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function fromBase64Url(text, what) {
    if (typeof text !== "string") throw new TypeError(`${what} must be a base64url string`);
    const base64 = text.replace(/-/g, "+").replace(/_/g, "/");
    const binary = atob(base64 + "=".repeat((4 - (base64.length % 4)) % 4));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  // --- options, page to app ------------------------------------------------

  function required(value, what) {
    if (value === undefined || value === null) throw new TypeError(`${what} is required`);
    return value;
  }

  const descriptorJSON = (what) => (descriptor) => ({
    type: String(descriptor.type),
    id: toBase64Url(required(descriptor.id, `${what}.id`), `${what}.id`),
    transports: descriptor.transports ? Array.from(descriptor.transports, String) : undefined,
  });

  function creationOptionsJSON(options) {
    const rp = required(options.rp, "rp");
    const user = required(options.user, "user");
    const selection = options.authenticatorSelection;
    return {
      rp: { id: rp.id === undefined ? undefined : String(rp.id), name: String(rp.name ?? "") },
      user: {
        id: toBase64Url(required(user.id, "user.id"), "user.id"),
        name: String(user.name ?? ""),
        displayName: String(user.displayName ?? ""),
      },
      challenge: toBase64Url(required(options.challenge, "challenge"), "challenge"),
      pubKeyCredParams: Array.from(required(options.pubKeyCredParams, "pubKeyCredParams"), (p) => ({
        type: String(p.type),
        alg: Number(p.alg),
      })),
      excludeCredentials: Array.from(options.excludeCredentials || [], descriptorJSON("excludeCredentials")),
      authenticatorSelection: selection
        ? {
            authenticatorAttachment: selection.authenticatorAttachment,
            residentKey: selection.residentKey,
            requireResidentKey: selection.requireResidentKey,
            userVerification: selection.userVerification,
          }
        : undefined,
      attestation: options.attestation === undefined ? "none" : String(options.attestation),
      extensions: { credProps: Boolean(options.extensions && options.extensions.credProps) },
    };
  }

  function requestOptionsJSON(options) {
    return {
      challenge: toBase64Url(required(options.challenge, "challenge"), "challenge"),
      rpId: options.rpId === undefined ? undefined : String(options.rpId),
      allowCredentials: Array.from(options.allowCredentials || [], descriptorJSON("allowCredentials")),
      userVerification: options.userVerification,
    };
  }

  // --- credentials, app to page --------------------------------------------

  // The classes the page sees. Constructible only from here, as in a browser,
  // so `instanceof PublicKeyCredential` means what a site expects it to.
  const internal = Symbol("passkey bridge");
  const illegal = () => new TypeError("Illegal constructor");

  class AuthenticatorResponse {
    #clientDataJSON;
    constructor(key, json) {
      if (key !== internal) throw illegal();
      this.#clientDataJSON = fromBase64Url(json.clientDataJSON, "clientDataJSON");
    }
    get clientDataJSON() {
      return this.#clientDataJSON;
    }
  }

  class AuthenticatorAttestationResponse extends AuthenticatorResponse {
    #attestationObject;
    #authenticatorData;
    #publicKey;
    #publicKeyAlgorithm;
    #transports;
    constructor(key, json) {
      super(key, json);
      this.#attestationObject = fromBase64Url(json.attestationObject, "attestationObject");
      this.#authenticatorData = fromBase64Url(json.authenticatorData, "authenticatorData");
      this.#publicKey = json.publicKey ? fromBase64Url(json.publicKey, "publicKey") : null;
      this.#publicKeyAlgorithm = json.publicKeyAlgorithm;
      this.#transports = Array.from(json.transports || []);
    }
    get attestationObject() {
      return this.#attestationObject;
    }
    getAuthenticatorData() {
      return this.#authenticatorData;
    }
    getPublicKey() {
      return this.#publicKey;
    }
    getPublicKeyAlgorithm() {
      return this.#publicKeyAlgorithm;
    }
    getTransports() {
      return [...this.#transports];
    }
  }

  class AuthenticatorAssertionResponse extends AuthenticatorResponse {
    #authenticatorData;
    #signature;
    #userHandle;
    constructor(key, json) {
      super(key, json);
      this.#authenticatorData = fromBase64Url(json.authenticatorData, "authenticatorData");
      this.#signature = fromBase64Url(json.signature, "signature");
      this.#userHandle = json.userHandle ? fromBase64Url(json.userHandle, "userHandle") : null;
    }
    get authenticatorData() {
      return this.#authenticatorData;
    }
    get signature() {
      return this.#signature;
    }
    get userHandle() {
      return this.#userHandle;
    }
  }

  const copy = (json) => JSON.parse(JSON.stringify(json));

  function parsedDescriptors(list, what) {
    return Array.from(list || [], (d) => ({ ...d, id: fromBase64Url(d.id, `${what}.id`) }));
  }

  class PublicKeyCredential {
    #json;
    #rawId;
    #response;
    constructor(key, json, response) {
      if (key !== internal) throw illegal();
      this.#json = json;
      this.#rawId = fromBase64Url(json.rawId, "rawId");
      this.#response = response;
    }
    get id() {
      return this.#json.id;
    }
    get type() {
      return "public-key";
    }
    get rawId() {
      return this.#rawId;
    }
    get response() {
      return this.#response;
    }
    get authenticatorAttachment() {
      return this.#json.authenticatorAttachment ?? null;
    }
    getClientExtensionResults() {
      return copy(this.#json.clientExtensionResults || {});
    }
    toJSON() {
      return copy(this.#json);
    }

    static isUserVerifyingPlatformAuthenticatorAvailable() {
      return Promise.resolve(true);
    }
    // No autofill-style sign-in: there is no field UI to attach it to.
    static isConditionalMediationAvailable() {
      return Promise.resolve(false);
    }
    static getClientCapabilities() {
      return Promise.resolve({
        conditionalCreate: false,
        conditionalGet: false,
        hybridTransport: false,
        passkeyPlatformAuthenticator: true,
        userVerifyingPlatformAuthenticator: true,
        relatedOrigins: false,
        signalAllAcceptedCredentials: false,
        signalCurrentUserDetails: false,
        signalUnknownCredential: false,
        "extension:credProps": true,
      });
    }
    static parseCreationOptionsFromJSON(json) {
      return {
        ...json,
        challenge: fromBase64Url(json.challenge, "challenge"),
        user: { ...json.user, id: fromBase64Url(json.user.id, "user.id") },
        excludeCredentials: parsedDescriptors(json.excludeCredentials, "excludeCredentials"),
      };
    }
    static parseRequestOptionsFromJSON(json) {
      return {
        ...json,
        challenge: fromBase64Url(json.challenge, "challenge"),
        allowCredentials: parsedDescriptors(json.allowCredentials, "allowCredentials"),
      };
    }
  }

  const registered = (json) =>
    new PublicKeyCredential(internal, json, new AuthenticatorAttestationResponse(internal, json.response));
  const asserted = (json) =>
    new PublicKeyCredential(internal, json, new AuthenticatorAssertionResponse(internal, json.response));

  // --- the relay -----------------------------------------------------------

  let nextId = 1;
  const waiting = new Map();

  function onReply(event) {
    let reply;
    try {
      reply = JSON.parse(event.data);
    } catch {
      return;
    }
    const settle = waiting.get(reply && reply.id);
    if (!settle) return;
    waiting.delete(reply.id);
    settle(reply);
  }

  if (typeof bridge.addEventListener === "function") bridge.addEventListener("message", onReply);
  else bridge.onmessage = onReply;

  const aborted = (signal) =>
    signal.reason !== undefined ? signal.reason : new DOMException("The operation was aborted.", "AbortError");

  function failure({ name, message }) {
    return name === "TypeError" ? new TypeError(message) : new DOMException(message, name || "UnknownError");
  }

  function relay(type, options, signal, build) {
    return new Promise((resolve, reject) => {
      if (signal && signal.aborted) return reject(aborted(signal));
      const id = nextId++;
      const onAbort = () => {
        waiting.delete(id);
        bridge.postMessage(JSON.stringify({ id, type: "cancel" }));
        reject(aborted(signal));
      };
      if (signal) signal.addEventListener("abort", onAbort, { once: true });
      waiting.set(id, (reply) => {
        if (signal) signal.removeEventListener("abort", onAbort);
        if (reply.error) return reject(failure(reply.error));
        try {
          resolve(build(reply.credential));
        } catch (error) {
          reject(error);
        }
      });
      bridge.postMessage(JSON.stringify({ id, type, options }));
    });
  }

  // --- navigator.credentials -----------------------------------------------

  const container = navigator.credentials;
  const original = {
    create: container && typeof container.create === "function" ? container.create.bind(container) : null,
    get: container && typeof container.get === "function" ? container.get.bind(container) : null,
  };
  const unsupported = () =>
    Promise.reject(new DOMException("Only public key credentials are supported.", "NotSupportedError"));

  function create(options = {}) {
    if (!options.publicKey) return original.create ? original.create(options) : unsupported();
    let json;
    try {
      json = creationOptionsJSON(options.publicKey);
    } catch (error) {
      return Promise.reject(error);
    }
    return relay("create", json, options.signal, registered);
  }

  function get(options = {}) {
    if (!options.publicKey) return original.get ? original.get(options) : unsupported();
    // What the Credential Management spec prescribes when conditional
    // mediation is not supported, and what keeps a site that asks for it on
    // page load from raising a fingerprint prompt nobody asked for.
    if (options.mediation === "conditional") {
      return Promise.reject(new TypeError("Conditional mediation is not supported."));
    }
    let json;
    try {
      json = requestOptionsJSON(options.publicKey);
    } catch (error) {
      return Promise.reject(error);
    }
    return relay("get", json, options.signal, asserted);
  }

  const method = (value) => ({ value, writable: true, configurable: true, enumerable: false });

  if (container) {
    Object.defineProperties(container, { create: method(create), get: method(get) });
  } else {
    Object.defineProperty(navigator, "credentials", {
      value: { create, get, store: unsupported, preventSilentAccess: () => Promise.resolve() },
      configurable: true,
    });
  }

  for (const [name, value] of Object.entries({
    PublicKeyCredential,
    AuthenticatorResponse,
    AuthenticatorAttestationResponse,
    AuthenticatorAssertionResponse,
  })) {
    Object.defineProperty(globalThis, name, method(value));
  }
})();
