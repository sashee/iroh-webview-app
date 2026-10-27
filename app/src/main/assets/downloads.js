// Saving the files a page holds, as downloads.
//
// The app installs this at document start, for the endpoint's own origin only
// (WebViewCompat.addDocumentStartJavaScript), next to a WebMessageListener
// named __irohDownloads. A page saves a file it holds -- one it decrypted,
// say -- the way browsers expect: a link with a download attribute to a blob:
// URL, clicked. The WebView hands that to the app's download listener, whose
// system downloader can only fetch from the network, and a blob: URL lives
// only in the page. So this takes the click instead, and sends the Blob to
// the app in pieces, each once the app has written the one before. The app
// saves it in Downloads.
//
// It covers a link in the page, clicked or click()ed, and a link outside the
// page click()ed, which is how most pages save. A click dispatched on a link
// outside the page (dispatchEvent) still goes to the WebView, which says the
// download could not be started.
//
// Nothing here is trusted: the app checks the origin, the name and the sizes.
(() => {
  "use strict";

  const bridge = globalThis.__irohDownloads;
  const Anchor = globalThis.HTMLAnchorElement;
  const urls = globalThis.URL;
  if (!bridge || typeof bridge.postMessage !== "function" || typeof Anchor !== "function") return;
  if (!urls || typeof urls.createObjectURL !== "function" || typeof globalThis.Blob !== "function") return;

  // The size of the pieces sent: the app takes up to 4 MiB.
  const PIECE = 1024 * 1024;

  const method = (value) => ({ value, writable: true, configurable: true, enumerable: false });

  // ---------------------------------------------------------------- the page's blob: URLs

  // The Blob each blob: URL the page made stands for, until it revokes it:
  // what the browser keeps alive anyway. Kept so a link's file is read from
  // the Blob itself, and so a URL revoked as soon as click() returns, as
  // pages do, still saves.
  const made = new Map();
  const create = urls.createObjectURL;
  const revoke = urls.revokeObjectURL;
  Object.defineProperty(
    urls,
    "createObjectURL",
    method(function createObjectURL(object) {
      const url = create.call(this, object);
      if (object instanceof globalThis.Blob) made.set(url, object);
      return url;
    }),
  );
  if (typeof revoke === "function") {
    Object.defineProperty(
      urls,
      "revokeObjectURL",
      method(function revokeObjectURL(url) {
        made.delete(String(url));
        return revoke.call(this, url);
      }),
    );
  }

  /** The Blob at a blob: URL: the page's own, else fetched now, before the page can revoke it. */
  function blobAt(url) {
    if (made.has(url)) return Promise.resolve(made.get(url));
    return globalThis.fetch(url).then((response) => response.blob());
  }

  // ---------------------------------------------------------------- the app's side

  let nextId = 1;
  // The answer the app owes, by the file's id.
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

  /** Posts `message` about the file `id`, and waits for the app's answer. */
  const ask = (id, message) =>
    new Promise((resolve) => {
      waiting.set(id, resolve);
      bridge.postMessage(message);
    });

  const going = (answer) => typeof answer.received === "number" && !("error" in answer);

  /** Sends one file: its name and size, its pieces from where the app says it is, then the end. */
  async function send(reading, name) {
    let blob;
    try {
      blob = await reading;
    } catch (error) {
      console.warn("Could not read the file to save", error);
      return;
    }
    const id = nextId++;
    let answer = await ask(id, JSON.stringify({ id, start: { name, size: blob.size } }));
    while (going(answer) && answer.received < blob.size) {
      let piece;
      try {
        piece = await blob.slice(answer.received, answer.received + PIECE).arrayBuffer();
      } catch (error) {
        console.warn("Could not read the file to save", error);
        bridge.postMessage(JSON.stringify({ id, abort: true }));
        return;
      }
      answer = await ask(id, piece);
    }
    if (going(answer)) await ask(id, JSON.stringify({ id, end: true }));
  }

  // One file at a time, in the order they were clicked. Each is read at once.
  let queue = Promise.resolve();
  function save(link) {
    const reading = blobAt(link.href);
    reading.catch(() => {});
    const name = link.getAttribute("download") || "";
    queue = queue.then(() => send(reading, name)).catch((error) => console.warn("Could not save the file", error));
  }

  // ---------------------------------------------------------------- the clicks

  const saves = (link) => link.hasAttribute("download") && String(link.href).startsWith("blob:");

  function onClick(event) {
    if (event.defaultPrevented) return;
    const path = typeof event.composedPath === "function" ? event.composedPath() : [event.target];
    const link = path.find((target) => target instanceof Anchor);
    if (!link || !saves(link)) return;
    event.preventDefault();
    save(link);
  }

  // As a click bubbles out to the window: after the page's handlers on the
  // link and the document, which may cancel it.
  globalThis.addEventListener("click", onClick);

  // A link outside the page: its click reaches no listener but the link's
  // own, so for click() this listens there too, after the page's.
  const click = Anchor.prototype.click;
  Object.defineProperty(
    Anchor.prototype,
    "click",
    method(function () {
      if (this.isConnected) return click.call(this);
      this.addEventListener("click", onClick);
      try {
        return click.call(this);
      } finally {
        this.removeEventListener("click", onClick);
      }
    }),
  );
})();
