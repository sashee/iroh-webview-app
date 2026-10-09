// Tests for app/src/main/assets/no-webrtc.js, the script that takes WebRTC
// away from pages.
//
// Each run gets a fresh realm whose WebRTC constructors are defined the way
// the WebView defines them -- configurable, not enumerable -- since that is
// what decides whether `delete` can remove them.
//
//   node --test app/src/test/js/no-webrtc.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  process.env.NO_WEBRTC_JS ?? new URL("../../main/assets/no-webrtc.js", import.meta.url),
  "utf8",
);

/** Run the script in a fresh page with [globals] installed as interface objects. */
function page(globals) {
  const realm = vm.createContext({});
  for (const [name, value] of Object.entries(globals)) {
    Object.defineProperty(realm, name, { value, configurable: true, writable: true, enumerable: false });
  }
  vm.runInContext(source, realm);
  return realm;
}

const constructor = () => class {};

test("the peer connection constructors are gone", () => {
  const realm = page({ RTCPeerConnection: constructor(), webkitRTCPeerConnection: constructor() });
  assert.equal(vm.runInContext("typeof RTCPeerConnection", realm), "undefined");
  assert.equal(vm.runInContext("typeof webkitRTCPeerConnection", realm), "undefined");
  assert.equal(vm.runInContext("'RTCPeerConnection' in globalThis", realm), false);
});

test("the rest of the page is left alone", () => {
  const WebSocket = constructor();
  const RTCSessionDescription = constructor();
  const realm = page({ RTCPeerConnection: constructor(), WebSocket, RTCSessionDescription });
  assert.equal(realm.WebSocket, WebSocket);
  assert.equal(realm.RTCSessionDescription, RTCSessionDescription);
});

test("a page without WebRTC is no trouble", () => {
  assert.doesNotThrow(() => page({}));
});

test("it leaves nothing of its own behind", () => {
  const realm = page({ RTCPeerConnection: constructor() });
  assert.deepEqual(Object.getOwnPropertyNames(realm), []);
});
