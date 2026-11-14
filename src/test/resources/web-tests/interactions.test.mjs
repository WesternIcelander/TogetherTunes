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
