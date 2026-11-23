// Unit tests for web/src/listsort.ts (compiled), run with Node's test runner by ListSortJsTest.
// The module path comes in LISTSORT_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.LISTSORT_JS).href);

test("# ascending is the default; a click sorts, a second click flips, another column starts ascending", () => {
  assert.deepEqual(S.DEFAULT_SORT, { column: "num", desc: false });
  let s = S.DEFAULT_SORT;
  s = S.nextSort(s, "title");
  assert.deepEqual(s, { column: "title", desc: false });
  s = S.nextSort(s, "title");
  assert.deepEqual(s, { column: "title", desc: true });
  s = S.nextSort(s, "title");
  assert.deepEqual(s, { column: "title", desc: false });
  s = S.nextSort({ column: "title", desc: true }, "artist");
  assert.deepEqual(s, { column: "artist", desc: false });
  assert.deepEqual(S.nextSort(S.DEFAULT_SORT, "num"), { column: "num", desc: true }, "# flips too");
  assert.deepEqual(S.nextSort({ column: "num", desc: true }, "num"), S.DEFAULT_SORT);
});

test("reordering only on # ascending", () => {
  assert.equal(S.canReorder(S.DEFAULT_SORT), true);
  assert.equal(S.canReorder({ column: "num", desc: true }), false);
  for (const c of ["title", "artist", "album", "added", "duration"]) {
    assert.equal(S.canReorder({ column: c, desc: false }), false, c);
    assert.equal(S.canReorder({ column: c, desc: true }), false, c);
  }
});

test("aria-sort and the arrow go on the sorted heading only", () => {
  const s = { column: "artist", desc: true };
  assert.equal(S.ariaSort(s, ["title", "artist"]), "descending");
  assert.equal(S.ariaSort(s, ["album"]), "none");
  assert.equal(S.ariaSort({ column: "album", desc: false }, ["album"]), "ascending");
  assert.equal(S.ariaSort(S.DEFAULT_SORT, ["num"]), "ascending");
  assert.equal(S.sortArrow(s, "artist"), "▼");
  assert.equal(S.sortArrow({ column: "title", desc: false }, "title"), "▲");
  assert.equal(S.sortArrow(s, "title"), "");
});

test("the API parameters of the paged lists", () => {
  assert.deepEqual(S.sortParams(S.DEFAULT_SORT), {});
  assert.deepEqual(S.sortParams({ column: "num", desc: true }), { order: "desc" });
  assert.deepEqual(S.sortParams({ column: "title", desc: false }), { sort: "title", order: "asc" });
  assert.deepEqual(S.sortParams({ column: "duration", desc: true }), { sort: "duration", order: "desc" });
  assert.deepEqual(S.sortParams({ column: "added", desc: false }), { sort: "added", order: "asc" });
});

test("sortable columns: search results have no date added", () => {
  assert.deepEqual([...S.sortableColumns("songs")], ["num", "title", "artist", "album", "added", "duration"]);
  assert.deepEqual([...S.sortableColumns("playlist")], ["num", "title", "artist", "album", "added", "duration"]);
  assert.deepEqual([...S.sortableColumns("search")], ["num", "title", "artist", "album", "duration"]);
});

test("remembered per list; broken or unknown values are the default", () => {
  assert.equal(S.sortStorageKey("songs"), "tt.listSort.songs");
  assert.equal(S.sortStorageKey("playlist", "p1"), "tt.listSort.playlist:p1");
  assert.equal(S.sortStorageKey("liked", undefined), "tt.listSort.liked");
  assert.equal(S.serializeSort(S.DEFAULT_SORT), null);
  for (const s of [{ column: "title", desc: false }, { column: "num", desc: true }, { column: "duration", desc: true }]) {
    assert.deepEqual(S.parseSort(S.serializeSort(s)), s);
  }
  for (const bad of [null, undefined, "", "title", "title:up", "size:asc", "TITLE:asc", 5]) {
    assert.deepEqual(S.parseSort(bad), S.DEFAULT_SORT, String(bad));
  }
  assert.deepEqual(S.parseSort("added:desc", S.sortableColumns("search")), S.DEFAULT_SORT, "not sortable there");
});

test("a loaded list sorts like the server: case-insensitive, missing last both ways, ties in the own order", () => {
  const rows = [
    { id: "a", title: "banana", artist: "Zed", album: "Alpha", durationMs: 3000, addedAt: 100 },
    { id: "b", title: "Apple", album: "beta", addedAt: 200 },
    { id: "c", title: "cherry", artist: "", album: "Alpha", durationMs: 1000, addedAt: 300 },
    { id: "d", title: "apple", artist: "ABBA", durationMs: 2000, addedAt: 400 },
    { id: "e", title: "Éclair", artist: "abba", album: "Gamma", durationMs: 2000, addedAt: 500 },
  ];
  const ids = (s) => S.sortRows(rows, s, (r) => r).map((r) => r.id).join("");
  assert.equal(ids(S.DEFAULT_SORT), "abcde");
  assert.equal(ids({ column: "num", desc: true }), "edcba");
  assert.equal(ids({ column: "title", desc: false }), "bdace", "apple ties keep the own order");
  assert.equal(ids({ column: "title", desc: true }), "ecabd");
  assert.equal(ids({ column: "artist", desc: false }), "deabc", "missing (none, empty) last");
  assert.equal(ids({ column: "artist", desc: true }), "adebc", "missing last descending too");
  assert.equal(ids({ column: "album", desc: false }), "acbed");
  assert.equal(ids({ column: "duration", desc: false }), "cdeab");
  assert.equal(ids({ column: "duration", desc: true }), "adecb");
  assert.equal(ids({ column: "added", desc: true }), "edcba");
  assert.notEqual(S.sortRows(rows, S.DEFAULT_SORT, (r) => r), rows, "a new array");
  assert.equal(rows[0].id, "a", "the input is unchanged");
});
