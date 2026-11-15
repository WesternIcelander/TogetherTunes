// Unit tests for web/src/mediasession.ts (compiled), run with Node's test runner by MediaSessionJsTest.
// The module path comes in MEDIASESSION_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const M = await import(pathToFileURL(process.env.MEDIASESSION_JS).href);

const ORIGIN = "https://tt.example";
const you = (canSkip, restriction) => (restriction === undefined ? { canSkip } : { canSkip, restriction });
const room = (extra = {}) => Object.assign({ name: "Lounge", controlMode: "owner", you: you(true) }, extra);
const item = (extra = {}) => Object.assign({ id: "i1", title: "Song", artist: "Band", durationMs: 200_000,
  coverUrl: "/media/i1/v/2/cover.jpg" }, extra);
const state = (opts = {}) => ({
  room: opts.room ?? room(),
  timeline: opts.timeline ?? { state: "playing", entryId: "e1", anchorServerMs: 10_000, anchorPositionMs: 5_000 },
  current: opts.current === null ? undefined : { entryId: "e1", status: "ready", item: opts.item ?? item() },
});
const input = (s, extra = {}) => Object.assign({ state: s, heard: true, muted: false, serverNow: 12_000, origin: ORIGIN },
  extra);

test("a playing song: title, artist, the room's name as album, absolute JPEG artwork, playing", () => {
  const p = M.sessionPlan(input(state()));
  assert.deepEqual(p.metadata, { title: "Song", artist: "Band", album: "Lounge",
    artwork: [{ src: "https://tt.example/media/i1/v/2/cover.jpg", type: "image/jpeg" }] });
  assert.equal(p.playbackState, "playing");
  assert.deepEqual(p.actions, ["play", "pause", "nexttrack"]);
  assert.deepEqual(p.position, { duration: 200, position: 7, playbackRate: 1 });
});

test("the item's album wins; the alone room has none; no cover: no artwork", () => {
  assert.equal(M.sessionPlan(input(state({ item: item({ album: "Record" }) }))).metadata.album, "Record");
  assert.equal(M.sessionPlan(input(state({ room: room({ alone: true }) }))).metadata.album, "");
  assert.equal(M.sessionPlan(input(state({ room: room({ alone: true }), item: item({ album: "Record" }) })))
    .metadata.album, "Record");
  assert.deepEqual(M.sessionPlan(input(state({ item: item({ coverUrl: undefined }) }))).metadata.artwork, []);
});

test("artwork sizes once the cover's size is known (for that URL only)", () => {
  const url = "/media/i1/v/2/cover.jpg";
  assert.deepEqual(M.sessionPlan(input(state(), { coverSize: { url, width: 1200, height: 675 } })).metadata.artwork,
    [{ src: ORIGIN + url, type: "image/jpeg", sizes: "1200x675" }]);
  assert.equal(M.sessionPlan(input(state(), { coverSize: { url: "/other.jpg", width: 10, height: 10 } }))
    .metadata.artwork[0].sizes, undefined);
});

test("an episode without an artist shows the show's name", () => {
  const p = M.sessionPlan(input(state({ item: item({ artist: undefined, show: { name: "The Show", episode: 2 } }) })));
  assert.equal(p.metadata.artist, "The Show");
});

test("absolute URLs", () => {
  assert.equal(M.absoluteUrl("/a.jpg", "http://h:8080"), "http://h:8080/a.jpg");
  assert.equal(M.absoluteUrl("a.jpg", "http://h/"), "http://h/a.jpg");
  assert.equal(M.absoluteUrl("https://x/a.jpg", "http://h"), "https://x/a.jpg");
});

test("nothing plays, or this window isn't heard: empty session", () => {
  const empty = { metadata: null, playbackState: "none", actions: [], position: undefined, positionKey: "" };
  assert.deepEqual(M.sessionPlan(input(undefined)), empty);
  assert.deepEqual(M.sessionPlan(input(state({ current: null }))), empty);
  assert.deepEqual(M.sessionPlan(input(state({ timeline: { state: "idle", anchorServerMs: 0, anchorPositionMs: 0 } }))),
    empty);
  assert.deepEqual(M.sessionPlan(input(state({ timeline: { state: "playing", entryId: "other", anchorServerMs: 0,
    anchorPositionMs: 0 } }))), empty, "the timeline is on another entry (loading)");
  assert.deepEqual(M.sessionPlan(input(state(), { heard: false })), empty, "another window has the sound, or blocked");
});

test("the listener's mute is paused; a paused room too", () => {
  const muted = M.sessionPlan(input(state(), { muted: true }));
  assert.equal(muted.playbackState, "paused");
  assert.notEqual(muted.metadata, null, "still the window with the sound");
  assert.deepEqual(muted.actions, ["play", "pause", "nexttrack"]);
  const paused = M.sessionPlan(input(state({ timeline: { state: "paused", entryId: "e1", anchorServerMs: 0,
    anchorPositionMs: 30_000 } })));
  assert.equal(paused.playbackState, "paused");
  assert.equal(paused.position.position, 30);
});

test("Next only for people who may skip: not in vote mode for voters, not while restricted", () => {
  const voter = state({ room: room({ controlMode: "vote", you: you(false) }) });
  assert.deepEqual(M.sessionPlan(input(voter)).actions, ["play", "pause"]);
  const ownerInVote = state({ room: room({ controlMode: "vote", you: you(true) }) });
  assert.deepEqual(M.sessionPlan(input(ownerInVote)).actions, ["play", "pause", "nexttrack"]);
  const banned = state({ room: room({ you: you(true, { kind: "ban" }) }) });
  assert.deepEqual(M.sessionPlan(input(banned)).actions, ["play", "pause"]);
});

test("position: clamped, none for unknown durations, its key changes on track change, pause, mute and resync only", () => {
  assert.equal(M.sessionPlan(input(state(), { serverNow: 10_000_000 })).position.position, 200);
  const unknown = M.sessionPlan(input(state({ item: item({ durationMs: undefined }) })));
  assert.equal(unknown.position, undefined);
  assert.equal(unknown.positionKey, "");
  const a = M.sessionPlan(input(state())).positionKey;
  assert.equal(M.sessionPlan(input(state(), { serverNow: 50_000 })).positionKey, a, "not every tick");
  assert.notEqual(M.sessionPlan(input(state(), { muted: true })).positionKey, a);
  const seek = state({ timeline: { state: "playing", entryId: "e1", anchorServerMs: 11_000, anchorPositionMs: 90_000 } });
  assert.notEqual(M.sessionPlan(input(seek)).positionKey, a);
});

/** A fake navigator.mediaSession recording what was done to it. */
function fakeSession(unsupported = []) {
  const log = [];
  return {
    log,
    handlers: {},
    _metadata: undefined,
    get metadata() { return this._metadata; },
    set metadata(v) { log.push(["metadata", v]); this._metadata = v; },
    _state: "none",
    get playbackState() { return this._state; },
    set playbackState(v) { log.push(["playbackState", v]); this._state = v; },
    setActionHandler(action, handler) {
      if (unsupported.indexOf(action) >= 0) throw new TypeError("unsupported " + action);
      log.push(["handler", action, handler === null ? null : "fn"]);
      this.handlers[action] = handler;
    },
    setPositionState(p) { log.push(["position", p]); },
  };
}

test("MediaSessionSync applies a plan, touches nothing when it didn't change, and survives unsupported actions", () => {
  const ms = fakeSession(["seekto"]);
  const calls = [];
  const sync = new M.MediaSessionSync(ms, (m) => ({ made: m }), {
    play: () => calls.push("play"), pause: () => calls.push("pause"), nexttrack: () => calls.push("next") });
  const plan = M.sessionPlan(input(state()));
  sync.apply(plan);
  assert.equal(ms.metadata.made.title, "Song");
  assert.equal(ms.playbackState, "playing");
  assert.equal(typeof ms.handlers.play, "function");
  assert.equal(typeof ms.handlers.nexttrack, "function");
  for (const a of ["previoustrack", "seekbackward", "seekforward"]) assert.equal(ms.handlers[a], null, a);
  assert.ok(ms.log.some((e) => e[0] === "position" && e[1].duration === 200));
  ms.handlers.pause();
  ms.handlers.play();
  ms.handlers.nexttrack();
  assert.deepEqual(calls, ["pause", "play", "next"]);
  const n = ms.log.length;
  sync.apply(M.sessionPlan(input(state(), { serverNow: 30_000 })));
  assert.equal(ms.log.length, n, "same plan apart from the clock: nothing touched");
  // Muted: paused and the position again; the metadata stays.
  sync.apply(M.sessionPlan(input(state(), { muted: true })));
  assert.deepEqual(ms.log.slice(n).map((e) => e[0]), ["playbackState", "position"]);
  // A guest in vote mode: Next goes away.
  sync.apply(M.sessionPlan(input(state({ room: room({ controlMode: "vote", you: you(false) }) }))));
  assert.equal(ms.handlers.nexttrack, null);
  assert.equal(typeof ms.handlers.play, "function");
  // A track change: new metadata.
  sync.apply(M.sessionPlan(input(state({ item: item({ id: "i2", title: "Other" }) }))));
  assert.equal(ms.metadata.made.title, "Other");
  // Not heard any more: cleared, no handlers.
  sync.apply(M.sessionPlan(input(state(), { heard: false })));
  assert.equal(ms.metadata, null);
  assert.equal(ms.playbackState, "none");
  for (const a of ["play", "pause", "nexttrack"]) assert.equal(ms.handlers[a], null, a);
});

test("MediaSessionSync: the first plan of a window without the sound sets null metadata and no handlers", () => {
  const ms = fakeSession();
  new M.MediaSessionSync(ms, (m) => m, { play() {}, pause() {}, nexttrack() {} })
    .apply(M.sessionPlan(input(state(), { heard: false })));
  assert.equal(ms.metadata, null);
  assert.equal(ms.playbackState, "none");
  assert.equal(ms.handlers.play, null);
});

test("MediaSessionSync: no session (no Media Session API) does nothing; a refused artwork falls back", () => {
  new M.MediaSessionSync(undefined, () => { throw new Error("never"); }, { play() {}, pause() {}, nexttrack() {} })
    .apply(M.sessionPlan(input(state())));
  const ms = fakeSession();
  new M.MediaSessionSync(ms, (m) => {
    if (m.artwork.length > 0) throw new TypeError("bad artwork");
    return m;
  }, { play() {}, pause() {}, nexttrack() {} }).apply(M.sessionPlan(input(state())));
  assert.equal(ms.metadata.title, "Song");
  assert.deepEqual(ms.metadata.artwork, []);
});
