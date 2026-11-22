// Unit tests for web/src/lookup.ts (compiled), run with Node's test runner by LookupJsTest.
// The module path comes in LOOKUP_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const L = await import(pathToFileURL(process.env.LOOKUP_JS).href);

const found = {
  title: "Good is YEAH! (feat. Kagamine Rin & Kagamine Len)",
  artist: "Wonderful★opportunity!, Kagamine Rin & Kagamine Len",
  artists: ["Wonderful★opportunity!", "Kagamine Rin & Kagamine Len"],
  album: "Good is YEAH! (feat. Kagamine Rin & Kagamine Len)",
  albumArtist: "Wonderful★opportunity!",
  track: 1, trackTotal: 2, isrc: "JPVI02201530", date: "2022-06-29",
  copyright: "Crypton Future Media, INC. www.piapro.net", label: "FlyingDog", barcode: "4573556731873",
};

test("review rows: only found fields, in order; checked where empty, unchecked where different, none where same", () => {
  const item = { title: "Good is YEAH!", artist: "Wonderful★opportunity!", album: found.album, genre: "Pop",
    label: "FlyingDog", track: 3 };
  const rows = L.reviewRows(item, found);
  assert.deepEqual(rows.map((r) => r.field),
    ["title", "artists", "album", "albumArtist", "track", "date", "label", "copyright", "isrc", "barcode"]);
  const by = Object.fromEntries(rows.map((r) => [r.field, r]));
  assert.equal(by.title.checked, false, "differs: unchecked");
  assert.equal(by.title.same, false);
  assert.equal(by.artists.found, "Wonderful★opportunity!, Kagamine Rin & Kagamine Len");
  assert.equal(by.artists.current, "Wonderful★opportunity!");
  assert.equal(by.artists.checked, false);
  assert.equal(by.album.same, true, "the same: no checkbox");
  assert.equal(by.album.checked, false);
  assert.equal(by.label.same, true);
  assert.equal(by.albumArtist.checked, true, "empty: checked");
  assert.equal(by.albumArtist.current, "");
  assert.equal(by.track.found, "1 of 2");
  assert.equal(by.track.current, "3");
  assert.equal(by.track.checked, false);
  assert.equal(by.isrc.checked, true);
  assert.equal(rows.some((r) => r.field === "genre"), false, "not found: no row");
  assert.equal(rows.some((r) => r.field === "disc"), false);
});

test("one Artists row: the list joined, compared as string and list", () => {
  const same = L.reviewRows({ artist: found.artist, artists: found.artists }, found).find((r) => r.field === "artists");
  assert.equal(same.same, true);
  const noList = L.reviewRows({ artist: found.artist }, found).find((r) => r.field === "artists");
  assert.equal(noList.same, false, "the list would be new");
  assert.equal(noList.checked, false, "the item has an artist: unchecked");
  assert.equal(L.reviewRows({}, found).find((r) => r.field === "artists").checked, true);
  assert.equal(L.fieldText({ artist: "A feat. B", artists: ["A", "B"] }, "artists"), "A, B");
});

test("the cover starts checked only when the item has none", () => {
  assert.equal(L.coverChecked(false), true);
  assert.equal(L.coverChecked(true), false);
});

test("Apply sends the checked fields and the token; artists as list and string; numbers with totals", () => {
  assert.equal(L.applyBody(found, [], "t"), null);
  assert.deepEqual(L.applyBody(found, ["artists", "track", "isrc"], "tok"), {
    artists: found.artists, artist: found.artist, track: 1, trackTotal: 2, isrc: "JPVI02201530", lookupToken: "tok",
  });
  assert.deepEqual(L.applyBody({ artist: "Solo" }, ["artists"], "t"), { artist: "Solo", lookupToken: "t" });
  assert.deepEqual(L.applyBody({ disc: 2 }, ["disc"], "t"), { disc: 2, lookupToken: "t" });
  assert.deepEqual(L.applyBody(found, ["title", "album", "date"], "t"),
    { title: found.title, album: found.album, date: "2022-06-29", lookupToken: "t" });
  // a field checked that wasn't found sends nothing for it
  assert.equal(L.applyBody({ title: "T" }, ["genre"], "t"), null);
});

test("album picker: name, then album artist · year · track", () => {
  assert.deepEqual(L.candidateText({ album: "Troubadour", albumArtist: "K'naan", date: "2009-02-24", track: 7,
    trackTotal: 18 }), { name: "Troubadour", detail: "K'naan · 2009 · track 7 of 18" });
  assert.deepEqual(L.candidateText({ album: "EP" }), { name: "EP", detail: "" });
  assert.deepEqual(L.candidateText({ album: "EP", date: "2010", trackTotal: 4 }), { name: "EP", detail: "2010 · track of 4" });
  assert.deepEqual(L.pickBody({ album: "A", albumArtist: "B", track: 1 }), { album: "A", albumArtist: "B" });
  assert.deepEqual(L.pickBody({ album: "A" }), { album: "A" });
  assert.equal(L.yearOf("nope"), "");
  assert.equal(L.yearOf(undefined), "");
});

test("sizes and texts", () => {
  assert.equal(L.sizeText(1600, 1600), "1600 × 1600");
  assert.equal(L.sizeText(undefined, 3), "");
  assert.equal(L.NOTHING_FOUND, "No details found");
});
