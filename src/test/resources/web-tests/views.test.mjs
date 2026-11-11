// Unit tests for web/src/views.ts (compiled), run with Node's test runner by ViewsJsTest.
// The module path comes in VIEWS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const V = await import(pathToFileURL(process.env.VIEWS_JS).href);

test("paths", () => {
  assert.equal(V.viewPath("a/b"), "/api/items/a%2Fb/view");
  assert.equal(V.pinPath("x"), "/api/items/x/pin");
});

test("one report per item a minute; other items at once", () => {
  let t = 1000;
  const sent = [];
  const report = V.viewReporter((id) => { sent.push([id, t]); return Promise.resolve(); }, () => t);
  report("a");
  report("a"); // play, pause, play again
  t += 30_000;
  report("a");
  report("b");
  assert.deepEqual(sent, [["a", 1000], ["b", 31_000]]);
  t += 30_000;
  report("a");
  assert.deepEqual(sent.at(-1), ["a", 61_000]);
  assert.equal(V.VIEW_REPORT_GAP_MS, 60_000);
});

test("a failed or throwing report is dropped quietly", async () => {
  const report = V.viewReporter(() => Promise.reject(new Error("offline")), () => 0);
  report("a");
  const throwing = V.viewReporter(() => { throw new Error("boom"); }, () => 0);
  throwing("a");
  await new Promise((r) => setTimeout(r, 10)); // no unhandled rejection
});

test("a clock that went backwards doesn't block reports", () => {
  let t = 100_000;
  const sent = [];
  const report = V.viewReporter((id) => { sent.push(id); return Promise.resolve(); }, () => t);
  report("a");
  t = 50_000;
  report("a");
  assert.equal(sent.length, 2);
});

test("the pin line", () => {
  assert.equal(V.pinText(undefined, String), undefined);
  assert.equal(V.pinText({ pinnedAt: 5 }, (ms) => "T" + ms), "T5 (its media is never deleted automatically)");
});
