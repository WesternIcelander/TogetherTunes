// Unit tests for web/src/roomsearch.ts (compiled), run with Node's test runner by RoomSearchJsTest.
// The module path comes in ROOMSEARCH_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const R = await import(pathToFileURL(process.env.ROOMSEARCH_JS).href);

const guest = (over = {}) => ({ identified: true, canYoutube: false, canRequestLibrary: false, ...over });

test("which buttons show", () => {
  assert.deepEqual(R.roomSearchButtons("guest", guest({ canRequestLibrary: true })), { library: true, youtube: false });
  assert.deepEqual(R.roomSearchButtons("guest", guest({ canRequestLibrary: true, canYoutube: true })),
    { library: true, youtube: true });
  assert.deepEqual(R.roomSearchButtons("guest", guest({ canYoutube: true })), { library: false, youtube: true },
    "a guest who may only request links still gets Search YouTube");
  assert.deepEqual(R.roomSearchButtons("guest", guest()), { library: false, youtube: false });
  assert.deepEqual(R.roomSearchButtons("user", guest({ canRequestLibrary: true, canYoutube: true })),
    { library: false, youtube: false }, "users search from the header");
  assert.deepEqual(R.roomSearchButtons("anonymous", guest({ identified: false, canRequestLibrary: true, canYoutube: true })),
    { library: false, youtube: false }, "anonymous visitors join as a guest first");
  assert.deepEqual(R.roomSearchButtons("guest", guest({ canRequestLibrary: true, canYoutube: true,
    restriction: { kind: "timeout" } })), { library: false, youtube: false }, "banned or timed out");
  assert.deepEqual(R.roomSearchButtons("guest", { identified: true, canYoutube: true }), { library: false, youtube: true },
    "canRequestLibrary left out");
});

test("headings", () => {
  assert.equal(R.roomSearchHeading({ library: true }, false), "Request from the library");
  assert.equal(R.roomSearchHeading({ library: true }, true), "Suggest from the library");
  assert.equal(R.roomSearchHeading({ library: false }, false), "Request from YouTube");
  assert.equal(R.roomSearchHeading({ library: false }, true), "Suggest from YouTube");
  assert.equal(R.SEARCH_YOUTUBE_LABEL, "Search YouTube");
});

test("the room's YouTube search path", () => {
  assert.equal(R.roomYoutubeSearchPath("01H x", "  katy perry "),
    "/api/rooms/01H%20x/youtube-search?q=katy+perry&limit=10");
  assert.equal(R.roomYoutubeSearchPath("r", "a&b", 5), "/api/rooms/r/youtube-search?q=a%26b&limit=5");
});

test("typing clears the results until the next search", () => {
  const s = new R.RoomSearchResults();
  assert.equal(s.shown, "none");
  assert.equal(s.input(), false, "nothing shown yet");
  const a = s.start("library");
  assert.equal(s.shown, "library");
  assert.equal(s.accepts(a), true);
  assert.equal(s.moreAllowed(), true);
  assert.equal(s.input(), true, "a change to the text box clears them");
  assert.equal(s.shown, "none");
  assert.equal(s.accepts(a), false, "an answer still on its way is dropped");
  assert.equal(s.moreAllowed(), false);
  const b = s.start("youtube");
  assert.equal(s.shown, "youtube");
  assert.equal(s.moreAllowed(), false, "no More for YouTube results");
  const c = s.start("library");
  assert.equal(s.accepts(b), false, "a newer search wins");
  assert.equal(s.accepts(c), true);
});

test("texts", () => {
  assert.equal(R.youtubeRequestedText("Firework", "queued"),
    "Firework: Added to the queue. New videos play once they have been downloaded.");
  assert.equal(R.youtubeRequestedText("Firework", "suggested"),
    "Firework: Sent as a suggestion. New videos play once they have been downloaded.");
  assert.equal(R.roomYoutubeErrorText("too_many_searches", "x"),
    "Too many YouTube searches in the past hour. Try again later.");
  assert.equal(R.roomYoutubeErrorText("guest_youtube_disabled", "x"), "Guests can't request YouTube links in this room.");
  assert.equal(R.roomYoutubeErrorText("identity_required", "x"), "Join as a guest first.");
  assert.equal(R.roomYoutubeErrorText("other", "fallback"), "fallback");
});
