// Unit tests for web/src/jobtext.ts (compiled), run with Node's test runner by JobsJsTest.
// The module path comes in JOBTEXT_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const J = await import(pathToFileURL(process.env.JOBTEXT_JS).href);

const row = (over) => ({
  id: "j", kind: "upload", state: "queued", progress: 0, attempts: 0, createdAt: 1000,
  item: { id: "i", kind: "audio", title: "Song", source: "upload", status: "pending" }, ...over,
});

test("ordinals", () => {
  assert.deepEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111, 112].map(J.ordinal),
    ["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th", "112th"]);
});

test("state lines", () => {
  assert.equal(J.stateText(row({ position: 3 })), "Queued, 3rd in line");
  assert.equal(J.stateText(row({})), "Queued");
  assert.equal(J.stateText(row({ state: "running", progress: 0.426, attempts: 1 })), "Running 42%");
  assert.equal(J.stateText(row({ state: "running", progress: 1.5, attempts: 2 })), "Running 100% (attempt 2)");
  assert.equal(J.stateText(row({ state: "failed", attempts: 1 })), "Failed");
  assert.equal(J.stateText(row({ state: "failed", attempts: 3 })), "Failed after 3 attempts");
  assert.equal(J.stateText(row({ state: "done", attempts: 1 })), "Done");
});

test("kinds and tiers", () => {
  assert.equal(J.kindText("upload"), "Upload");
  assert.equal(J.kindText("youtube"), "YouTube download");
  assert.equal(J.kindText("refetch"), "YouTube refetch");
  assert.equal(J.kindText("reencode"), "Re-encode (new output profile)");
  assert.equal(J.kindText("quality"), "Quality re-encode");
  assert.equal(J.kindText("future"), "future");
  assert.equal(J.tierText("quick"), "Quick preset");
  assert.equal(J.tierText("quality"), "Quality preset");
  assert.equal(J.tierText(undefined), "");
});

test("elapsed time", () => {
  assert.equal(J.elapsedText(row({}), 5000), "");
  assert.equal(J.elapsedText(row({ state: "running", startedAt: 1000 }), 13_400), "12 s");
  assert.equal(J.elapsedText(row({ state: "done", startedAt: 0, finishedAt: 185_000 }), 1e9), "3 min 05 s");
  assert.equal(J.elapsedText(row({ state: "failed", startedAt: 0, finishedAt: 3_720_000 }), 1e9), "1 h 02 min");
  // a queued job that ran before (released at a shutdown) shows no time
  assert.equal(J.elapsedText(row({ startedAt: 0 }), 1e9), "");
});

test("empty lists say whose jobs", () => {
  assert.equal(J.emptyText("active", true), "Nothing is encoding or queued.");
  assert.equal(J.emptyText("failed", false), "No failed jobs for items in your library.");
  assert.equal(J.emptyText("done", false), "No jobs finished in the last 24 hours for items in your library.");
});
