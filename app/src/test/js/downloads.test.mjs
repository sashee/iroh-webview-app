// Tests for app/src/main/assets/downloads.js, the script that sends the files
// a page saves (links to its blob: URLs) to the app, which the WebView's own
// download listener cannot fetch.
//
// Each run gets a fresh realm with fake links and blob: URLs, and an app that
// answers as PageDownloads does, once it has "written" what it got.
//
//   node --test app/src/test/js/downloads.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  process.env.DOWNLOADS_JS ?? new URL("../../main/assets/downloads.js", import.meta.url),
  "utf8",
);

const MiB = 1024 * 1024;

/** Run the script in a fresh page. `refuse` makes the app refuse every file. */
function page({ bridge = true, refuse = false } = {}) {
  const windowListeners = [];
  // What the browser itself would have downloaded: links whose click nobody cancelled.
  const browserDownloads = [];
  // The browser's blob: URLs.
  const registry = new Map();
  let made = 0;

  class Event {
    constructor(target, path) {
      this.target = target;
      this.path = path;
      this.defaultPrevented = false;
    }
    preventDefault() {
      this.defaultPrevented = true;
    }
    composedPath() {
      return this.path;
    }
  }

  class HTMLAnchorElement {
    constructor() {
      this.attributes = new Map();
      this.listeners = [];
      this.href = "";
      this.isConnected = false;
    }
    hasAttribute(name) {
      return this.attributes.has(name);
    }
    getAttribute(name) {
      return this.attributes.get(name) ?? null;
    }
    setAttribute(name, value) {
      this.attributes.set(name, String(value));
    }
    addEventListener(type, listener) {
      if (type === "click") this.listeners.push(listener);
    }
    removeEventListener(type, listener) {
      this.listeners = this.listeners.filter((l) => l !== listener);
    }
    /** A click: the link's own listeners, then, for a link in the page, the window's. */
    click() {
      const event = new Event(this, this.isConnected ? [this, "document", "window"] : [this]);
      [...this.listeners].forEach((listener) => listener(event));
      if (this.isConnected) [...windowListeners].forEach((listener) => listener(event));
      if (!event.defaultPrevented) browserDownloads.push(this.href);
    }
  }

  const URL = {
    createObjectURL(blob) {
      const url = `blob:http://a1b2c3d4e5f60718.localhost:24680/${++made}`;
      registry.set(url, blob);
      return url;
    },
    revokeObjectURL(url) {
      registry.delete(url);
    },
  };

  const fetch = async (url) => {
    const blob = registry.get(url);
    if (!blob) throw new TypeError("Failed to fetch");
    return { blob: async () => blob };
  };

  // The app: what it got, in order, and the files it saved.
  const sent = [];
  const saved = [];
  const listeners = [];
  // Set while an answer is owed: a message then would be one the app never asked for.
  let owed = false;
  const overrun = [];
  let file = null;
  const answer = (message) => setImmediate(() => {
    owed = false;
    listeners.forEach((listener) => listener({ data: JSON.stringify(message) }));
  });
  function receive(message) {
    if (owed) overrun.push(message);
    owed = true;
    if (typeof message !== "string") {
      const bytes = new Uint8Array(message);
      sent.push(bytes);
      file.bytes.push(bytes);
      file.received += bytes.length;
      return answer({ id: file.id, received: file.received });
    }
    const parsed = JSON.parse(message);
    sent.push(parsed);
    if (parsed.start) {
      if (refuse) return answer({ id: parsed.id, error: "No." });
      file = { id: parsed.id, name: parsed.start.name, received: 0, bytes: [] };
      return answer({ id: parsed.id, received: 0 });
    }
    if (parsed.end) {
      saved.push({ name: file.name, bytes: Buffer.concat(file.bytes) });
      return answer({ id: parsed.id, saved: file.name });
    }
    owed = false;
  }

  const warnings = [];
  const pageClick = HTMLAnchorElement.prototype.click;
  const realm = vm.createContext({
    Blob,
    URL,
    fetch,
    HTMLAnchorElement,
    console: { warn: (...args) => warnings.push(args) },
    addEventListener: (type, listener) => type === "click" && windowListeners.push(listener),
  });
  if (bridge) {
    realm.__irohDownloads = {
      postMessage: receive,
      addEventListener: (type, listener) => type === "message" && listeners.push(listener),
    };
  }
  vm.runInContext(source, realm);

  /** A link to `blob`, as a page makes one to save it. */
  const link = (blob, { name = "song.mp3", connected = false, download = true } = {}) => {
    const anchor = new HTMLAnchorElement();
    anchor.href = realm.URL.createObjectURL(blob);
    if (download) anchor.setAttribute("download", name);
    anchor.isConnected = connected;
    return anchor;
  };

  return { realm, URL, registry, HTMLAnchorElement, pageClick, link, sent, saved, overrun, browserDownloads, warnings };
}

/** Bytes 0, 1, 2… of length `size`. */
const counting = (size) => Uint8Array.from({ length: size }, (_, i) => i % 251);

/** Let the script and the app go back and forth until `done`. */
async function until(done) {
  for (let i = 0; i < 1000 && !done(); i++) await new Promise((resolve) => setImmediate(resolve));
  assert.ok(done(), "it never got there");
}

const messages = (sent) => sent.map((m) => (m instanceof Uint8Array ? m.length : m));

test("a link's click() sends the file in pieces, each once the last was written, and the browser saves nothing", async () => {
  const p = page();
  const bytes = counting(2.5 * MiB);
  p.link(new Blob([bytes])).click();
  await until(() => p.saved.length === 1);

  assert.deepEqual(messages(p.sent), [{ id: 1, start: { name: "song.mp3", size: 2.5 * MiB } }, MiB, MiB, MiB / 2, { id: 1, end: true }]);
  assert.deepEqual(p.saved[0].name, "song.mp3");
  assert.ok(Buffer.from(bytes).equals(p.saved[0].bytes));
  assert.deepEqual(p.overrun, []);
  assert.deepEqual(p.browserDownloads, []);
});

test("a link in the page, clicked, is saved the same way", async () => {
  const p = page();
  p.link(new Blob(["hello"]), { name: "a.txt", connected: true }).click();
  await until(() => p.saved.length === 1);
  assert.equal(p.saved[0].bytes.toString(), "hello");
  assert.deepEqual(p.browserDownloads, []);
});

test("a URL revoked as soon as click() returns still saves", async () => {
  const p = page();
  const anchor = p.link(new Blob(["hello"]));
  anchor.click();
  p.realm.URL.revokeObjectURL(anchor.href);
  assert.equal(p.registry.size, 0);
  await until(() => p.saved.length === 1);
  assert.equal(p.saved[0].bytes.toString(), "hello");
});

test("an empty file is saved", async () => {
  const p = page();
  p.link(new Blob([]), { name: "empty" }).click();
  await until(() => p.saved.length === 1);
  assert.deepEqual(messages(p.sent), [{ id: 1, start: { name: "empty", size: 0 } }, { id: 1, end: true }]);
});

test("a click the page cancels, and links that save no blob, are left to the browser", async () => {
  const p = page();
  const cancelled = p.link(new Blob(["x"]));
  cancelled.addEventListener("click", (event) => event.preventDefault());
  cancelled.click();

  const plain = p.link(new Blob(["x"]), { download: false });
  plain.click();
  const remote = new p.HTMLAnchorElement();
  remote.href = "http://a1b2c3d4e5f60718.localhost:24680/export.csv";
  remote.setAttribute("download", "export.csv");
  remote.click();

  await until(() => true);
  assert.deepEqual(p.sent, []);
  assert.deepEqual(p.browserDownloads, [plain.href, remote.href]);
});

test("a refused file sends nothing more", async () => {
  const p = page({ refuse: true });
  p.link(new Blob([counting(10)])).click();
  await until(() => p.sent.length === 1);
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(p.sent.length, 1);
});

test("files go one at a time, in the order they were clicked", async () => {
  const p = page();
  p.link(new Blob(["one"]), { name: "1" }).click();
  p.link(new Blob(["two"]), { name: "2" }).click();
  await until(() => p.saved.length === 2);
  assert.deepEqual(p.saved.map((f) => [f.name, f.bytes.toString()]), [["1", "one"], ["2", "two"]]);
  assert.deepEqual(p.overrun, []);
  assert.deepEqual(messages(p.sent).map((m) => (typeof m === "number" ? "piece" : m.id)), [1, "piece", 1, 2, "piece", 2]);
});

test("a blob: URL the page made elsewhere is fetched", async () => {
  const p = page();
  const anchor = new p.HTMLAnchorElement();
  anchor.href = "blob:http://a1b2c3d4e5f60718.localhost:24680/from-a-worker";
  anchor.setAttribute("download", "w.txt");
  p.registry.set(anchor.href, new Blob(["made elsewhere"]));
  anchor.click();
  await until(() => p.saved.length === 1);
  assert.equal(p.saved[0].bytes.toString(), "made elsewhere");
});

test("a link to a blob that is gone sends nothing", async () => {
  const p = page();
  const anchor = new p.HTMLAnchorElement();
  anchor.href = "blob:http://a1b2c3d4e5f60718.localhost:24680/gone";
  anchor.setAttribute("download", "gone.txt");
  anchor.click();
  await until(() => p.warnings.length === 1);
  assert.deepEqual(p.sent, []);
  assert.deepEqual(p.browserDownloads, []);
});

test("a file that cannot be read on is abandoned, and the app told", async () => {
  const p = page();
  const realm = p.realm;
  // A Blob whose bytes are gone once it is started.
  const Broken = vm.runInContext(
    `(class extends Blob { slice() { return { arrayBuffer: () => Promise.reject(new Error("gone")) }; } })`,
    realm,
  );
  p.link(new Broken(["xyz"])).click();
  await until(() => p.sent.length === 2);
  assert.deepEqual(p.sent[1], { id: 1, abort: true });
});

test("without the bridge, the page is left as it was", () => {
  const p = page({ bridge: false });
  assert.equal(p.HTMLAnchorElement.prototype.click, p.pageClick);
  const anchor = p.link(new Blob(["x"]));
  anchor.click();
  assert.deepEqual(p.browserDownloads, [anchor.href]);
});
