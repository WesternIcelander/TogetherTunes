// Unit tests for web/src/videosearch.ts (compiled), run with Node's test runner by VideoSearchJsTest.
// The module path comes in VIDEOSEARCH_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const V = await import(pathToFileURL(process.env.VIDEOSEARCH_JS).href);

test("candidate lines: channel, length and type, the known parts only", () => {
  assert.equal(V.candidateDetail({ videoId: "a", title: "T", channel: "KatyPerryVEVO", durationMs: 231500 }),
    "KatyPerryVEVO · 3:52");
  assert.equal(V.candidateDetail({ videoId: "a", title: "T", channel: "A, B", durationMs: 214000, type: "lyric_video" }),
    "A, B · 3:34 · Lyric Video");
  assert.equal(V.candidateDetail({ videoId: "a", title: "T", type: "visualizer" }), "Visualizer");
  assert.equal(V.candidateDetail({ videoId: "a", title: "T", type: "official_video" }), "Official video");
  assert.equal(V.candidateDetail({ videoId: "a", title: "T", type: "music_video" }), "Music Video");
  assert.equal(V.candidateDetail({ videoId: "a", title: "T", type: "something_new" }), "");
  assert.equal(V.candidateDetail({ videoId: "a", title: "T" }), "");
});

test("lengths", () => {
  assert.equal(V.lengthText(undefined), "");
  assert.equal(V.lengthText(0), "");
  assert.equal(V.lengthText(5000), "0:05");
  assert.equal(V.lengthText(61000), "1:01");
  assert.equal(V.lengthText(3723000), "1:02:03");
});

test("the pick's body and what it did", () => {
  assert.deepEqual(V.pickBody({ videoId: "AAAAAAAAAAA", title: "x", channel: "y" }), { videoId: "AAAAAAAAAAA" });
  assert.equal(V.pickedText({ itemId: "v", status: "pending", linked: true, linkType: "music_video" }, "Firework"),
    "Linked to Firework as Music Video.");
  assert.equal(V.pickedText({ itemId: "v", status: "ready", linked: true, linkType: "lyric_video" }, "Firework"),
    "Linked to Firework as Lyric Video.");
  assert.match(V.pickedText({ itemId: "v", status: "ready", linked: false, linkType: "music_video" }, "Firework"),
    /^Not linked: Firework already has/);
  assert.equal(V.NO_VIDEO_FOUND, "No music video found.");
});

test("errors", () => {
  assert.equal(V.searchErrorText("too_many_searches", "x"), "Too many searches in the past hour. Try again later.");
  assert.equal(V.searchErrorText("not_a_candidate", "x"), "That video isn't available any more. Search again.");
  assert.equal(V.searchErrorText("search_failed", "Searching YouTube failed"), "Searching YouTube failed");
  assert.equal(V.searchErrorText(undefined, "fallback"), "fallback");
});

test("a candidate row: title, uploader · length, the two buttons", () => {
  assert.deepEqual(V.candidateRow({ videoId: "AAAAAAAAAAA", title: "Firework", channel: "KatyPerryVEVO", durationMs: 234000 }),
    { title: "Firework", detail: "KatyPerryVEVO · 3:54", preview: "Preview on YT", use: "Use this video" });
  assert.deepEqual(V.candidateRow({ videoId: "AAAAAAAAAAA", title: "Firework" }),
    { title: "Firework", detail: "", preview: "Preview on YT", use: "Use this video" });
});

test("the preview opens the watch page", () => {
  assert.equal(V.previewUrl("AAAAAAAAAAA"), "https://www.youtube.com/watch?v=AAAAAAAAAAA");
  assert.equal(V.previewUrl("a-b_c"), "https://www.youtube.com/watch?v=a-b_c");
});

test("thumbnails: the server's, else YouTube's own", () => {
  assert.equal(V.thumbnailSrc({ videoId: "AAAAAAAAAAA", thumbnailUrl: "https://i.ytimg.com/vi/AAAAAAAAAAA/mqdefault.jpg" }),
    "https://i.ytimg.com/vi/AAAAAAAAAAA/mqdefault.jpg");
  assert.equal(V.thumbnailSrc({ videoId: "AAAAAAAAAAA" }), "https://i.ytimg.com/vi/AAAAAAAAAAA/hqdefault.jpg");
  assert.equal(V.thumbnailSrc({ videoId: "AAAAAAAAAAA", thumbnailUrl: "" }), "https://i.ytimg.com/vi/AAAAAAAAAAA/hqdefault.jpg");
});
