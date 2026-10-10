// Copying from pages, marked sensitive.
//
// The app installs this at document start, for the endpoint's own origin only
// (WebViewCompat.addDocumentStartJavaScript), next to a WebMessageListener
// named __irohClipboard. The WebView writes the clipboard without Android's
// sensitivity flag, so a password a page copies would show in the copy
// preview and stay in the keyboard's clipboard history. This hands the text
// to the app instead, which writes it with the flag set.
//
// It covers navigator.clipboard.writeText, and write() of a single item with
// text/plain, of which only the text is copied. Every other write, copying a
// selection (long-press, Copy) and document.execCommand("copy") still go
// through the WebView, unmarked.
//
// Nothing here is trusted: the app checks the origin, and that the page is on
// screen, itself.
(() => {
  "use strict";

  const bridge = globalThis.__irohClipboard;
  const clipboard = globalThis.navigator && navigator.clipboard;
  if (!bridge || typeof bridge.postMessage !== "function" || !clipboard) return;

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

  function failure({ name, message }) {
    return name === "TypeError" ? new TypeError(message) : new DOMException(message, name || "UnknownError");
  }

  function copy(text) {
    return new Promise((resolve, reject) => {
      const id = nextId++;
      waiting.set(id, (reply) => (reply.error ? reject(failure(reply.error)) : resolve()));
      bridge.postMessage(JSON.stringify({ id, text }));
    });
  }

  function writeText(data) {
    if (arguments.length === 0) return Promise.reject(new TypeError("writeText needs the text to copy."));
    return copy(String(data));
  }

  const method = (value) => ({ value, writable: true, configurable: true, enumerable: false });

  Object.defineProperty(clipboard, "writeText", method(writeText));

  if (typeof clipboard.write === "function") {
    const original = clipboard.write.bind(clipboard);
    const write = async (items) => {
      const list = Array.from(items);
      const item = list.length === 1 && list[0].types.includes("text/plain") ? list[0] : null;
      if (!item) return original(items);
      const blob = await item.getType("text/plain");
      return copy(await blob.text());
    };
    Object.defineProperty(clipboard, "write", method(write));
  }
})();
