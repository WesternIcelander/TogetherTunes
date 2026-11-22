// Unit tests for web/src/interactions.ts (compiled), run with Node's test runner by InteractionsJsTest.
// The module paths come in INTERACTIONS_JS and MOBILE_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const I = await import(pathToFileURL(process.env.INTERACTIONS_JS).href);
const M = await import(pathToFileURL(process.env.MOBILE_JS).href);

const none = { shiftKey: false, ctrlKey: false, metaKey: false };
const ctrl = { ...none, ctrlKey: true };
const cmd = { ...none, metaKey: true };
const shift = { ...none, shiftKey: true };

test("double-click requests in a room, follows the setting alone", () => {
  assert.equal(I.doubleClickAction(false, "play"), "request");
  assert.equal(I.doubleClickAction(false, "queue"), "request");
  assert.equal(I.doubleClickAction(true, "play"), "playNow");
  assert.equal(I.doubleClickAction(true, "queue"), "queue");
  // the default (no setting, or one this client doesn't know) is Play immediately
  assert.equal(I.doubleClickAction(true, undefined), "playNow");
  assert.equal(I.doubleClickAction(true, "later"), "playNow");
});

test("play now goes before the first manually added entry", () => {
  assert.equal(I.playNowBefore([]), undefined);
  assert.equal(I.playNowBefore([{ entryId: "a", section: "autoplay" }]), undefined);
  assert.equal(I.playNowBefore([{ entryId: "m1", section: "manual" }, { entryId: "m2", section: "manual" }]), "m1");
  // an older server without sections: every entry is manual
  assert.equal(I.playNowBefore([{ entryId: "x" }]), "x");
  assert.equal(I.playNowBefore([{ entryId: "a", section: "autoplay" }, { entryId: "m", section: "manual" }]), "m");
});

test("playlist clicks: plain opens, Ctrl or Cmd toggles while a room is open", () => {
  assert.equal(I.playlistClick(none, true), "open");
  assert.equal(I.playlistClick(ctrl, true), "toggle");
  assert.equal(I.playlistClick(cmd, true), "toggle");
  assert.equal(I.playlistClick(ctrl, false), "open");
  assert.equal(I.playlistClick(shift, true), "open");
});

test("playlist menu: autoplay choices with a room, rename and delete for own playlists", () => {
  assert.deepEqual(I.playlistMenu({ roomOpen: true, selected: false, builtin: false }),
    ["use", "only", "open", "rename", "delete"]);
  assert.deepEqual(I.playlistMenu({ roomOpen: true, selected: true, builtin: false }),
    ["stopUsing", "only", "open", "rename", "delete"]);
  assert.deepEqual(I.playlistMenu({ roomOpen: false, selected: false, builtin: false }), ["open", "rename", "delete"]);
  // Liked Songs can be used for autoplay but never renamed or deleted
  assert.deepEqual(I.playlistMenu({ roomOpen: true, selected: false, builtin: true }), ["use", "only", "open"]);
  assert.equal(I.PLAYLIST_ACTION_LABELS.only, "Use only this one for autoplay");
  assert.equal(I.PLAYLIST_ACTION_LABELS.rename, "Rename…");
});

test("playlist names typed in a prompt", () => {
  assert.equal(I.playlistNameInput(null), undefined);
  assert.equal(I.playlistNameInput("   "), undefined);
  assert.equal(I.playlistNameInput("  Mix "), "Mix");
  assert.equal(I.playlistNameInput("Mix", "Mix"), undefined);
  assert.equal(I.playlistNameInput(" Mix 2", "Mix"), "Mix 2");
});

test("desktop multi-select: click, Ctrl/Cmd-click, Shift-click", () => {
  const rows = ["1", "2", "3", "4", "5"];
  const sel = new M.Selection();
  let anchor = I.clickSelect(sel, rows, "2", none, undefined);
  assert.deepEqual(sel.inOrder(rows), ["2"]);
  assert.equal(anchor, "2");
  anchor = I.clickSelect(sel, rows, "4", ctrl, anchor);
  assert.deepEqual(sel.inOrder(rows), ["2", "4"]);
  assert.equal(anchor, "4");
  anchor = I.clickSelect(sel, rows, "4", cmd, anchor); // toggles off again
  assert.deepEqual(sel.inOrder(rows), ["2"]);
  // Shift: the range from the anchor, replacing the selection
  anchor = I.clickSelect(sel, rows, "1", none, anchor);
  anchor = I.clickSelect(sel, rows, "3", shift, anchor);
  assert.deepEqual(sel.inOrder(rows), ["1", "2", "3"]);
  assert.equal(anchor, "1");
  // backwards, and Shift+Ctrl adds the range to what is selected
  anchor = I.clickSelect(sel, rows, "5", ctrl, anchor);
  anchor = I.clickSelect(sel, rows, "4", { ...shift, ctrlKey: true }, anchor);
  assert.deepEqual(sel.inOrder(rows), ["1", "2", "3", "4", "5"]);
  // a plain click selects only that row
  I.clickSelect(sel, rows, "3", none, anchor);
  assert.deepEqual(sel.inOrder(rows), ["3"]);
  // Shift without an anchor (or one no longer shown) acts as a plain click
  const sel2 = new M.Selection();
  assert.equal(I.clickSelect(sel2, rows, "2", shift, undefined), "2");
  assert.equal(I.clickSelect(sel2, rows, "4", shift, "gone"), "4");
  assert.deepEqual(sel2.inOrder(rows), ["4"]);
  assert.equal(I.selectionText(1), "1 song selected");
  assert.equal(I.selectionText(3), "3 songs selected");
});

test("song menu by list and room", () => {
  assert.deepEqual(I.songMenu({ room: true, alone: false, list: "playlist" }),
    ["request", "open", "copyLink", "removeFromPlaylist", "select"]);
  assert.deepEqual(I.songMenu({ room: true, alone: true, list: "library" }),
    ["playNow", "queue", "open", "copyLink", "removeFromLibrary", "select"]);
  assert.deepEqual(I.songMenu({ room: false, alone: false, list: "liked" }), ["open", "copyLink", "unlike", "select"]);
  assert.deepEqual(I.songMenu({ room: false, alone: false, list: "search" }), ["open", "copyLink", "select"]);
  assert.deepEqual(I.songMenu({ room: false, alone: false, list: "othersPlaylist" }), ["open", "copyLink", "select"]);
  assert.equal(I.SONG_ACTION_LABELS.request, "Add to Queue");
});

const mvLink = (id, title = "Video " + id) => ({ type: "music_video", item: { id, title, status: "ready", mediaType: "music_video" } });
const lvLink = (id) => ({ type: "lyric_video", item: { id, title: "Lyrics " + id, status: "ready", mediaType: "music_video" } });
const extLink = (url, label) => (label === undefined ? { type: "external", url } : { type: "external", url, label });
const labels = (entries) => entries.map((e) => e.label);

test("song menu with linked media in a room: the video entries after the song's own add", () => {
  const user = { signedIn: true, youtube: true };
  const links = [mvLink("a"), extLink("https://www.shop.example/x"), mvLink("b"), lvLink("c")];
  const entries = I.songMenuEntries({ room: true, alone: false, list: "library", ...user, links });
  assert.deepEqual(labels(entries), ["Add to Queue", "Add Music Video to Queue", "Open shop.example",
    "Add Music Video 2 to Queue", "Add Lyric Video to Queue", "Open", "Copy link", "Remove from library…", "Select"]);
  assert.deepEqual(entries[0], { kind: "song", action: "request", label: "Add to Queue" });
  assert.deepEqual(entries[1], { kind: "request", itemId: "a", label: "Add Music Video to Queue" });
  assert.deepEqual(entries[3], { kind: "request", itemId: "b", label: "Add Music Video 2 to Queue" });
  assert.deepEqual(entries[5], { kind: "song", action: "open", label: "Open" });
});

test("song menu with linked media alone: Play … now and Add … to Queue, numbered", () => {
  const entries = I.songMenuEntries({ room: true, alone: true, list: "search", signedIn: true, youtube: true,
    links: [lvLink("c"), lvLink("d")] });
  assert.deepEqual(labels(entries), ["Play now", "Add to Queue", "Play Lyric Video now", "Add Lyric Video to Queue",
    "Play Lyric Video 2 now", "Add Lyric Video 2 to Queue", "Open", "Copy link", "Select"]);
  assert.deepEqual(entries.slice(2, 4).map((e) => [e.kind, e.itemId]), [["playNow", "c"], ["queue", "c"]]);
});

test("song menu: Find a music video… without a linked video, signed-in users with YouTube imports only", () => {
  const find = "Find a music video…";
  const base = { room: true, alone: false, list: "playlist", links: [] };
  assert.deepEqual(labels(I.songMenuEntries({ ...base, signedIn: true, youtube: true })),
    ["Add to Queue", find, "Open", "Copy link", "Remove from playlist", "Select"]);
  // an external link isn't a video: the search is still offered
  assert.deepEqual(labels(I.songMenuEntries({ ...base, links: [extLink("https://a.example/", "Story")], signedIn: true,
    youtube: true })), ["Add to Queue", "Open Story", find, "Open", "Copy link", "Remove from playlist", "Select"]);
  // a guest (or anonymous visitor) and a user without library.youtube: no search
  assert.ok(!labels(I.songMenuEntries({ ...base, signedIn: false, youtube: false })).includes(find));
  assert.ok(!labels(I.songMenuEntries({ ...base, signedIn: true, youtube: false })).includes(find));
  // a linked video: no search; guests still get the linked video
  assert.deepEqual(labels(I.songMenuEntries({ ...base, links: [mvLink("a")], signedIn: false, youtube: false })),
    ["Add to Queue", "Add Music Video to Queue", "Open", "Copy link", "Remove from playlist", "Select"]);
});

test("song menu without a room: no video entries, external links and the search stay", () => {
  const links = [mvLink("a"), extLink("https://a.example/")];
  assert.deepEqual(labels(I.songMenuEntries({ room: false, alone: false, list: "liked", signedIn: true, youtube: true,
    links })), ["Open a.example", "Open", "Copy link", "Remove from Liked Songs", "Select"]);
  assert.deepEqual(labels(I.songMenuEntries({ room: false, alone: false, list: "liked", signedIn: true, youtube: true,
    links: [] })), ["Find a music video…", "Open", "Copy link", "Remove from Liked Songs", "Select"]);
});

test("the item page's menu: only the adds and the linked media", () => {
  assert.deepEqual(I.songMenu({ room: true, alone: false, list: "item" }), ["request"]);
  assert.deepEqual(I.songMenu({ room: false, alone: false, list: "item" }), []);
  assert.deepEqual(labels(I.songMenuEntries({ room: true, alone: true, list: "item", signedIn: false, youtube: false,
    links: [mvLink("a")] })), ["Play now", "Add to Queue", "Play Music Video now", "Add Music Video to Queue"]);
  assert.deepEqual(I.songMenuEntries({ room: false, alone: false, list: "item", signedIn: false, youtube: false,
    links: [] }), [], "a guest without a room: nothing");
});

const you = (over = {}) => ({ identified: true, canVote: true, canRequest: true, canYoutube: false, canSkip: false,
  canModerate: false, canRequestLibrary: true, ...over });
const room = (controlMode, y) => ({ room: { controlMode, you: y, settings: {} }, queue: [], suggestions: [] });
const me = { kind: "user", id: "u1" };
const other = { kind: "user", id: "u2" };
const entry = (over = {}) => ({ entryId: "e1", status: "ready", section: "manual", addedBy: other, pinned: false,
  yourVote: 0, item: { source: "upload" }, ...over });

test("queue entry menu: vote, pin for moderators in vote mode, request, remove, open", () => {
  // a listener in vote mode: vote, open
  assert.deepEqual(I.entryMenu(room("vote", you()), entry(), me, false), ["voteUp", "open"]);
  assert.deepEqual(I.entryMenu(room("vote", you()), entry({ yourVote: 1 }), me, false), ["unvote", "open"]);
  // a moderator: Pin to top / Unpin and remove
  assert.deepEqual(I.entryMenu(room("vote", you({ canModerate: true })), entry(), me, false),
    ["voteUp", "pin", "remove", "open"]);
  assert.deepEqual(I.entryMenu(room("vote", you({ canModerate: true })), entry({ pinned: true }), me, false),
    ["voteUp", "unpin", "remove", "open"]);
  // no pin outside vote mode, nor on autoplay entries (they get "request it")
  assert.deepEqual(I.entryMenu(room("open", you({ canModerate: true })), entry(), me, false), ["remove", "open"]);
  assert.deepEqual(I.entryMenu(room("vote", you({ canModerate: true })), entry({ section: "autoplay" }), me, false),
    ["request", "remove", "open"]);
  // the one who added it may remove it
  assert.deepEqual(I.entryMenu(room("open", you()), entry({ addedBy: me }), me, false), ["remove", "open"]);
  // read-only (banned or timed out): only Open
  const banned = you({ canModerate: true, restriction: { kind: "ban" } });
  assert.ok(I.entryMenu(room("vote", banned), entry(), me, false).every((a) => a !== "pin" && a !== "voteUp"));
  // suggestions
  assert.deepEqual(I.entryMenu(room("owner", you({ canModerate: true })), entry(), me, true), ["accept", "reject", "open"]);
  assert.deepEqual(I.entryMenu(room("owner", you()), entry({ addedBy: me }), me, true), ["withdraw", "open"]);
  assert.deepEqual(I.entryMenu(room("owner", you()), entry(), me, true), ["open"]);
  assert.equal(I.ENTRY_ACTION_LABELS.pin, "Pin to top");
});

test("entry actions map to room socket messages", () => {
  assert.deepEqual(I.entryMessage("pin", "e"), { type: "queue.pin", entryId: "e", pinned: true });
  assert.deepEqual(I.entryMessage("unpin", "e"), { type: "queue.pin", entryId: "e", pinned: false });
  assert.deepEqual(I.entryMessage("voteUp", "e"), { type: "vote", entryId: "e", value: 1 });
  assert.deepEqual(I.entryMessage("unvote", "e"), { type: "vote", entryId: "e", value: 0 });
  assert.deepEqual(I.entryMessage("request", "e"), { type: "queue.request", entryId: "e" });
  assert.deepEqual(I.entryMessage("remove", "e"), { type: "queue.remove", entryId: "e" });
  assert.deepEqual(I.entryMessage("withdraw", "e"), { type: "queue.remove", entryId: "e" });
  assert.deepEqual(I.entryMessage("accept", "e"), { type: "suggest.accept", entryId: "e" });
  assert.deepEqual(I.entryMessage("reject", "e"), { type: "suggest.reject", entryId: "e" });
  assert.deepEqual(I.entryMessage("retry", "e"), { type: "queue.retry", entryId: "e" });
  assert.deepEqual(I.entryMessage("skip", "e"), { type: "next" });
  assert.equal(I.entryMessage("open", "e"), undefined);
});
