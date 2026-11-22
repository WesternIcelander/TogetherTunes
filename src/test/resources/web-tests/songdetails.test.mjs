// Unit tests for web/src/songdetails.ts (compiled), run with Node's test runner by SongDetailsJsTest.
// The module path comes in SONGDETAILS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.SONGDETAILS_JS).href);

const input = (over = {}) => ({
  albumArtist: "", track: "", trackTotal: "", disc: "", discTotal: "", isrc: "", date: "", genre: "",
  copyright: "", label: "", barcode: "", ...over,
});

test("the Artists field starts with the list, else the artist as one name (never split on commas)", () => {
  assert.deepEqual(S.initialArtists({ artist: "A, B", artists: ["A", "B"] }), ["A", "B"]);
  assert.deepEqual(S.initialArtists({ artist: "Earth, Wind & Fire" }), ["Earth, Wind & Fire"]);
  assert.deepEqual(S.initialArtists({}), []);
  const item = { artist: "A feat. B", artists: ["A", "B"] };
  const names = S.initialArtists(item);
  names.push("C");
  assert.deepEqual(item.artists, ["A", "B"], "a copy");
});

test("list editing: add, remove, move within bounds", () => {
  assert.deepEqual(S.addName(["A"]), ["A", ""]);
  assert.deepEqual(S.removeName(["A", "B", "C"], 1), ["A", "C"]);
  assert.deepEqual(S.moveName(["A", "B", "C"], 2, -1), ["A", "C", "B"]);
  assert.deepEqual(S.moveName(["A", "B", "C"], 0, 1), ["B", "A", "C"]);
  assert.deepEqual(S.moveName(["A", "B"], 0, -1), ["A", "B"], "already first");
  assert.deepEqual(S.moveName(["A", "B"], 1, 5), ["A", "B"], "already last");
  assert.deepEqual(S.moveName(["A", "B", "C"], 0, 5), ["B", "C", "A"], "clamped to the end");
});

test("names are cleaned, blanks and repeats dropped; joined with ', '", () => {
  assert.deepEqual(S.cleanNames([" A ", "", "  ", "B\t\nC", "A"]), ["A", "B C"]);
  assert.equal(S.joinArtists(["Neko Hacker", " Rika "]), "Neko Hacker, Rika");
  assert.equal(S.joinArtists([]), "");
});

test("artists go in the body only when the names were edited", () => {
  const featured = { artist: "A feat. B", artists: ["A", "B"] };
  let r = S.detailsBody(featured, ["A", "B "], input());
  assert.ok("body" in r);
  assert.equal("artists" in r.body, false, "unchanged: the item keeps 'A feat. B'");
  r = S.detailsBody(featured, ["B", "A"], input());
  assert.deepEqual(r.body.artists, ["B", "A"], "reordered");
  r = S.detailsBody({ artist: "Solo" }, ["Solo", "Guest"], input());
  assert.deepEqual(r.body.artists, ["Solo", "Guest"], "added");
  r = S.detailsBody({ artist: "Solo" }, [], input());
  assert.deepEqual(r.body.artists, [], "all removed clears the artist");
  r = S.detailsBody({}, [""], input());
  assert.equal("artists" in r.body, false, "an empty new row is nothing");
  assert.equal("artist" in r.body, false, "never a separate artist field");
});

test("blank fields clear (null); values are cleaned and normalised", () => {
  const r = S.detailsBody({}, [], input({
    albumArtist: "  The  Band ", track: "01", trackTotal: "12", disc: " 1 ", discTotal: "", isrc: "jp-vi0-22-01530",
    date: "2022-06-29", genre: "Pop", copyright: "℗ 2010 X", label: "", barcode: "4573556 731873",
  }));
  assert.deepEqual(r.body, {
    albumArtist: "The Band", genre: "Pop", copyright: "℗ 2010 X", label: null,
    track: 1, trackTotal: 12, disc: 1, discTotal: null, isrc: "JPVI02201530", date: "2022-06-29",
    barcode: "4573556731873",
  });
});

test("validation: one message per field with a problem", () => {
  const r = S.detailsBody({}, [], input({
    track: "0", trackTotal: "1.5", disc: "x", discTotal: "10000", isrc: "ABC", date: "2023-02-29", barcode: "1234567",
    genre: "g".repeat(301),
  }));
  assert.ok("errors" in r);
  assert.deepEqual(Object.keys(r.errors).sort(),
    ["barcode", "date", "disc", "discTotal", "genre", "isrc", "track", "trackTotal"]);
  assert.match(r.errors.track, /1 to 9999/);
  const many = S.detailsBody({}, Array.from({ length: 51 }, (_, i) => "N" + i), input());
  assert.ok("errors" in many && many.errors.artists !== undefined);
  const long = S.detailsBody({}, ["x".repeat(200), "y".repeat(200)], input());
  assert.ok("errors" in long && /together/.test(long.errors.artists));
});

test("dates: YYYY, YYYY-MM, YYYY-MM-DD and real days only", () => {
  for (const ok of ["2022", "2022-06", "2022-06-29", "2024-02-29", "2000-02-29"]) assert.ok(S.validDate(ok), ok);
  for (const bad of ["22", "2022-6", "2022-13", "2022-00", "2023-02-29", "1900-02-29", "2022-04-31", "0000",
    "2022-06-29T00:00", "June 2022"]) assert.equal(S.validDate(bad), false, bad);
});

test("ISRC and barcode", () => {
  assert.equal(S.normalizeIsrc("USS1Z9900001"), "USS1Z9900001");
  assert.equal(S.normalizeIsrc("us-s1z-99-00001"), "USS1Z9900001");
  assert.equal(S.normalizeIsrc("US-S1Z-99-0000"), undefined);
  assert.equal(S.normalizeIsrc("1SS1Z9900001"), undefined, "starts with a country code");
  assert.equal(S.normalizeBarcode("012345678905"), "012345678905");
  assert.equal(S.normalizeBarcode("1234567"), undefined);
  assert.equal(S.normalizeBarcode("123456789012345"), undefined);
});

test("the form starts with the item's values", () => {
  assert.deepEqual(S.detailsInput({ track: 1, trackTotal: 2, isrc: "JPVI02201530" }),
    input({ track: "1", trackTotal: "2", isrc: "JPVI02201530" }));
});

test("display rows: known fields only, track and disc as 'n of total'", () => {
  assert.deepEqual(S.detailRows({}), []);
  assert.deepEqual(S.detailRows({
    artist: "Wonderful★opportunity!, Kagamine Rin & Kagamine Len",
    artists: ["Wonderful★opportunity!", "Kagamine Rin & Kagamine Len"], albumArtist: "Wonderful★opportunity!",
    track: 1, trackTotal: 2, disc: 1, discTotal: 2, isrc: "JPVI02201530", date: "2022-06-29", genre: "Vocaloid",
    copyright: "Crypton Future Media, INC. www.piapro.net", label: "FlyingDog", barcode: "4573556731873",
  }), [
    ["Album artist", "Wonderful★opportunity!"], ["Track", "1 of 2"], ["Disc", "1 of 2"], ["Released", "2022-06-29"],
    ["Genre", "Vocaloid"], ["Label", "FlyingDog"], ["Copyright", "Crypton Future Media, INC. www.piapro.net"],
    ["ISRC", "JPVI02201530"], ["Barcode", "4573556731873"],
  ], "the artist line already shows the joined names");
  assert.deepEqual(S.detailRows({ artist: "A feat. B", artists: ["A", "B"], track: 3 }),
    [["Artists", "A, B"], ["Track", "3"]]);
  assert.deepEqual(S.detailRows({ trackTotal: 9, discTotal: 2 }), [["Tracks", "9"], ["Discs", "2"]]);
});

test("server errors map to their field", () => {
  assert.equal(S.fieldOfError("invalid_track_total"), "trackTotal");
  assert.equal(S.fieldOfError("invalid_album_artist"), "albumArtist");
  assert.equal(S.fieldOfError("invalid_artists"), "artists");
  assert.equal(S.fieldOfError("invalid_title"), undefined);
  assert.equal(S.fieldOfError("metadata_too_long"), undefined);
});
