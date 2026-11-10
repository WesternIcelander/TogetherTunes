// Unit tests for web/src/diagnostics.ts (compiled), run with Node's test runner by ListenJsTest.
// The module path comes in DIAGNOSTICS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const D = await import(pathToFileURL(process.env.DIAGNOSTICS_JS).href);

test("ring keeps the last max items and counts all", () => {
  const r = new D.Ring(3);
  for (let i = 1; i <= 5; i++) r.push(i);
  assert.deepEqual(r.toArray(), [3, 4, 5]);
  assert.equal(r.size, 3);
  assert.equal(r.total, 5);
  const copy = r.toArray();
  copy.push(99);
  assert.deepEqual(r.toArray(), [3, 4, 5]); // a copy
  r.clear();
  assert.deepEqual(r.toArray(), []);
});

test("decisions: the last 200 are kept, with a time", () => {
  D.clearForTests();
  for (let i = 0; i < 250; i++) {
    D.recordDecision({ el: "a0", drift: i, action: "steady", rate: 1, readyState: 4, seeking: false, paused: false });
  }
  const s = D.snapshot();
  assert.equal(s.decisions.length, D.DECISIONS);
  assert.equal(D.DECISIONS, 200);
  assert.equal(s.decisionsTotal, 250);
  assert.equal(s.decisions[0].drift, 50);
  assert.equal(s.decisions[199].drift, 249);
  assert.equal(typeof s.decisions[0].t, "number");
});

test("page errors and unhandled rejections are captured from install on", () => {
  D.clearForTests();
  const listeners = {};
  const target = { addEventListener: (type, l) => { listeners[type] = l; } };
  D.captureErrors(target);
  D.captureErrors({ addEventListener: () => assert.fail("installed twice") });
  const e = new TypeError("x is not a function");
  listeners.error({ message: "Uncaught TypeError", filename: "https://h/web/js/listen.js?v=1", lineno: 10, colno: 5, error: e });
  listeners.unhandledrejection({ reason: { name: "NotAllowedError", message: "play() not allowed" } });
  listeners.unhandledrejection({ reason: "plain" });
  const errors = D.snapshot().errors;
  assert.equal(errors.length, 3);
  assert.equal(errors[0].kind, "error");
  assert.match(errors[0].error, /^TypeError: x is not a function/);
  assert.equal(errors[0].where, "https://h/web/js/listen.js:10:5"); // no query
  assert.equal(errors[1].kind, "unhandledrejection");
  assert.equal(errors[1].error, "NotAllowedError: play() not allowed");
  assert.equal(errors[2].error, "plain");
});

test("errors caught by the player, and long texts cut", () => {
  D.clearForTests();
  D.recordError("tick", new RangeError("bad"), { el: "v0" });
  D.recordError("tick", "y".repeat(5000));
  const errors = D.snapshot().errors;
  assert.equal(errors[0].kind, "tick");
  assert.equal(errors[0].el, "v0");
  assert.match(errors[0].error, /^RangeError: bad/);
  assert.equal(errors[1].error.length, D.MAX_ERROR_TEXT + 1);
  assert.equal(D.describeError({ a: 1 }), "{\"a\":1}");
  assert.equal(D.describeError(undefined), "undefined");
});

test("events ring and stripQuery", () => {
  D.clearForTests();
  for (let i = 0; i < D.EVENTS + 10; i++) D.recordEvent("seek", { toMs: i });
  const s = D.snapshot();
  assert.equal(s.events.length, D.EVENTS);
  assert.equal(s.events[0].kind, "seek");
  assert.equal(s.events[0].toMs, 10);
  assert.equal(D.stripQuery("/rooms/x?token=abc#f"), "/rooms/x");
  assert.equal(D.stripQuery("/rooms/x#f"), "/rooms/x");
  assert.equal(D.stripQuery("/rooms/x"), "/rooms/x");
});

test("health: one sample per second, the last 300 (5 minutes) kept", () => {
  D.clearForTests();
  for (let i = 0; i < 320; i++) D.recordHealth({ pos: i, out: { v0: [-12.5, -20, 25, 0, 0] } });
  const s = D.snapshot();
  assert.equal(D.HEALTH, 300);
  assert.equal(s.health.length, 300);
  assert.equal(s.healthTotal, 320);
  assert.equal(s.health[0].pos, 20);
  assert.deepEqual(s.health[299].out, { v0: [-12.5, -20, 25, 0, 0] });
  assert.equal(typeof s.health[0].t, "number");
  // Compact: a full ring of typical samples stays far below the report limit (240 000 chars).
  D.clearForTests();
  for (let i = 0; i < 300; i++) {
    D.recordHealth({ ctx: "running", ck: 1000, out: { v0: [-3.2, -14.1, 25, 0, 0] }, el: "v0", pos: 168261 + i * 1000,
      dr: -1, rs: 4, g: 1, wk: [12000, 400000, 30, 0], vq: [5000 + i * 30, 12, 0], ms: 3 });
  }
  assert.ok(JSON.stringify(D.snapshot().health).length < 60000);
});
