// Unit tests for web/src/dragdrop.ts (compiled), run with Node's test runner by DragDropJsTest.
// The module path comes in DRAGDROP_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const D = await import(pathToFileURL(process.env.DRAGDROP_JS).href);

const you = (o = {}) => ({ identified: true, canVote: true, canRequest: true, canYoutube: true, canSkip: false,
  canModerate: false, ...o });
const room = (controlMode, y = {}, alone = undefined) => ({
  room: { controlMode, you: you(y), settings: {}, ...(alone !== undefined ? { alone } : {}) },
  queue: [], suggestions: [],
});
const queueDrag = { kind: "items", source: "queue", itemIds: ["i1", "i2"], entryIds: ["e1", "e2"] };
const songs = { kind: "items", source: "search", itemIds: ["a", "b"] };

test("payloads round-trip and malformed ones are refused", () => {
  for (const p of [queueDrag, songs, { kind: "playlist", playlistId: "p1" },
    { kind: "items", source: "playlist", itemIds: ["x"], playlistId: "p", entryIds: ["pe"] }]) {
    assert.deepEqual(D.decodePayload(D.encodePayload(p)), p);
  }
  for (const bad of [undefined, null, "", "{", "[]", "1", "{\"kind\":\"items\"}",
    "{\"kind\":\"items\",\"source\":\"elsewhere\",\"itemIds\":[\"a\"]}",
    "{\"kind\":\"items\",\"source\":\"queue\",\"itemIds\":[]}",
    "{\"kind\":\"items\",\"source\":\"queue\",\"itemIds\":[\"a\",3]}",
    "{\"kind\":\"items\",\"source\":\"queue\",\"itemIds\":[\"a\"],\"entryIds\":[\"x\",\"y\"]}",
    "{\"kind\":\"playlist\"}", "{\"kind\":\"playlist\",\"playlistId\":\"\"}"]) {
    assert.equal(D.decodePayload(bad), undefined, String(bad));
  }
});

test("drop content: ours first, then files, then links", () => {
  assert.equal(D.dropContent([D.DRAG_TYPE, "text/plain", "text/uri-list"]), "internal");
  assert.equal(D.dropContent(["Files"]), "files");
  assert.equal(D.dropContent(["text/uri-list", "text/plain"]), "link");
  assert.equal(D.dropContent(["text/plain"]), "link");
  assert.equal(D.dropContent(["text/html"]), "none");
  assert.equal(D.dropContent([]), "none");
});

test("the link of a drop", () => {
  assert.equal(D.linkFromDrop("# comment\nhttps://youtu.be/abc\nhttps://other", ""), "https://youtu.be/abc");
  assert.equal(D.linkFromDrop("", "  https://www.youtube.com/watch?v=x  "), "https://www.youtube.com/watch?v=x");
  assert.equal(D.linkFromDrop("", "some words https://x"), undefined);
  assert.equal(D.linkFromDrop("", "javascript:alert(1)"), undefined);
  assert.equal(D.linkFromDrop("", ""), undefined);
});

test("where a drop lands in a list", () => {
  const keys = ["a", "b", "c"];
  assert.equal(D.dropBefore(keys, "b", false), "b");
  assert.equal(D.dropBefore(keys, "b", true), "c");
  assert.equal(D.dropBefore(keys, "c", true), null);
  assert.equal(D.dropBefore(keys, "a", false), "a");
  assert.equal(D.dropBefore(keys, undefined, false), null);
  assert.equal(D.dropBefore(keys, "zz", false), null);
});

test("a drag carries the selection when it starts on a selected row", () => {
  const sel = new Set(["c", "a"]);
  const has = (k) => sel.has(k);
  assert.deepEqual(D.draggedKeys(has, ["a", "b", "c"], "c"), ["a", "c"]);
  assert.deepEqual(D.draggedKeys(has, ["a", "b", "c"], "b"), ["b"]);
  assert.deepEqual(D.draggedKeys(() => false, ["a"], "a"), ["a"]);
});

test("moves that change nothing are noticed", () => {
  const order = ["a", "b", "c", "d"];
  assert.equal(D.moveIsNoop(order, ["b"], "c"), true);
  assert.equal(D.moveIsNoop(order, ["b"], "b"), true);
  assert.equal(D.moveIsNoop(order, ["b", "c"], "d"), true);
  assert.equal(D.moveIsNoop(order, ["b", "c"], "c"), true);
  assert.equal(D.moveIsNoop(order, ["d"], null), true);
  assert.equal(D.moveIsNoop(order, ["b"], "a"), false);
  assert.equal(D.moveIsNoop(order, ["b"], null), false);
  assert.equal(D.moveIsNoop(order, ["c", "b"], "d"), false);
  assert.equal(D.moveIsNoop(order, ["x"], null), false);
  assert.equal(D.moveIsNoop(order, [], null), true);
});

test("playlist order body", () => {
  assert.deepEqual(D.playlistOrderBody("p2", "p1"), { playlistIds: ["p2"], beforePlaylistId: "p1" });
  assert.deepEqual(D.playlistOrderBody("p2", null), { playlistIds: ["p2"], beforePlaylistId: null });
});

test("dropping on a playlist: its own entries move in its view, everything else is added", () => {
  const own = { kind: "items", source: "playlist", itemIds: ["i1"], playlistId: "p", entryIds: ["pe1"] };
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: true }, own), { kind: "move", entryIds: ["pe1"] });
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: false }, own), { kind: "none" });
  assert.deepEqual(D.playlistDrop({ playlistId: "q", inView: true }, own), { kind: "add", itemIds: ["i1"] });
  assert.deepEqual(D.playlistDrop({ playlistId: "q", inView: false }, own), { kind: "add", itemIds: ["i1"] });
  // from the queue, Recents and search results: added (no queue rights needed to drag out)
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: true }, queueDrag), { kind: "add", itemIds: ["i1", "i2"] });
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: false },
    { kind: "items", source: "recents", itemIds: ["r"] }), { kind: "add", itemIds: ["r"] });
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: true }, songs), { kind: "add", itemIds: ["a", "b"] });
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: true }, { kind: "playlist", playlistId: "p" }), { kind: "none" });
  assert.deepEqual(D.playlistDrop({ playlistId: "p", inView: true }, undefined), { kind: "none" });
});

test("who may reorder the queue", () => {
  assert.equal(D.canMoveQueue(room("owner", { canModerate: true })), true);
  assert.equal(D.canMoveQueue(room("owner")), false);
  assert.equal(D.canMoveQueue(room("open")), true);
  assert.equal(D.canMoveQueue(room("open", { identified: false })), false);
  assert.equal(D.canMoveQueue(room("open", { canRequest: false })), false);
  assert.equal(D.canMoveQueue(room("open", { canRequest: false, canModerate: true })), true);
  // never in vote mode, not even the owner
  assert.equal(D.canMoveQueue(room("vote", { canModerate: true })), false);
  // read-only (banned or timed out)
  assert.equal(D.canMoveQueue(room("owner", { canModerate: true, restriction: { kind: "timeout" } })), false);
  // alone: always
  assert.equal(D.canMoveQueue(room("owner", {}, true)), true);
  assert.match(D.queueMoveRefusal(room("vote", { canModerate: true })), /votes/);
  assert.match(D.queueMoveRefusal(room("owner")), /owner and moderators/);
});

test("dropping on the queue: entries move with the rights, songs are requested", () => {
  const owner = room("owner", { canModerate: true });
  assert.deepEqual(D.queueDrop(owner, queueDrag, "autoplay", "e9"), { kind: "move",
    message: { type: "queue.move", entryIds: ["e1", "e2"], beforeEntryId: "e9", section: "autoplay" } });
  assert.deepEqual(D.queueDrop(owner, queueDrag, "manual", null), { kind: "move",
    message: { type: "queue.move", entryIds: ["e1", "e2"], beforeEntryId: null, section: "manual" } });
  assert.equal(D.queueDrop(room("owner"), queueDrag, "manual", null).kind, "refused");
  assert.equal(D.queueDrop(room("vote", { canModerate: true }), queueDrag, "manual", null).kind, "refused");
  assert.deepEqual(D.queueDrop(room("vote"), songs, "manual", null), { kind: "add", itemIds: ["a", "b"] });
  assert.deepEqual(D.queueDrop(owner, { kind: "items", source: "recents", itemIds: ["r"] }, "manual", null),
    { kind: "add", itemIds: ["r"] });
  // Now Playing and suggestions (no entry ids) only drag out
  assert.deepEqual(D.queueDrop(owner, { kind: "items", source: "queue", itemIds: ["c"] }, "manual", null), { kind: "none" });
  assert.deepEqual(D.queueDrop(owner, { kind: "playlist", playlistId: "p" }, "manual", null), { kind: "none" });
  assert.equal(D.queueShowsPosition(owner, queueDrag), true);
  assert.equal(D.queueShowsPosition(room("owner"), queueDrag), false);
  assert.equal(D.queueShowsPosition(owner, songs), false);
});

test("links and files from outside on the queue: by the request rules", () => {
  const r = room("open", { canYoutube: true, canUpload: true });
  assert.equal(D.queueExternalDrop(r, "link"), "link");
  assert.equal(D.queueExternalDrop(r, "files"), "files");
  assert.equal(D.queueExternalDrop(r, "none"), "none");
  assert.equal(D.queueExternalDrop(r, "internal"), "none");
  assert.equal(D.queueExternalDrop(room("open", { canYoutube: false, canUpload: true }), "link"), "none");
  assert.equal(D.queueExternalDrop(room("open", { canYoutube: true }), "files"), "none");
  assert.equal(D.queueExternalDrop(room("open", { canYoutube: true, canUpload: false }), "files"), "none");
  // anonymous visitors (the room view's upload box) and banned or timed-out people: no drop target
  assert.equal(D.queueExternalDrop(room("open", { canYoutube: true, canUpload: true, identified: false }), "link"),
    "none");
  assert.equal(D.queueExternalDrop(room("open", { canYoutube: true, canUpload: true,
    restriction: { kind: "timeout" } }), "files"), "none");
  // the alone room and vote/owner modes request the same way (the server decides queued or suggested)
  assert.equal(D.queueExternalDrop(room("vote", { canYoutube: true }, true), "link"), "link");
});

test("which dropped files the queue takes", () => {
  assert.equal(D.isMediaFile({ name: "a.mp3", type: "audio/mpeg" }), true);
  assert.equal(D.isMediaFile({ name: "a.mp4", type: "video/mp4" }), true);
  assert.equal(D.isMediaFile({ name: "a.flac", type: "" }), true);
  assert.equal(D.isMediaFile({ name: "a.mkv", type: "application/octet-stream" }), true);
  assert.equal(D.isMediaFile({ name: "a.pdf", type: "application/pdf" }), false);
  assert.equal(D.isMediaFile({ name: "a.txt", type: "" }), false);
  assert.equal(D.isMediaFile({ name: "noext", type: "" }), false);
});

test("which dropped files are videos, and the type question", () => {
  assert.equal(D.isVideoFile({ name: "a.mp4", type: "video/mp4" }), true);
  assert.equal(D.isVideoFile({ name: "a.mkv", type: "" }), true);
  assert.equal(D.isVideoFile({ name: "a.MOV", type: "application/octet-stream" }), true);
  assert.equal(D.isVideoFile({ name: "a.mp3", type: "audio/mpeg" }), false);
  assert.equal(D.isVideoFile({ name: "a.flac", type: "" }), false);
  assert.equal(D.isVideoFile({ name: "noext", type: "" }), false);
  assert.deepEqual(D.fileDropQuestion([{ name: "a.mp3", type: "audio/mpeg" }]), { videos: 0, ask: "none" });
  assert.deepEqual(D.fileDropQuestion([{ name: "a.mp3", type: "audio/mpeg" }, { name: "v.mp4", type: "video/mp4" }]),
    { videos: 1, ask: "one", name: "v.mp4" });
  assert.deepEqual(D.fileDropQuestion([{ name: "v.webm", type: "" }, { name: "w.mp4", type: "video/mp4" }]),
    { videos: 2, ask: "several" });
});

test("upload lines", () => {
  assert.equal(D.uploadLine("checking", 0, 100, 0.5), "Checking 50%…");
  assert.equal(D.uploadLine("uploading", 25, 100), "Uploading 25%");
  assert.equal(D.uploadLine("uploading", 0, 0), "Uploading 0%");
  assert.equal(D.uploadLine("processing", 100, 100), "Finishing…");
  assert.equal(D.uploadLine("done", 100, 100), "Uploaded");
});
