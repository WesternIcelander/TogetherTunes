// Unit tests for web/src/classify.ts (compiled), run with Node's test runner by ClassifyJsTest.
// The module path comes in CLASSIFY_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const C = await import(pathToFileURL(process.env.CLASSIFY_JS).href);

function error(status, code, details) {
  return { status, code, message: "m", details };
}

test("the classify question in an API error", () => {
  assert.deepEqual(C.needsClassification(error(409, "needs_classification", { code: "needs_classification", itemId: "i1" })),
    { itemId: "i1" });
  assert.deepEqual(C.needsClassification(error(409, "needs_classification", {
    itemId: "i2", suggested: { mediaType: "show", show: { name: "The Show", season: 2, episode: 3 } },
  })), { itemId: "i2", suggested: { mediaType: "show", show: { name: "The Show", season: 2, episode: 3 } } });
  assert.deepEqual(C.needsClassification(error(409, "needs_classification", {
    itemId: "i3", suggested: { mediaType: "music_video" },
  })), { itemId: "i3", suggested: { mediaType: "music_video" } });
  // a suggestion the client can't offer is dropped, not the question
  assert.deepEqual(C.needsClassification(error(409, "needs_classification", {
    itemId: "i4", suggested: { mediaType: "uncategorized" },
  })), { itemId: "i4" });
  assert.equal(C.needsClassification(error(409, "media_expired", { itemId: "x" })), undefined);
  assert.equal(C.needsClassification(error(400, "needs_classification", { itemId: "x" })), undefined);
  assert.equal(C.needsClassification(error(409, "needs_classification", {})), undefined);
  assert.equal(C.needsClassification(new Error("x")), undefined);
  assert.equal(C.needsClassification(null), undefined);
});

test("the choices and the prefill", () => {
  assert.deepEqual([...C.CLASSIFY_TYPES].sort(), ["audio", "movie", "music_video", "show"]);
  assert.deepEqual(C.classifyDefaults(undefined), { mediaType: "", showName: "", season: "", episode: "" });
  assert.deepEqual(C.classifyDefaults({ mediaType: "music_video" }),
    { mediaType: "music_video", showName: "", season: "", episode: "" });
  assert.deepEqual(C.classifyDefaults({ mediaType: "show", show: { name: "S", episode: 4 } }),
    { mediaType: "show", showName: "S", season: "", episode: "4" });
  assert.deepEqual(C.classifyDefaults({ mediaType: "show", show: { season: 0 } }),
    { mediaType: "show", showName: "", season: "0", episode: "" });
});

test("the body: a type is required, uncategorized isn't one, show details validated", () => {
  assert.ok("error" in C.classifyBody("", "", "", ""));
  assert.ok("error" in C.classifyBody("uncategorized", "", "", ""));
  assert.deepEqual(C.classifyBody("movie", "x", "1", "2"), { body: { mediaType: "movie" } });
  assert.deepEqual(C.classifyBody("show", "S", "1", "2"),
    { body: { mediaType: "show", show: { name: "S", season: 1, episode: 2 } } });
  assert.ok("error" in C.classifyBody("show", "", "1", "2"));
  assert.ok("error" in C.classifyBody("show", "S", "1", ""));
});
