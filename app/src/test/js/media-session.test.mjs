// Tests for app/src/main/assets/media-session.js, the script that relays the
// page's Media Session to the app, and the system's buttons back.
//
// Each run gets a fresh realm with a fake bridge, a clock, a document whose
// media events the test fires, and (unless a test says otherwise) a native
// navigator.mediaSession that records what reaches it.
//
//   node --test app/src/test/js/media-session.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(
  process.env.MEDIA_SESSION_JS ?? new URL("../../main/assets/media-session.js", import.meta.url),
  "utf8",
);

/** A browser's MediaSession, as far as the script relies on it: checks its arguments and records them. */
function nativeSession() {
  const calls = [];
  class MediaMetadata {
    constructor(init = {}) {
      Object.assign(this, { title: "", artist: "", album: "", ...init });
    }
  }
  class MediaSession {
    get metadata() {
      return this._metadata ?? null;
    }
    set metadata(value) {
      if (value !== null && !(value instanceof MediaMetadata)) throw new TypeError("not a MediaMetadata");
      calls.push(["metadata", value?.title ?? null]);
      this._metadata = value;
    }
    set playbackState(value) {
      calls.push(["playbackState", value]);
    }
    setActionHandler(action, handler) {
      calls.push(["setActionHandler", action, typeof handler]);
    }
    setPositionState(state) {
      calls.push(["setPositionState", state]);
    }
  }
  return { session: new MediaSession(), MediaMetadata, calls };
}

/** Run the script in a fresh page. */
function page({ bridge = true, native = true, top } = {}) {
  const sent = [];
  const listeners = [];
  const documentListeners = {};
  const windowListeners = {};
  const clock = { now: 1_000_000 };
  const browser = native ? nativeSession() : null;
  const realm = vm.createContext({
    navigator: browser ? { mediaSession: browser.session } : {},
    MediaMetadata: browser?.MediaMetadata,
    Date: { now: () => clock.now },
    document: {
      addEventListener: (type, listener, capture) => {
        assert.equal(capture, true, "media events don't bubble, so they are caught on the way down");
        (documentListeners[type] ??= []).push(listener);
      },
    },
    addEventListener: (type, listener) => (windowListeners[type] ??= []).push(listener),
    ...(top === undefined ? {} : { top }),
  });
  if (bridge) {
    realm.__irohMediaSession = {
      postMessage: (message) => sent.push(JSON.parse(message)),
      addEventListener: (type, listener) => type === "message" && listeners.push(listener),
    };
  }
  vm.runInContext(source, realm);
  const run = (code) => vm.runInContext(code, realm);
  return {
    realm,
    run,
    sent,
    clock,
    calls: browser?.calls ?? [],
    /** The app sends a button press. */
    press: (command) => listeners.forEach((listener) => listener({ data: JSON.stringify(command) })),
    raw: (data) => listeners.forEach((listener) => listener({ data })),
    /** A media element event, as the document would dispatch it. */
    media: (type, target) => (documentListeners[type] ?? []).forEach((listener) => listener({ type, target })),
    window: (type, event = {}) => (windowListeners[type] ?? []).forEach((listener) => listener({ type, ...event })),
  };
}

/** Let the script's batched send run. */
const settled = () => new Promise((resolve) => setImmediate(resolve));

/** A media element, as far as the script reads it. */
const audio = (state = {}) => ({ paused: false, ended: false, duration: 200, currentTime: 12, playbackRate: 1, plays: 0, pauses: 0, play() { this.plays++; this.paused = false; return Promise.resolve(); }, pause() { this.pauses++; this.paused = true; }, ...state });

test("a page that never sets metadata sends nothing, even while it plays", async () => {
  const { run, sent, media } = page();
  run(`navigator.mediaSession.setActionHandler("play", () => {}); navigator.mediaSession.playbackState = "playing";`);
  media("play", audio());
  await settled();
  assert.deepEqual(sent, []);
});

test("what the page sets is sent together, once its code has run", async () => {
  const { run, sent } = page();
  run(`
    navigator.mediaSession.metadata = new MediaMetadata({ title: "Song", artist: "Band", album: "Album" });
    navigator.mediaSession.setActionHandler("nexttrack", () => {});
    navigator.mediaSession.setActionHandler("play", () => {});
    navigator.mediaSession.setActionHandler("skipad", () => {});
    navigator.mediaSession.playbackState = "playing";
  `);
  assert.deepEqual(sent, [], "nothing before the page's code has finished");
  await settled();
  assert.deepEqual(sent, [
    {
      metadata: { title: "Song", artist: "Band", album: "Album" },
      playing: true,
      actions: ["play", "nexttrack"],
      position: null,
    },
  ]);
});

test("the WebView's own session still gets every call, and its checks still throw", async () => {
  const { run, sent, calls } = page();
  run(`navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });`);
  run(`navigator.mediaSession.setPositionState({ duration: 10, position: 1 });`);
  assert.deepEqual(calls.map((call) => call[0]), ["metadata", "setPositionState"]);
  assert.throws(() => run(`navigator.mediaSession.metadata = { title: "not metadata" };`), /not a MediaMetadata/);
  assert.equal(run(`navigator.mediaSession.metadata.title`), "Song", "a refused value isn't kept");
  await settled();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].metadata.title, "Song");
});

test("without a session of its own, the WebView gets one, and MediaMetadata", async () => {
  const { run, sent } = page({ native: false });
  run(`
    navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });
    navigator.mediaSession.playbackState = "paused";
  `);
  assert.equal(run(`navigator.mediaSession.playbackState`), "paused");
  await settled();
  assert.deepEqual(sent, [{ metadata: { title: "Song", artist: "", album: "" }, playing: false, actions: [], position: null }]);
});

test("the position is the page's, moved on while it plays", async () => {
  const { run, sent, clock } = page();
  run(`
    navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });
    navigator.mediaSession.playbackState = "playing";
    navigator.mediaSession.setPositionState({ duration: 100, position: 10, playbackRate: 2 });
  `);
  clock.now += 3000;
  await settled();
  assert.deepEqual(sent.at(-1).position, { duration: 100, position: 16, rate: 2 });

  run(`navigator.mediaSession.playbackState = "paused"`);
  clock.now += 3000;
  await settled();
  assert.deepEqual(sent.at(-1).position, { duration: 100, position: 10, rate: 2 }, "not moved on while paused");

  run(`navigator.mediaSession.setPositionState()`);
  await settled();
  assert.equal(sent.at(-1).position, null);
});

test("when the page says nothing itself, the media element says what plays and where", async () => {
  const { run, sent, media } = page();
  run(`navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });`);
  const element = audio();
  media("play", element);
  await settled();
  assert.deepEqual(sent.at(-1), { metadata: { title: "Song", artist: "", album: "" }, playing: true, actions: [], position: { duration: 200, position: 12, rate: 1 } });

  element.paused = true;
  media("pause", element);
  await settled();
  assert.equal(sent.at(-1).playing, false);

  media("seeked", audio({ currentTime: 99 }));
  await settled();
  assert.equal(sent.at(-1).position.position, 12, "another element's events don't count");
});

test("the same state isn't sent twice", async () => {
  const { run, sent } = page();
  run(`navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });`);
  await settled();
  run(`navigator.mediaSession.playbackState = "none";`);
  await settled();
  assert.equal(sent.length, 1);
});

test("leaving the page tells the app that nothing plays; coming back from the cache tells it again", async () => {
  const { run, sent, window } = page();
  run(`navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });`);
  await settled();
  window("pagehide");
  assert.deepEqual(sent.at(-1), { metadata: null });
  window("pageshow", { persisted: true });
  await settled();
  assert.equal(sent.at(-1).metadata.title, "Song");
  run(`navigator.mediaSession.metadata = null;`);
  await settled();
  assert.deepEqual(sent.at(-1), { metadata: null });
});

test("a button from the app calls the page's handler, with what a browser passes", async () => {
  const { run, press } = page();
  run(`
    globalThis.got = [];
    for (const action of ["play", "pause", "nexttrack", "previoustrack", "seekto", "seekforward", "stop"]) {
      navigator.mediaSession.setActionHandler(action, (details) => got.push(details));
    }
  `);
  press({ action: "nexttrack" });
  press({ action: "seekto", seekTime: 42.5 });
  press({ action: "seekforward", seekOffset: 10 });
  press({ action: "seekto" });
  press({ action: "skipad" });
  press({ action: "constructor" });
  press(null);
  assert.deepEqual(JSON.parse(run(`JSON.stringify(got)`)), [
    { action: "nexttrack" },
    { action: "seekto", seekTime: 42.5 },
    { action: "seekforward", seekOffset: 10 },
  ]);
});

test("without a handler, play and pause act on the media element, as in a browser", async () => {
  const { press, media } = page();
  const element = audio({ paused: true });
  press({ action: "play" });
  media("play", element);
  press({ action: "pause" });
  press({ action: "play" });
  assert.equal(element.pauses, 1);
  assert.equal(element.plays, 1);
});

test("a removed handler isn't called, and its button isn't offered", async () => {
  const { run, sent, press } = page();
  run(`
    globalThis.calls = 0;
    navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });
    navigator.mediaSession.setActionHandler("nexttrack", () => calls++);
    navigator.mediaSession.setActionHandler("nexttrack", null);
  `);
  press({ action: "nexttrack" });
  await settled();
  assert.equal(run(`calls`), 0);
  assert.deepEqual(sent.at(-1).actions, []);
});

test("garbage from the bridge is ignored", () => {
  const { raw } = page();
  assert.doesNotThrow(() => raw("not json"));
  assert.doesNotThrow(() => raw("42"));
});

test("without the bridge, or in a frame, the page is left as it was", async () => {
  for (const options of [{ bridge: false }, { top: {} }]) {
    const { run, sent, calls } = page(options);
    assert.equal(run(`Object.getOwnPropertyNames(navigator.mediaSession).length`), 0, JSON.stringify(options));
    run(`navigator.mediaSession.metadata = new MediaMetadata({ title: "Song" });`);
    await settled();
    assert.deepEqual(sent, []);
    assert.equal(calls.length, 1, "the page's call still reaches the WebView");
  }
});
