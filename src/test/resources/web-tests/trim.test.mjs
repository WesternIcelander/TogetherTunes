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

test("handles clamp and snap to the current grid", () => {
  const v = view({ videoStartMs: 1500, videoEndMs: 9000 });
  const r = T.currentRange(v);
  assert.deepEqual(r, { startMs: 1500, endMs: 9000 });
  assert.equal(T.snapToGrid(4400, 1500, 13000), 3500);
  assert.equal(T.snapToGrid(4600, 1500, 13000), 5500);
  assert.equal(T.snapToGrid(200, 1500, 13000), 1500);
  assert.equal(T.snapToGrid(0, 1500, 13000), 1500, "0 is off the grid: the nearest keyframe at or after 0");
  assert.deepEqual(T.moveStart(r, 4600, v, true), { startMs: 5500, endMs: 9000 });
  assert.deepEqual(T.moveStart(r, 4600, v, false), { startMs: 4600, endMs: 9000 });
  assert.deepEqual(T.moveStart(r, 8800, v, false), { startMs: 8000, endMs: 9000 }, "1 s stays");
  assert.deepEqual(T.moveStart(r, 8800, v, true), { startMs: 7500, endMs: 9000 }, "snapped below the limit");
  assert.deepEqual(T.moveStart(r, -50, v, true), { startMs: 1500, endMs: 9000 });
  assert.deepEqual(T.moveEnd(r, 20000, v), { startMs: 1500, endMs: 13000 });
  assert.deepEqual(T.moveEnd(r, 1000, v), { startMs: 1500, endMs: 2500 });
  assert.equal(T.onGrid(5500, 1500), true);
  assert.equal(T.onGrid(5000, 1500), false);
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
  assert.equal(T.estimate(v, { startMs: 2500, endMs: 9000 }).kind, "full");
  assert.equal(T.estimate(v, { startMs: 2500, endMs: 9000 }).encodeSeconds, 6.5);
  assert.equal(T.estimate(view({ videoStartMs: 10000 }), { startMs: 2000, endMs: 6000 }).kind, "full",
    "nothing of the current encode is left");
  assert.match(T.estimateText({ kind: "full", encodeSeconds: 6.5 }), /^Full re-encode: about 7 s/);
  assert.match(T.estimateText({ kind: "smart", encodeSeconds: 2 }), /about 2 s/);
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
