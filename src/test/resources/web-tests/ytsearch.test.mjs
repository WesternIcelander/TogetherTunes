// Unit tests for web/src/ytsearch.ts (compiled), run with Node's test runner by YtSearchJsTest.
// The module path comes in YTSEARCH_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const Y = await import(pathToFileURL(process.env.YTSEARCH_JS).href);

test("who may search YouTube", () => {
  assert.equal(Y.canSearchYoutube(undefined), false);
  assert.equal(Y.canSearchYoutube({ kind: "anonymous" }), false);
  assert.equal(Y.canSearchYoutube({ kind: "guest" }), false);
  assert.equal(Y.canSearchYoutube({ kind: "user", permissions: ["library.upload"] }), false,
    "without library.youtube (also what /api/me says while ytdlp.enabled is off)");
  assert.equal(Y.canSearchYoutube({ kind: "user", permissions: ["library.youtube"] }), true);
});

test("row texts: thumbnail, title, uploader · length, Preview on YT", () => {
  const r = Y.youtubeRow({ videoId: "QGJuMBdaqIw", title: "Firework", channel: "KatyPerryVEVO", durationMs: 234000,
    type: "music_video", thumbnailUrl: "https://i.ytimg.com/vi/QGJuMBdaqIw/hq720.jpg" });
  assert.deepEqual(r, { title: "Firework", detail: "KatyPerryVEVO · 3:54", preview: "Preview on YT",
    thumbnail: "https://i.ytimg.com/vi/QGJuMBdaqIw/hq720.jpg", url: "https://www.youtube.com/watch?v=QGJuMBdaqIw" });
  const bare = Y.youtubeRow({ videoId: "a-b_c", title: "T" });
  assert.equal(bare.detail, "", "no type, no unknown parts");
  assert.equal(bare.thumbnail, "https://i.ytimg.com/vi/a-b_c/hqdefault.jpg");
  assert.equal(Y.youtubeDetail({ videoId: "x", title: "T", durationMs: 61000 }), "1:01");
  assert.equal(Y.youtubeDetail({ videoId: "x", title: "T", channel: "C" }), "C");
  assert.equal(Y.watchUrl({ videoId: "x" }), "https://www.youtube.com/watch?v=x");
});

test("which actions a row offers", () => {
  assert.deepEqual(Y.youtubeActions(undefined), ["addToLibrary"], "no room open here");
  assert.deepEqual(Y.youtubeActions({ you: { canYoutube: true } }), ["addToQueue", "addToLibrary"]);
  assert.deepEqual(Y.youtubeActions({ you: { canYoutube: false } }), ["addToLibrary"], "the room takes no links from them");
  assert.deepEqual(Y.youtubeActions({ you: { canYoutube: true, restriction: { kind: "timeout" } } }), ["addToLibrary"],
    "banned or timed out there");
  assert.equal(Y.YOUTUBE_ACTION_LABELS.addToQueue, "Add to Queue");
  assert.equal(Y.YOUTUBE_ACTION_LABELS.addToLibrary, "Add to library…");
});

test("the search path", () => {
  assert.equal(Y.youtubeSearchPath(" katy perry "), "/api/youtube/search?q=katy+perry&limit=10");
  assert.equal(Y.youtubeSearchPath("a&b", 8), "/api/youtube/search?q=a%26b&limit=8");
  assert.equal(Y.PAGE_LIMIT, 10);
  assert.equal(Y.DROPDOWN_YOUTUBE_LIMIT, 8);
});

test("texts after Add to Queue and on errors", () => {
  assert.equal(Y.queuedText("added", "Firework", "Den"),
    "Firework added to Den. New videos play once they have been downloaded.");
  assert.equal(Y.queuedText("added", "Firework", "Den", false), "Firework added to Den.");
  assert.equal(Y.queuedText("suggested", "Firework", "Den"), "Firework suggested to Den.");
  assert.equal(Y.queuedText("playing", "Firework", "Den"), "Playing Firework.");
  assert.equal(Y.youtubeErrorText("too_many_searches", "x"), "Too many YouTube searches in the past hour. Try again later.");
  assert.equal(Y.youtubeErrorText("feature_disabled", "x"), "Adding videos from links is turned off on this server.");
  assert.equal(Y.youtubeErrorText("search_failed", "Searching YouTube failed"), "Searching YouTube failed");
  assert.equal(Y.youtubeErrorText(undefined, "x"), "x");
  assert.equal(Y.YOUTUBE_SECTION_TITLE, "YouTube");
  assert.equal(Y.NO_YOUTUBE_RESULTS, "Nothing found on YouTube.");
});
