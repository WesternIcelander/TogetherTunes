// Unit tests for web/src/listensync.ts (compiled), run with Node's test runner by ListenJsTest.
// The module path comes in LISTENSYNC_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const L = await import(pathToFileURL(process.env.LISTENSYNC_JS).href);

test("sync loop: hard seek above 750 ms, rate nudge above 40 ms, steady below", () => {
  assert.deepEqual(L.syncAction(751), { kind: "seek" });
  assert.deepEqual(L.syncAction(-751), { kind: "seek" });
  assert.deepEqual(L.syncAction(750), { kind: "rate", rate: 0.95 }); // 1 − 750/2000 = 0.625 → clamped
  assert.deepEqual(L.syncAction(-750), { kind: "rate", rate: 1.05 });
  assert.deepEqual(L.syncAction(100), { kind: "rate", rate: 0.95 });
  assert.deepEqual(L.syncAction(-100), { kind: "rate", rate: 1.05 });
  assert.deepEqual(L.syncAction(41), { kind: "rate", rate: 1 - 41 / 2000 });
  assert.deepEqual(L.syncAction(-60), { kind: "rate", rate: 1 + 60 / 2000 });
  assert.deepEqual(L.syncAction(40), { kind: "rate", rate: 1 });
  assert.deepEqual(L.syncAction(-40), { kind: "rate", rate: 1 });
  assert.deepEqual(L.syncAction(0), { kind: "rate", rate: 1 });
});

test("element ahead slows down, element behind speeds up", () => {
  assert.ok(L.syncAction(80).rate < 1);
  assert.ok(L.syncAction(-80).rate > 1);
});

test("dB to linear gain", () => {
  assert.equal(L.dbToGain(0), 1);
  assert.equal(L.dbToGain(undefined), 1);
  assert.equal(L.dbToGain(null), 1);
  assert.equal(L.dbToGain(NaN), 1);
  assert.ok(Math.abs(L.dbToGain(-6.0206) - 0.5) < 1e-4);
  assert.ok(Math.abs(L.dbToGain(20) - 10) < 1e-9);
});

const audioItem = {
  kind: "audio", presentAsAudio: true, audioUrl: "/media/a/audio.m4a",
  originalUrl: "/media/a/original", originalGainDb: -3.2, originalType: "audio/flac",
};
const yes = () => true;
const no = () => false;

test("audio item with a playable original plays the original with its gain", () => {
  assert.deepEqual(L.chooseSource(audioItem, yes, false),
    { url: "/media/a/original", gainDb: -3.2, video: false, original: true });
});

test("canPlayType saying no, or a given-up original, falls back to audio.m4a", () => {
  const fallback = { url: "/media/a/audio.m4a", gainDb: 0, video: false, original: false };
  assert.deepEqual(L.chooseSource(audioItem, no, false), fallback);
  assert.deepEqual(L.chooseSource(audioItem, yes, true), fallback);
  let asked;
  L.chooseSource(audioItem, (t) => { asked = t; return true; }, false);
  assert.equal(asked, "audio/flac");
});

test("no original offered (not web-playable) → audio.m4a; no type → trust the server", () => {
  const { originalUrl, originalGainDb, originalType, ...plain } = audioItem;
  assert.equal(L.chooseSource(plain, yes, false).url, "/media/a/audio.m4a");
  const { originalType: _t, ...untyped } = audioItem;
  assert.equal(L.chooseSource(untyped, no, false).url, "/media/a/original");
  const noGain = { ...audioItem, originalGainDb: undefined };
  assert.equal(L.chooseSource(noGain, yes, false).gainDb, 0);
});

test("video items: mezz.mp4 in a video element, presentAsAudio → audio.m4a", () => {
  const video = { kind: "video", presentAsAudio: false, audioUrl: "/media/v/audio.m4a", videoUrl: "/media/v/mezz.mp4" };
  assert.deepEqual(L.chooseSource(video, yes, false), { url: "/media/v/mezz.mp4", gainDb: 0, video: true, original: false });
  assert.deepEqual(L.chooseSource({ ...video, presentAsAudio: true }, yes, false),
    { url: "/media/v/audio.m4a", gainDb: 0, video: false, original: false });
});

test("an item that isn't ready has no source", () => {
  assert.equal(L.chooseSource({ kind: "audio" }, yes, false), undefined);
});

test("preload: the first ready queue entry, about 10 s before the end", () => {
  const e = (id, status, itemStatus = status) => ({ entryId: id, status, item: { id: "i" + id, status: itemStatus } });
  const state = { current: e("c", "ready"), queue: [e("p", "processing"), e("f", "failed"), e("n", "ready"), e("m", "ready")] };
  assert.equal(L.nextToPreload(state).entryId, "n");
  assert.equal(L.nextToPreload({ current: undefined, queue: [] }), undefined);
  assert.equal(L.nextToPreload({ current: undefined, queue: [e("p", "processing")] }), undefined);
  assert.equal(L.shouldPreload(10_000), true);
  assert.equal(L.shouldPreload(9_000), true);
  assert.equal(L.shouldPreload(10_001), false);
});

test("an original is given up after two hard seeks within 10 s didn't hold", () => {
  const g = new L.SeekGuard();
  assert.equal(g.hardSeek(1000), true);
  assert.equal(g.hardSeek(5000), true);
  assert.equal(g.hardSeek(9000), false); // a third within 10 s of the first two
  const h = new L.SeekGuard();
  assert.equal(h.hardSeek(1000), true);
  assert.equal(h.hardSeek(5000), true);
  assert.equal(h.hardSeek(11_000), true); // the first is older than 10 s
  assert.equal(h.hardSeek(14_000), false);
});

test("re-anchoring resets the count (a timeline seek isn't the file's fault)", () => {
  const g = new L.SeekGuard();
  g.hardSeek(1000);
  g.hardSeek(2000);
  g.reset();
  assert.equal(g.hardSeek(3000), true);
});

test("audio-only mode: video items play audio.m4a, never mezz.mp4; audio items unchanged", () => {
  const video = { kind: "video", presentAsAudio: false, audioUrl: "/media/v/audio.m4a", videoUrl: "/media/v/mezz.mp4" };
  assert.deepEqual(L.chooseSource(video, yes, false, true), { url: "/media/v/audio.m4a", gainDb: 0, video: false, original: false });
  assert.equal(L.chooseSource(video, yes, false, false).url, "/media/v/mezz.mp4");
  assert.deepEqual(L.chooseSource(audioItem, yes, false, true),
    { url: "/media/a/original", gainDb: -3.2, video: false, original: true });
});

test("switch gate: crossfade after 3 steady ticks at least 200 ms apart", () => {
  const g = new L.SwitchGate(0);
  assert.equal(g.sample(100, undefined), false); // loading
  assert.equal(g.sample(250, 10), false);
  assert.equal(g.sample(300, 5), false); // too soon after the last: not counted
  assert.equal(g.sample(500, -20), false);
  assert.equal(g.sample(750, 40), true);
});

test("switch gate: drift above 40 ms or not playing restarts the count", () => {
  const g = new L.SwitchGate(0);
  g.sample(250, 10);
  g.sample(500, 10);
  assert.equal(g.sample(750, 41), false);
  assert.equal(g.sample(1000, 10), false);
  assert.equal(g.sample(1250, undefined), false);
  assert.equal(g.sample(1500, 10), false);
  assert.equal(g.sample(1750, 10), false);
  assert.equal(g.sample(2000, 10), true);
});

test("switch gate: after 3 s it crossfades if the element plays, never while it doesn't", () => {
  const g = new L.SwitchGate(1000);
  assert.equal(g.sample(3999, 300), false);
  assert.equal(g.sample(4000, undefined), false);
  assert.equal(g.sample(4250, 300), true);
  assert.equal(L.CROSSFADE_MS, 150);
});
