// The page's Media Session, relayed to the app, so that Android shows what is
// playing: a notification with its buttons, the lock screen, and the
// headset's buttons.
//
// The app installs this at document start, for the endpoint's own origin only
// (WebViewCompat.addDocumentStartJavaScript), next to a WebMessageListener
// named __irohMediaSession. The WebView keeps navigator.mediaSession as a
// browser does, but shows it nowhere. This keeps what the page sets -- its
// metadata, playback state, position and action handlers -- tells the app
// whenever any of it changes, and calls the page's handler when the app sends
// back a button the user pressed.
//
// A page that never sets metadata sends nothing, so nothing is shown for it.
// Only the top frame takes part. Nothing here is trusted: the app checks the
// origin, and reads only titles, a state, a position and which buttons to show.
(() => {
  "use strict";

  const bridge = globalThis.__irohMediaSession;
  const navigator = globalThis.navigator;
  if (!bridge || typeof bridge.postMessage !== "function" || !navigator) return;
  if (globalThis.top !== undefined && globalThis.top !== globalThis) return;

  // The buttons the app can show, by the names the Media Session API gives them.
  const ACTIONS = ["play", "pause", "stop", "previoustrack", "nexttrack", "seekto", "seekbackward", "seekforward"];
  const STATES = ["none", "paused", "playing"];
  // The media element events that change what is playing, or where.
  const MEDIA_EVENTS = ["play", "playing", "pause", "ended", "emptied", "seeked", "ratechange", "durationchange", "loadedmetadata"];

  const handlers = new Map();
  let metadata = null;
  let playbackState = "none";
  // { duration, playbackRate, position, at }: as the page last set it, and when.
  let positionState = null;
  // The media element that last started playing: what "playing" and the
  // position mean when the page says nothing itself.
  let element = null;

  // The WebView's own session, when it has one. It is still told everything,
  // so it checks arguments as a browser would and the page reads back what it
  // set.
  const native = navigator.mediaSession || null;
  const prototype = native && Object.getPrototypeOf(native);
  const nativeSetter = (name) => {
    const descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, name);
    return descriptor && descriptor.set ? (value) => descriptor.set.call(native, value) : () => {};
  };
  const nativeMethod = (name) =>
    prototype && typeof prototype[name] === "function" ? (...args) => prototype[name].apply(native, args) : () => {};

  // ---------------------------------------------------------------- what the app is told

  const playing = () =>
    playbackState === "playing" || (playbackState === "none" && element !== null && !element.paused && !element.ended);

  const finite = (value) => (typeof value === "number" && Number.isFinite(value) ? value : null);

  /** Seconds: the page's own position, moved on by the time since it was set; else the element's. */
  function position() {
    if (positionState) {
      const { duration, playbackRate, position: at, at: since } = positionState;
      const moved = playing() ? ((Date.now() - since) / 1000) * playbackRate : 0;
      const now = Math.max(0, at + moved);
      return { duration: finite(duration), position: duration === null ? now : Math.min(now, duration), rate: playbackRate };
    }
    if (element && finite(element.duration) !== null) {
      return { duration: element.duration, position: element.currentTime, rate: element.playbackRate || 1 };
    }
    return null;
  }

  const text = (value) => (value === undefined || value === null ? "" : String(value));

  function snapshot() {
    if (metadata === null) return { metadata: null };
    return {
      metadata: { title: text(metadata.title), artist: text(metadata.artist), album: text(metadata.album) },
      playing: playing(),
      actions: ACTIONS.filter((action) => handlers.has(action)),
      position: position(),
    };
  }

  // Where every page starts, so a page that never sets metadata sends nothing.
  let lastSent = JSON.stringify({ metadata: null });
  function send(state) {
    const message = JSON.stringify(state);
    if (message === lastSent) return;
    lastSent = message;
    bridge.postMessage(message);
  }

  // Changes come in bursts (a new track sets metadata, then plays), so they
  // are sent together, once the page's own code has run.
  let scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    Promise.resolve().then(() => {
      scheduled = false;
      send(snapshot());
    });
  }

  // ---------------------------------------------------------------- the page's side: navigator.mediaSession

  const session = native || {};
  const define = (name, descriptor) => Object.defineProperty(session, name, { configurable: true, enumerable: true, ...descriptor });

  define("metadata", {
    get: () => metadata,
    set(value) {
      nativeSetter("metadata")(value);
      metadata = value === undefined ? null : value;
      schedule();
    },
  });

  define("playbackState", {
    get: () => playbackState,
    set(value) {
      nativeSetter("playbackState")(value);
      if (!STATES.includes(value)) return;
      playbackState = value;
      schedule();
    },
  });

  define("setActionHandler", {
    writable: true,
    value(action, handler) {
      nativeMethod("setActionHandler")(action, handler);
      if (handler === null || handler === undefined) handlers.delete(action);
      else if (typeof handler === "function") handlers.set(action, handler);
      schedule();
    },
  });

  define("setPositionState", {
    writable: true,
    value(state) {
      nativeMethod("setPositionState")(state);
      if (state === undefined || state === null || (finite(state.duration) === null && state.duration !== Infinity)) {
        positionState = null;
      } else {
        const duration = state.duration === Infinity ? null : state.duration;
        positionState = { duration, playbackRate: finite(state.playbackRate) || 1, position: finite(state.position) || 0, at: Date.now() };
      }
      schedule();
    },
  });

  if (!native) {
    Object.defineProperty(navigator, "mediaSession", { configurable: true, enumerable: true, value: session });
  }
  if (typeof globalThis.MediaMetadata !== "function") {
    globalThis.MediaMetadata = class MediaMetadata {
      constructor(init = {}) {
        this.title = text(init.title);
        this.artist = text(init.artist);
        this.album = text(init.album);
        this.artwork = Array.isArray(init.artwork) ? init.artwork : [];
      }
    };
  }

  // ---------------------------------------------------------------- media elements

  const document = globalThis.document;
  if (document && typeof document.addEventListener === "function") {
    const onMedia = (event) => {
      const target = event.target;
      if (!target || typeof target.paused !== "boolean") return;
      if (event.type === "play" || event.type === "playing") element = target;
      if (target === element) schedule();
    };
    MEDIA_EVENTS.forEach((type) => document.addEventListener(type, onMedia, true));
  }

  // Leaving the page takes what it played with it; coming back from the
  // back-forward cache brings it back.
  if (typeof globalThis.addEventListener === "function") {
    globalThis.addEventListener("pagehide", () => send({ metadata: null }));
    globalThis.addEventListener("pageshow", (event) => {
      if (event.persisted) schedule();
    });
  }

  // ---------------------------------------------------------------- the app's side: the buttons

  function press(command) {
    const action = command && command.action;
    if (!ACTIONS.includes(action)) return;
    const details = { action };
    if (action === "seekto") {
      if (finite(command.seekTime) === null) return;
      details.seekTime = command.seekTime;
    }
    if (finite(command.seekOffset) !== null) details.seekOffset = command.seekOffset;
    const handler = handlers.get(action);
    if (handler) {
      handler.call(session, details);
      return;
    }
    // What a browser does without a handler: play and pause the media.
    if (action === "play" && element) Promise.resolve(element.play()).catch(() => {});
    if (action === "pause" && element) element.pause();
  }

  function onCommand(event) {
    let command;
    try {
      command = JSON.parse(event.data);
    } catch {
      return;
    }
    press(command);
  }

  if (typeof bridge.addEventListener === "function") bridge.addEventListener("message", onCommand);
  else bridge.onmessage = onCommand;
})();
