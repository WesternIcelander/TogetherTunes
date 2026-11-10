// Unit tests for web/src/remote.ts (compiled), run with Node's test runner by RemoteJsTest.
// The module path comes in REMOTE_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const R = await import(pathToFileURL(process.env.REMOTE_JS).href);

test("mode: the URL wins, then the room's remembered mode, then listen", () => {
  assert.equal(R.chooseMode("remote", "listen"), "remote");
  assert.equal(R.chooseMode("listen", "remote"), "listen");
  assert.equal(R.chooseMode(null, "remote"), "remote");
  assert.equal(R.chooseMode(null, "listen"), "listen");
  assert.equal(R.chooseMode(null, null), "listen");
  assert.equal(R.chooseMode("bogus", "nonsense"), "listen");
  assert.equal(R.chooseMode("bogus", "remote"), "remote");
  assert.notEqual(R.modeKey("a"), R.modeKey("b"));
  assert.notEqual(R.nudgeKey("a"), R.modeKey("a"));
});

test("delay: defaults per output kind, RTMP when no output is listed", () => {
  assert.deepEqual(R.outputDelay(undefined, undefined), { kind: "rtmp", delayMs: 5000 });
  assert.deepEqual(R.outputDelay([], {}), { kind: "rtmp", delayMs: 5000 });
  assert.deepEqual(R.outputDelay([{ kind: "ts", status: "running" }], {}), { kind: "ts", delayMs: 2000 });
  assert.deepEqual(R.outputDelay([{ kind: "hls" }], {}), { kind: "hls", delayMs: 8000 });
  assert.deepEqual(R.outputDelay([{ kind: "mp3", status: "ready" }], {}), { kind: "mp3", delayMs: 3000 });
  // MP3 is the last choice when the room has a video output too
  assert.equal(R.outputDelay([{ kind: "mp3", status: "ready" }, { kind: "hls", status: "ready" }], {}).kind, "hls");
  assert.deepEqual(R.outputDelay([{ kind: "mp3" }], { estimatedDelayMs: { mp3: 4500 } }), { kind: "mp3", delayMs: 4500 });
});

test("delay: room settings override defaults, the output's own value overrides settings", () => {
  const settings = { estimatedDelayMs: { rtmp: 7000, ts: 2500, hls: 9000 } };
  assert.deepEqual(R.outputDelay([], settings), { kind: "rtmp", delayMs: 7000 });
  assert.deepEqual(R.outputDelay([{ kind: "ts" }], settings), { kind: "ts", delayMs: 2500 });
  assert.deepEqual(R.outputDelay([{ kind: "ts", estimatedDelayMs: 1500 }], settings), { kind: "ts", delayMs: 1500 });
  // Nonsense values fall through.
  assert.deepEqual(R.outputDelay([{ kind: "ts", estimatedDelayMs: -1 }], { estimatedDelayMs: { ts: "x" } }),
    { kind: "ts", delayMs: 2000 });
});

test("delay: the active output is the preferred one that isn't failing or off", () => {
  const outs = [{ kind: "hls", status: "running" }, { kind: "ts", status: "running" }, { kind: "rtmp", status: "error" }];
  assert.equal(R.outputDelay(outs, {}).kind, "ts");
  assert.equal(R.outputDelay([{ kind: "hls" }, { kind: "rtmp", status: "connected" }], {}).kind, "rtmp");
  // All failing: still the preferred one rather than nothing.
  assert.equal(R.outputDelay([{ kind: "hls", status: "error" }, { kind: "ts", status: "disabled" }], {}).kind, "ts");
});

test("nudge: whole 0.5 s steps, stored value parsed safely, total delay kept within 0..120 s", () => {
  assert.equal(R.parseNudge(null), 0);
  assert.equal(R.parseNudge(""), 0);
  assert.equal(R.parseNudge("abc"), 0);
  assert.equal(R.parseNudge("1000"), 1000);
  assert.equal(R.parseNudge("-1500"), -1500);
  assert.equal(R.parseNudge("740"), 500);
  assert.equal(R.parseNudge("9999999"), 120000);
  assert.equal(R.stepNudge(0, 1, 5000), 500);
  assert.equal(R.stepNudge(500, -1, 5000), 0);
  assert.equal(R.stepNudge(0, -1, 5000), -500);
  // Can't go below a total of 0 …
  assert.equal(R.stepNudge(-2000, -1, 2000), -2000);
  assert.equal(R.stepNudge(-1500, -1, 2000), -2000);
  // … or above 120 s.
  assert.equal(R.stepNudge(0, 1, 120000), 0);
  assert.equal(R.effectiveDelay(5000, 500), 5500);
  assert.equal(R.effectiveDelay(2000, -5000), 0);
  assert.equal(R.effectiveDelay(119000, 5000), 120000);
  assert.equal(R.formatSeconds(5000), "5.0 s");
  assert.equal(R.formatSeconds(500, true), "+0.5 s");
  assert.equal(R.formatSeconds(-1500, true), "−1.5 s");
});

const tl = (entryId, state, anchorServerMs, anchorPositionMs = 0) => ({ entryId, state, anchorServerMs, anchorPositionMs });

test("delayed timeline: the state on joining counts as always in effect", () => {
  const d = new R.DelayedTimeline();
  assert.equal(d.atTime(0), undefined);
  d.push(tl("a", "playing", 100000, 0), "A", 160000);
  assert.equal(d.atTime(0).value, "A");
  assert.equal(d.atTime(160000).value, "A");
});

test("delayed timeline: a skip shows up only after the delay, dated at its anchor", () => {
  const d = new R.DelayedTimeline();
  d.push(tl("a", "playing", 100000), "A", 100000);
  d.push(tl("b", "playing", 200000), "B", 200040); // arrived 40 ms after it happened
  const delay = 5000; // the page asks for at(serverNow − delay)
  assert.equal(d.atTime(200040 - delay).value, "A"); // just received: still hearing A
  assert.equal(d.atTime(204999 - delay).value, "A");
  assert.equal(d.atTime(205000 - delay).value, "B"); // 5 s after the skip happened
  assert.equal(d.atTime(205100 - delay).timeline.entryId, "b");
});

test("delayed timeline: implausible anchors are dated by arrival, never before the previous change", () => {
  const d = new R.DelayedTimeline();
  d.push(tl("a", "playing", 100000), "A", 100000);
  d.push(tl(undefined, "idle", 0), "idle", 150000);        // idle carries no useful anchor
  assert.equal(d.atTime(149999).value, "A");
  assert.equal(d.atTime(150000).value, "idle");
  d.push(tl("c", "playing", 999999), "C", 160000);           // anchor in the future
  assert.equal(d.atTime(159999).value, "idle");
  assert.equal(d.atTime(160000).value, "C");
  d.push(tl("c", "paused", 100), "Cp", 170000);              // anchor long ago
  assert.equal(d.atTime(169999).value, "C");
  assert.equal(d.atTime(170000).value, "Cp");
});

test("delayed timeline: an unchanged timeline updates the value without a new change", () => {
  const d = new R.DelayedTimeline();
  d.push(tl("a", "playing", 100000), "A (processing)", 100000);
  d.push(tl("a", "playing", 100000), "A (ready)", 130000);
  assert.equal(d.size, 1);
  assert.equal(d.atTime(100000).value, "A (ready)");
  const t = tl("b", "playing", 140000);
  d.push(t, "B", 140000);
  t.anchorPositionMs = 99; // the caller's object changing later doesn't change the record
  assert.equal(d.atTime(140000).timeline.anchorPositionMs, 0);
});

test("delayed timeline: old changes are dropped once no delay can reach them", () => {
  const d = new R.DelayedTimeline();
  for (let i = 0; i < 100; i++) d.push(tl("e" + i, "playing", i * 10000), "E" + i, i * 10000);
  // Delays reach back at most 120 s (+30 s kept): about 15 changes of 10 s each, plus one.
  assert.ok(d.size <= 17, "kept " + d.size);
  assert.equal(d.atTime(990000 - 120000).value, "E87");
  d.clear();
  assert.equal(d.size, 0);
});
