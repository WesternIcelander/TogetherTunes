// Unit tests for web/src/trim.ts (compiled), run with Node's test runner by TrimJsTest.
// The module path comes in TRIM_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const T = await import(pathToFileURL(process.env.TRIM_JS).href);

const view = (over) => ({ videoStartMs: 0, originalMs: 13000, ...over });

test("times format as m:ss.mmm and parse back", () => {
  assert.equal(T.formatTime(0), "0:00.000");
  assert.equal(T.formatTime(65250), "1:05.250");
  assert.equal(T.formatTime(3661001), "61:01.001");
  assert.equal(T.parseTime("1:05.250"), 65250);
  assert.equal(T.parseTime(" 1:05.25 "), 65250);
  assert.equal(T.parseTime("1:05"), 65000);
  assert.equal(T.parseTime("5.5"), 5500);
  assert.equal(T.parseTime("75"), 75000);
  assert.equal(T.parseTime("1:01:01.001"), 3661001);
  for (const bad of ["", "abc", "1:60", "1:60:00", "1:05.1234", "-1", "1:", ":05"]) {
    assert.equal(T.parseTime(bad), undefined, bad);
  }
  for (const ms of [0, 1, 999, 59999, 60000, 754321]) assert.equal(T.parseTime(T.formatTime(ms)), ms);
});

test("handles clamp and move freely", () => {
  const v = view({ videoStartMs: 1500, videoEndMs: 9000 });
  const r = T.currentRange(v);
  assert.deepEqual(r, { startMs: 1500, endMs: 9000 });
  assert.equal(T.snapToGrid, undefined, "no snapping any more");
  assert.deepEqual(T.moveStart(r, 4600), { startMs: 4600, endMs: 9000 });
  assert.deepEqual(T.moveStart(r, 4600.4), { startMs: 4600, endMs: 9000 });
  assert.deepEqual(T.moveStart(r, 8800), { startMs: 8000, endMs: 9000 }, "1 s stays");
  assert.deepEqual(T.moveStart(r, -50), { startMs: 0, endMs: 9000 });
  assert.deepEqual(T.moveEnd(r, 20000, v), { startMs: 1500, endMs: 13000 });
  assert.deepEqual(T.moveEnd(r, 1000, v), { startMs: 1500, endMs: 2500 });
});

test("which kind of re-encode a choice needs", () => {
  const v = view({});
  assert.deepEqual(T.estimate(v, { startMs: 0, endMs: 13000 }), { kind: "none", encodeSeconds: 0 });
  assert.equal(T.estimate(v, { startMs: 0, endMs: 11500 }).kind, "cut", "end earlier");
  assert.equal(T.estimate(v, { startMs: 4000, endMs: 13000 }).kind, "cut", "start later on the grid");
  const later = view({ videoEndMs: 11500 });
  assert.deepEqual(T.estimate(later, { startMs: 0, endMs: 12500 }), { kind: "smart", encodeSeconds: 2.5 },
    "end later: from the last GOP (frame 300)");
  const started = view({ videoStartMs: 4000 });
  assert.deepEqual(T.estimate(started, { startMs: 2000, endMs: 13000 }), { kind: "smart", encodeSeconds: 2 });
  // a start off the grid: only [S, next keyframe) is encoded (here 2.5 s → the keyframe at 4 s)
  assert.deepEqual(T.estimate(v, { startMs: 2500, endMs: 9000 }), { kind: "smart", encodeSeconds: 1.5 });
  // earlier and off the grid on a current start of 4 s: [3.5 s, 4 s) is encoded, all of the current kept
  assert.deepEqual(T.estimate(started, { startMs: 3500, endMs: 13000 }), { kind: "smart", encodeSeconds: 0.5 });
  // a current encode with a short first GOP (45 frames: keyframes at 2.5 s, 4 s, 6 s, ...)
  const short = view({ videoStartMs: 2500, videoEndMs: 9000, firstGopFrames: 45 });
  assert.deepEqual(T.estimate(short, { startMs: 4000, endMs: 9000 }), { kind: "cut", encodeSeconds: 0 },
    "onto a later keyframe");
  assert.deepEqual(T.estimate(short, { startMs: 3000, endMs: 9000 }), { kind: "smart", encodeSeconds: 1 },
    "later, off the grid: up to 4 s");
  assert.deepEqual(T.estimate(short, { startMs: 2000, endMs: 9000 }), { kind: "smart", encodeSeconds: 2 },
    "earlier: the short first GOP is encoded again, never kept mid-stream");
  assert.deepEqual(T.estimate(short, { startMs: 2500, endMs: 8000 }), { kind: "cut", encodeSeconds: 0 },
    "same start: the short first GOP stays at the front");
  assert.equal(T.estimate(view({ videoStartMs: 10000 }), { startMs: 2000, endMs: 6000 }).kind, "full",
    "nothing of the current encode is left");
  assert.equal(T.estimate(view({ videoEndMs: 11500 }), { startMs: 11000, endMs: 13000 }).kind, "full",
    "past the current encode's last keyframe");
  assert.equal(T.estimate(view({ videoStartMs: 10000 }), { startMs: 2000, endMs: 6000 }).encodeSeconds, 4);
  assert.match(T.estimateText({ kind: "full", encodeSeconds: 6.5 }), /^Full re-encode: about 7 s/);
  assert.match(T.estimateText({ kind: "smart", encodeSeconds: 2 }), /^Smart re-encode: about 2 s/);
  assert.match(T.estimateText({ kind: "cut", encodeSeconds: 0 }), /^No video re-encode/);
});

test("the request body and the preview", () => {
  const v = view({ videoStartMs: 2000, videoEndMs: 9000 });
  assert.deepEqual(T.trimBody(v, { startMs: 4000, endMs: 13000 }), { videoStartMs: 4000, videoEndMs: null });
  assert.deepEqual(T.trimBody(v, { startMs: 4000.4, endMs: 8000 }), { videoStartMs: 4000, videoEndMs: 8000 });
  assert.deepEqual(T.previewSpan(v, { startMs: 4000, endMs: 13000 }), { fromS: 2, toS: 7 });
  assert.deepEqual(T.previewSpan(v, { startMs: 0, endMs: 3000 }), { fromS: 0, toS: 1 });
  assert.equal(T.previewSpan(v, { startMs: 9500, endMs: 12000 }), undefined);
  assert.equal(T.playheadMs(v, 1.5), 3500);
  assert.equal(T.fractionToMs(0.5, v), 6500);
  assert.equal(T.msToFraction(6500, v), 0.5);
  assert.equal(T.frames(6500), 195);
  assert.equal(T.frames(6513), 196);
});

test("Preview end plays the selection's last 8 s from the current version", () => {
  assert.equal(T.PREVIEW_END_MS, 8000);
  // the current version is the original's 2..60 s
  const v = view({ videoStartMs: 2000, videoEndMs: 60000, originalMs: 70000 });
  // normal: the last 8 s of 10..40 s are 32..40 s, i.e. 30..38 s into the version
  assert.deepEqual(T.previewEndSpan(v, { startMs: 10000, endMs: 40000 }), { fromS: 30, toS: 38 });
  // a selection shorter than 8 s plays from its start (like Preview)
  assert.deepEqual(T.previewEndSpan(v, { startMs: 10000, endMs: 15000 }), { fromS: 8, toS: 13 });
  assert.deepEqual(T.previewEndSpan(v, { startMs: 10000, endMs: 15000 }), T.previewSpan(v, { startMs: 10000, endMs: 15000 }));
  // a selection only partly in the current version: the start before it doesn't matter ...
  assert.deepEqual(T.previewEndSpan(v, { startMs: 0, endMs: 9000 }), { fromS: 0, toS: 7 });
  // ... and an end past it plays what the version has of the last 8 s (56..60 s)
  assert.deepEqual(T.previewEndSpan(v, { startMs: 10000, endMs: 64000 }), { fromS: 54, toS: 58 });
  // none of the last 8 s in the current version (62..70 s): nothing to play (the page says so, as for Preview)
  assert.equal(T.previewEndSpan(v, { startMs: 10000, endMs: 70000 }), undefined);
  assert.equal(T.previewEndSpan(v, { startMs: 61000, endMs: 66000 }), undefined);
  // a custom length
  assert.deepEqual(T.previewEndSpan(v, { startMs: 10000, endMs: 40000 }, 3000), { fromS: 35, toS: 38 });
  // an audio item's player (audio.m4a starts at its trim's start)
  const pv = T.playerView(view({ originalMs: 30000, audioStartMs: 5000, audioEndMs: 25000 }), false);
  assert.deepEqual(T.previewEndSpan(pv, { startMs: 5000, endMs: 25000 }), { fromS: 12, toS: 20 });
});

test("the audio trim: current range, player view, estimate and body", () => {
  // an audio item without a trim: the whole original; its player is audio.m4a from 0
  const a = view({ originalMs: 10000 });
  assert.deepEqual(T.audioRange(a, false), { startMs: 0, endMs: 10000 });
  assert.deepEqual(T.playerView(a, false), { videoStartMs: 0, videoEndMs: undefined, originalMs: 10000 });
  assert.equal(T.audioEstimate(a, false, { startMs: 0, endMs: 10000 }).kind, "none");
  const e = T.audioEstimate(a, false, { startMs: 2000, endMs: 7000 });
  assert.deepEqual(e, { kind: "audio", encodeSeconds: 5 });
  assert.match(T.estimateText(e), /Audio re-encode: about 5 s/);
  assert.deepEqual(T.audioTrimBody(a, { startMs: 2000, endMs: 10000 }), { audioStartMs: 2000, audioEndMs: null });
  assert.deepEqual(T.audioTrimBody(a, undefined), { audioStartMs: null });

  // a trimmed audio item: its player's 0 is the trim's start (preview and playhead)
  const t = view({ originalMs: 10000, audioStartMs: 2000, audioEndMs: 7000 });
  const pv = T.playerView(t, false);
  assert.deepEqual(T.audioRange(t, false), { startMs: 2000, endMs: 7000 });
  assert.equal(T.playheadMs(pv, 1), 3000);
  assert.deepEqual(T.previewSpan(pv, { startMs: 1000, endMs: 4000 }), { fromS: 0, toS: 2 });

  // a video without an audio trim: the audio-only range follows the video trim; saving that same range sets one
  const v = view({ videoStartMs: 2000, videoEndMs: 9000, originalMs: 13000 });
  assert.deepEqual(T.audioRange(v, true), { startMs: 2000, endMs: 9000 });
  assert.equal(T.playerView(v, true), v);
  assert.equal(T.audioEstimate(v, true, { startMs: 2000, endMs: 9000 }).kind, "audio");
  const va = view({ videoStartMs: 2000, videoEndMs: 9000, originalMs: 13000, audioStartMs: 0, audioOnlyMs: 13000 });
  assert.deepEqual(T.audioRange(va, true), { startMs: 0, endMs: 13000 });
  assert.equal(T.audioEstimate(va, true, { startMs: 0, endMs: 13000 }).kind, "none");
  // preview of an audio range on a video plays the part the current video version has
  assert.deepEqual(T.previewSpan(va, { startMs: 0, endMs: 5000 }), { fromS: 0, toS: 3 });
});

test("Set start / Set end take the player's position on the original's timeline, within the handles' limits", () => {
  // a video trimmed to 2..9 s: its player's 1.5 s is the original's 3.5 s
  const v = view({ videoStartMs: 2000, videoEndMs: 9000, originalMs: 13000 });
  const r = T.currentRange(v);
  assert.deepEqual(T.startAtPlayhead(r, v, 1.5), { startMs: 3500, endMs: 9000 });
  assert.deepEqual(T.endAtPlayhead(r, v, 5.25, v), { startMs: 2000, endMs: 7250 });
  // clamped: the start stays a second before the end, the end a second after the start
  assert.deepEqual(T.startAtPlayhead(r, v, 6.9), { startMs: 8000, endMs: 9000 });
  assert.deepEqual(T.endAtPlayhead({ startMs: 5000, endMs: 9000 }, v, 0.5, v), { startMs: 5000, endMs: 6000 });
  // the end can't go past the original's end; a negative time is the version's start
  assert.deepEqual(T.endAtPlayhead(r, v, 99, v), { startMs: 2000, endMs: 13000 });
  assert.deepEqual(T.startAtPlayhead(r, v, -1), { startMs: 2000, endMs: 9000 });
  // a trimmed audio item: its player (audio.m4a) starts at the audio trim's start
  const a = view({ originalMs: 10000, audioStartMs: 2000, audioEndMs: 7000 });
  const pv = T.playerView(a, false);
  const ar = T.audioRange(a, false);
  const timeline = { ...a, videoStartMs: 0 };
  assert.deepEqual(T.startAtPlayhead(ar, pv, 1), { startMs: 3000, endMs: 7000 });
  assert.deepEqual(T.endAtPlayhead(ar, pv, 4, timeline), { startMs: 2000, endMs: 6000 });
  // fractions of a millisecond round
  assert.deepEqual(T.startAtPlayhead(r, v, 0.0004), { startMs: 2000, endMs: 9000 });
  assert.deepEqual(T.startAtPlayhead(r, v, 0.0016), { startMs: 2002, endMs: 9000 });
});
