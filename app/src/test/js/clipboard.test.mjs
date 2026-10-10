// Tests for app/src/main/assets/clipboard.js, the script that copies through
// the app so the clipboard marks the text sensitive.
//
// Each run gets a fresh realm with a fake bridge and a fake navigator.clipboard
// whose own write records what still reaches the browser.
//
//   node --test app/src/test/js/clipboard.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  process.env.CLIPBOARD_JS ?? new URL("../../main/assets/clipboard.js", import.meta.url),
  "utf8",
);

/** Run the script in a fresh page. */
function page({ bridge = true } = {}) {
  const sent = [];
  const listeners = [];
  const browserWrites = [];
  const clipboard = {
    writeText: () => Promise.reject(new Error("the browser's writeText was called")),
    write: (items) => {
      browserWrites.push(items);
      return Promise.resolve();
    },
    readText: () => Promise.resolve(""),
  };
  const realm = vm.createContext({ DOMException, navigator: { clipboard } });
  if (bridge) {
    realm.__irohClipboard = {
      postMessage: (message) => sent.push(JSON.parse(message)),
      addEventListener: (type, listener) => type === "message" && listeners.push(listener),
    };
  }
  vm.runInContext(source, realm);
  return {
    clipboard,
    sent,
    browserWrites,
    reply: (message) => listeners.forEach((listener) => listener({ data: JSON.stringify(message) })),
    raw: (data) => listeners.forEach((listener) => listener({ data })),
  };
}

/** A ClipboardItem as the page would build one, holding [types] as strings. */
const item = (types) => ({
  types: Object.keys(types),
  getType: (type) => Promise.resolve({ text: () => Promise.resolve(types[type]) }),
});

/** Let the script's awaits run. */
const settled = () => new Promise((resolve) => setImmediate(resolve));

test("writeText hands the text to the app and resolves once it is copied", async () => {
  const { clipboard, sent, reply } = page();
  let done = false;
  const copying = clipboard.writeText("hunter2").then(() => (done = true));

  assert.deepEqual(sent, [{ id: 1, text: "hunter2" }]);
  await settled();
  assert.equal(done, false);

  reply({ id: 1 });
  await copying;
  assert.equal(done, true);
});

test("a refusal rejects with the exception a browser would throw", async () => {
  const { clipboard, reply } = page();
  const copying = clipboard.writeText("hunter2");
  reply({ id: 1, error: { name: "NotAllowedError", message: "Document is not focused." } });
  await assert.rejects(copying, (error) => {
    assert.ok(error instanceof DOMException);
    assert.equal(error.name, "NotAllowedError");
    assert.equal(error.message, "Document is not focused.");
    return true;
  });
});

test("a TypeError from the app is a real TypeError", async () => {
  const { clipboard, reply } = page();
  const copying = clipboard.writeText("x");
  reply({ id: 1, error: { name: "TypeError", message: "No text to copy." } });
  await assert.rejects(copying, { name: "TypeError", message: "No text to copy." });
});

test("writeText takes any value as a string, as a browser does, but not nothing", async () => {
  const { clipboard, sent } = page();
  clipboard.writeText(42);
  clipboard.writeText(null);
  await assert.rejects(clipboard.writeText(), { name: "TypeError" });
  assert.deepEqual(sent.map((m) => m.text), ["42", "null"]);
});

test("replies are matched to their writes by id", async () => {
  const { clipboard, sent, reply } = page();
  const first = clipboard.writeText("one");
  const second = clipboard.writeText("two");
  assert.deepEqual(sent.map((m) => m.id), [1, 2]);

  reply({ id: 2, error: { name: "NotAllowedError", message: "no" } });
  reply({ id: 1 });
  await first;
  await assert.rejects(second, { name: "NotAllowedError" });
});

test("stray and malformed replies are ignored", async () => {
  const { clipboard, reply, raw } = page();
  const copying = clipboard.writeText("x");
  raw("not json");
  raw("null");
  reply({ id: 99 });
  reply({ id: 1 });
  await copying;
});

test("write of one item with text copies its text through the app", async () => {
  const { clipboard, sent, reply, browserWrites } = page();
  const copying = clipboard.write([item({ "text/plain": "hunter2", "text/html": "<b>hunter2</b>" })]);
  await settled();

  assert.deepEqual(sent, [{ id: 1, text: "hunter2" }]);
  reply({ id: 1 });
  await copying;
  assert.equal(browserWrites.length, 0);
});

test("any other write goes to the browser, unmarked", async () => {
  const { clipboard, sent, browserWrites } = page();
  const image = [item({ "image/png": "…" })];
  const two = [item({ "text/plain": "a" }), item({ "text/plain": "b" })];
  await clipboard.write(image);
  await clipboard.write(two);
  assert.deepEqual(browserWrites, [image, two]);
  assert.deepEqual(sent, []);
});

test("write of something that is not a list rejects", async () => {
  const { clipboard, sent } = page();
  await assert.rejects(clipboard.write(undefined), { name: "TypeError" });
  assert.deepEqual(sent, []);
});

test("without the bridge, the page's clipboard is untouched", async () => {
  const { clipboard, browserWrites } = page({ bridge: false });
  await assert.rejects(clipboard.writeText("x"), /the browser's writeText was called/);
  await clipboard.write([item({ "text/plain": "x" })]);
  assert.equal(browserWrites.length, 1);
});

test("a page without a clipboard is no trouble", () => {
  const realm = vm.createContext({ DOMException, navigator: {} });
  realm.__irohClipboard = { postMessage() {}, addEventListener() {} };
  assert.doesNotThrow(() => vm.runInContext(source, realm));
});
