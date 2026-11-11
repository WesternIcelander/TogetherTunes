// Unit tests for web/src/mediatypes.ts (compiled), run with Node's test runner by MediaTypesJsTest.
// The module path comes in MEDIATYPES_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const M = await import(pathToFileURL(process.env.MEDIATYPES_JS).href);

test("labels and the types an item can have", () => {
  assert.equal(M.mediaTypeLabel("show"), "Show episode");
  assert.equal(M.mediaTypeLabel("nonsense"), "Video");
  assert.equal(M.mediaTypeLabel("constructor"), "Video");
  assert.deepEqual(M.allowedTypes("audio"), ["audio"]);
  assert.equal(M.allowedTypes("video").length, 5);
});

test("an edit body: show details only for a show, validated", () => {
  assert.deepEqual(M.mediaTypeBody("movie", "ignored", "1", "2"), { body: { mediaType: "movie" } });
  assert.deepEqual(M.mediaTypeBody("show", " The Show ", "", "0"),
    { body: { mediaType: "show", show: { name: "The Show", episode: 0 } } });
  assert.deepEqual(M.mediaTypeBody("show", "S", "2", "5"),
    { body: { mediaType: "show", show: { name: "S", season: 2, episode: 5 } } });
  assert.ok("error" in M.mediaTypeBody("show", " ", "", "1"));
  assert.ok("error" in M.mediaTypeBody("show", "S", "", ""));
  assert.ok("error" in M.mediaTypeBody("show", "S", "-1", "1"));
  assert.ok("error" in M.mediaTypeBody("show", "S", "1", "1.5"));
  assert.ok("error" in M.mediaTypeBody("song", "", "", ""));
});
