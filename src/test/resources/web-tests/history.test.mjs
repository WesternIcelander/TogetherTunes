// Unit tests for web/src/history.ts (compiled), run with Node's test runner by RoomManageJsTest.
// The module path comes in HISTORY_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const H = await import(pathToFileURL(process.env.HISTORY_JS).href);

const item = (over = {}) => ({ id: "i1", kind: "audio", title: "Song", source: "upload", status: "ready",
  presentAsAudio: false, ...over });
const row = (over = {}) => ({ entryId: "e1", state: "played", playedAt: 1000, item: item(), recentlyPlayed: false,
  ...over });
const user = { user: true, moderates: false, canRequestLibrary: true };
const guest = { user: false, moderates: false, canRequestLibrary: true };
const mod = { user: true, moderates: true, canRequestLibrary: true };
const noRequests = { user: true, moderates: false, canRequestLibrary: false };

test("Add to Library: signed-in users only, In your library when it is there", () => {
  assert.equal(H.libraryAction(row({ inYourLibrary: false }), user), "add");
  assert.equal(H.libraryAction(row({ inYourLibrary: true }), user), "in-library");
  assert.equal(H.libraryAction(row(), guest), "none");
  // a purged YouTube item is fetched again; a purged or failed upload can't be restored
  assert.equal(H.libraryAction(row({ inYourLibrary: false, item: item({ status: "purged", source: "youtube",
    youtubeId: "abc" }) }), user), "add");
  assert.equal(H.libraryAction(row({ inYourLibrary: false, item: item({ status: "purged" }) }), user), "none");
  assert.equal(H.libraryAction(row({ inYourLibrary: false, item: item({ status: "failed" }) }), user), "none");
});

test("Add to Queue: owner and moderators on every row, others by the request rules", () => {
  const recent = row({ recentlyPlayed: true });
  assert.deepEqual(H.queueAction(recent, mod, 10), { shown: true, disabled: false });
  assert.deepEqual(H.queueAction(row(), user, 10), { shown: true, disabled: false });
  assert.deepEqual(H.queueAction(recent, user, 10),
    { shown: true, disabled: true, reason: "Played within the last 10 songs" });
  assert.deepEqual(H.queueAction(recent, guest, 1),
    { shown: true, disabled: true, reason: "Played within the last 1 song" });
  // window off: the flag doesn't matter
  assert.deepEqual(H.queueAction(recent, user, 0), { shown: true, disabled: false });
  assert.equal(H.queueAction(row(), noRequests, 10).shown, false);
  assert.equal(H.queueAction(row(), { ...noRequests, moderates: true }, 10).shown, true);
  // an expired upload can't play any more
  const gone = H.queueAction(row({ item: item({ status: "purged" }) }), mod, 10);
  assert.equal(gone.disabled, true);
});

test("via words", () => {
  assert.equal(H.viaText("upload"), "by upload");
  assert.equal(H.viaText("youtube"), "by YouTube link");
  assert.equal(H.viaText("history"), "from the history");
  assert.equal(H.viaText(undefined), "");
  assert.equal(H.viaText("other"), "other");
});

test("a fresh first page is merged in: new rows first, flags updated, older pages kept", () => {
  const shown = [row({ entryId: "e3", playedAt: 300 }), row({ entryId: "e2", playedAt: 200 }),
    row({ entryId: "e1", playedAt: 100 })];
  const page = [row({ entryId: "e4", playedAt: 400 }), row({ entryId: "e3", playedAt: 300, recentlyPlayed: true })];
  const merged = H.mergeFirstPage(shown, page);
  assert.deepEqual(merged.map((r) => r.entryId), ["e4", "e3", "e2", "e1"]);
  assert.equal(merged[1].recentlyPlayed, true);
  // a shown row newer than the page's oldest that the page lacks is gone (replayed by "previous")
  const again = H.mergeFirstPage(merged, [row({ entryId: "e5", playedAt: 500 }), row({ entryId: "e3", playedAt: 300 })]);
  assert.deepEqual(again.map((r) => r.entryId), ["e5", "e3", "e2", "e1"]);
});

test("markInLibrary marks every row of the item", () => {
  const rows = [row({ entryId: "a", inYourLibrary: false }), row({ entryId: "b", inYourLibrary: false }),
    row({ entryId: "c", inYourLibrary: false, item: item({ id: "i2" }) })];
  H.markInLibrary(rows, "i1");
  assert.deepEqual(rows.map((r) => r.inYourLibrary), [true, true, false]);
});
