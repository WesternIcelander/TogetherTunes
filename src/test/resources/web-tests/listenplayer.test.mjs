// ListenPlayer (web/src/listen.ts) with fake media elements: the muted fallback while the browser
// blocks sound (docs/rooms.md#listen-mode). Run by ListenJsTest; LISTEN_JS is the compiled module.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

/** What the fake browser allows: "all", "muted" (muted autoplay only), "none" (block audio and video). */
let policy = "muted";
/** Server clock (ms) for the player. */
let serverMs = 0;
const timers = [];

class FakeMedia {
  constructor(tag) {
    this.tag = tag;
    this.listeners = {};
    this.attrs = {};
    this.paused = true;
    this.muted = false;
    this.volume = 1;
    this.readyState = 0;
    this.seeking = false;
    this.duration = NaN;
    this.playbackRate = 1;
    this.hidden = false;
    this.preload = "";
    this.className = "";
    this.error = null;
    this.buffered = { length: 0, start: () => 0, end: () => 0 };
    this._time = 0;
    this.seeks = 0;
    this.plays = 0;
    this.refused = 0;
  }
  get currentTime() { return this._time; }
  set currentTime(v) { this._time = v; this.seeks++; }
  set src(url) { this.attrs.src = url; this.readyState = 4; this.duration = 60; this._time = 0; }
  get src() { return this.attrs.src ?? ""; }
  setAttribute(k, v) { this.attrs[k] = v; }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  removeAttribute(k) { delete this.attrs[k]; if (k === "src") { this.readyState = 0; this.duration = NaN; } }
  addEventListener(type, l) { (this.listeners[type] ??= []).push(l); }
  play() {
    this.plays++;
    if (policy === "all" || (policy === "muted" && this.muted)) {
      this.paused = false;
      return Promise.resolve();
    }
    this.refused++;
    const e = new Error("play() not allowed");
    e.name = "NotAllowedError";
    return Promise.reject(e);
  }
  pause() { this.paused = true; }
  load() {}
  remove() {}
  canPlayType() { return "maybe"; }
}
class FakeVideo extends FakeMedia {}

const elements = [];
globalThis.HTMLMediaElement = FakeMedia;
globalThis.HTMLVideoElement = FakeVideo;
globalThis.window = {
  setInterval: () => 0,
  clearInterval: () => undefined,
  setTimeout: (f) => { timers.push(f); return timers.length; },
  clearTimeout: () => undefined,
  addEventListener: () => undefined,
};
globalThis.document = {
  visibilityState: "visible",
  addEventListener: () => undefined,
  createElement: (tag) => {
    const el = tag === "video" ? new FakeVideo(tag) : new FakeMedia(tag);
    elements.push(el);
    return el;
  },
};

const { ListenPlayer } = await import(pathToFileURL(process.env.LISTEN_JS).href);

const flush = () => new Promise((r) => setImmediate(r));

function item(id, kind) {
  return { id, kind, status: "ready", durationMs: 60000, title: id,
    videoUrl: kind === "video" ? "/m/" + id + "/mezz.mp4" : undefined, audioUrl: "/m/" + id + "/audio.m4a" };
}

function stateWith(cur, next, anchorServerMs = 0) {
  return {
    version: 1, room: {}, presence: {}, suggestions: [], history: [],
    timeline: { state: "playing", entryId: cur.entryId, anchorServerMs, anchorPositionMs: 0 },
    current: { entryId: cur.entryId, status: "ready", item: cur.item, addedAt: 0 },
    queue: next === undefined ? [] : [{ entryId: next.entryId, status: "ready", item: next.item, addedAt: 0,
      votes: 0, pinned: false, yourVote: 0 }],
  };
}

function setup(p = "muted") {
  policy = p;
  serverMs = 10000;
  elements.length = 0;
  const overlayListeners = {};
  const button = { textContent: "" };
  const overlay = { hidden: true, addEventListener: (t, l) => { overlayListeners[t] = l; }, querySelector: () => button };
  const container = { hidden: true, append: () => undefined };
  const player = new ListenPlayer(container, overlay, () => serverMs, false, "rate", false);
  return { player, overlay, container, click: () => overlayListeners.click() };
}

/** Moves time forward: playing elements advance with the server clock, then a tick. */
function advance(player, ms) {
  serverMs += ms;
  for (const el of elements) if (!el.paused) el._time += ms / 1000;
  player.tick();
}

const playingVideo = () => elements.find((e) => e instanceof FakeVideo && !e.paused);

test("a refused video plays muted, in sync, behind the overlay; the click unmutes without a seek", async () => {
  const { player, overlay, click } = setup("muted");
  player.update(stateWith({ entryId: "e1", item: item("v1", "video") }, undefined, 10000));
  await flush();
  await flush();
  const v = playingVideo();
  assert.ok(v, "the video plays");
  assert.equal(v.muted, true);
  assert.equal(v.refused, 1, "one refused unmuted play, then muted");
  assert.equal(overlay.hidden, false, "the overlay shows");
  assert.equal(player.debug().blocked, true);
  for (let i = 0; i < 20; i++) advance(player, 250);
  await flush();
  const drift = player.debug().driftMs;
  assert.ok(Math.abs(drift) < 50, "in sync: " + drift);
  assert.ok(v.currentTime > 4.5, "advances while muted: " + v.currentTime);
  const seeks = v.seeks;
  policy = "all"; // the click is a gesture
  click();
  await flush();
  assert.equal(v.muted, false, "unmuted by the click");
  assert.equal(v.paused, false);
  assert.equal(v.seeks, seeks, "no seek");
  assert.equal(overlay.hidden, true);
  assert.equal(player.debug().blocked, false);
});

test("the listener's own mute is kept across the unlock and never unlocks", async () => {
  const { player, click } = setup("muted");
  player.setMuted(true);
  player.update(stateWith({ entryId: "e1", item: item("v1", "video") }, undefined, 10000));
  await flush();
  await flush();
  const v = playingVideo();
  assert.equal(v.muted, true);
  assert.equal(player.debug().blocked, true, "the listener's mute doesn't count as unlocked");
  policy = "all";
  click();
  await flush();
  assert.equal(v.muted, false, "the blocked mute is lifted");
  assert.equal(player.muted, true, "the listener's mute stays");
  assert.equal(v.volume, 0, "silent through the listener's mute");
  player.setMuted(false);
  assert.equal(v.volume, 1);
});

test("the next video starts muted while sound is blocked; an audio item waits as before", async () => {
  const { player, overlay } = setup("muted");
  const e1 = { entryId: "e1", item: item("v1", "video") };
  const e2 = { entryId: "e2", item: item("v2", "video") };
  const e3 = { entryId: "e3", item: item("a3", "audio") };
  player.update(stateWith(e1, e2, 10000));
  await flush();
  await flush();
  const first = playingVideo();
  assert.equal(first.muted, true);
  // Near the end: the next video is preloaded (paused).
  serverMs = 10000 + 55000;
  first._time = 55;
  player.tick();
  const preload = elements.find((e) => e instanceof FakeVideo && e !== first && e.src.includes("v2"));
  assert.ok(preload, "preloaded");
  assert.equal(preload.paused, true);
  // The next entry starts.
  player.update(stateWith(e2, e3, serverMs));
  await flush();
  await flush();
  assert.equal(preload.paused, false, "the preloaded video plays");
  assert.equal(preload.muted, true);
  assert.equal(preload.refused, 0, "muted before its first play");
  assert.equal(overlay.hidden, false);
  // An audio item: refused and paused, as without the fallback.
  player.update(stateWith(e3, undefined, serverMs));
  await flush();
  await flush();
  const audio = elements.find((e) => !(e instanceof FakeVideo) && e.src.includes("a3"));
  assert.equal(audio.paused, true);
  assert.equal(audio.muted, false);
  assert.ok(audio.refused >= 1);
  assert.equal(overlay.hidden, false);
});

test("switching to audio only and back while blocked-muted", async () => {
  const { player } = setup("muted");
  const e1 = { entryId: "e1", item: item("v1", "video") };
  player.update(stateWith(e1, undefined, 10000));
  await flush();
  await flush();
  advance(player, 1000);
  player.setAudioOnly(true);
  await flush();
  await flush();
  assert.equal(elements.filter((e) => e instanceof FakeVideo && !e.paused).length, 0, "no video plays");
  const refusedBefore = elements.reduce((n, e) => n + e.refused, 0);
  player.setAudioOnly(false);
  await flush();
  await flush();
  const v = playingVideo();
  assert.ok(v, "the video plays again");
  assert.equal(v.muted, true);
  assert.equal(elements.reduce((n, e) => n + e.refused, 0) - refusedBefore, 0, "muted before its play");
  advance(player, 250);
  assert.ok(Math.abs(player.debug().driftMs) < 50);
});

test("where muted play is refused too, the video waits paused and unmuted for the click", async () => {
  const { player, overlay, click } = setup("none");
  player.update(stateWith({ entryId: "e1", item: item("v1", "video") }, undefined, 10000));
  await flush();
  await flush();
  const v = elements.find((e) => e instanceof FakeVideo && e.src.includes("v1"));
  assert.equal(v.paused, true);
  assert.equal(v.muted, false);
  assert.equal(v.refused, 2, "unmuted, then muted");
  assert.equal(player.debug().mutedRefused, true);
  assert.equal(overlay.hidden, false);
  advance(player, 250);
  await flush();
  assert.equal(v.muted, false, "retried with sound, not muted");
  policy = "all";
  click();
  await flush();
  assert.equal(v.paused, false);
  assert.equal(v.muted, false);
  assert.equal(overlay.hidden, true);
});
