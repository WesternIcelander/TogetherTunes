// Unit tests for web/src/episodedrop.ts (compiled), run with Node's test runner by EpisodeDropJsTest.
// The module path comes in EPISODEDROP_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const E = await import(pathToFileURL(process.env.EPISODEDROP_JS).href);

test("the episode in a file name: the server's rules (Classify.fromFileName)", () => {
  assert.deepEqual(E.guessEpisode("My.Great_Show.S02E05.1080p.mp4"), { name: "My Great Show", season: 2, episode: 5 });
  assert.deepEqual(E.guessEpisode("Some_Show - s1e12 [720p].mkv"), { name: "Some Show", season: 1, episode: 12 });
  assert.deepEqual(E.guessEpisode("Show S03 E04.webm"), { name: "Show", season: 3, episode: 4 });
  assert.deepEqual(E.guessEpisode("Show.S10.E100"), { name: "Show", season: 10, episode: 100 });
  assert.deepEqual(E.guessEpisode("Show (S01E02).mp4"), { name: "Show", season: 1, episode: 2 });
  assert.deepEqual(E.guessEpisode("S01E02.mp4"), { season: 1, episode: 2 }, "no name before it");
  assert.equal(E.guessEpisode("Movie Night (2020).mkv"), undefined);
  assert.equal(E.guessEpisode("ABS01E02.mp4"), undefined, "part of a longer word");
  assert.equal(E.guessEpisode("S01E023456789"), undefined, "too many digits for an episode");
  assert.deepEqual(E.guessEpisode("Show S01E99999.mp4"), { name: "Show", season: 1, episode: 99999 });
});

test("rows are prefilled per file; the open show fills a missing name", () => {
  const rows = E.episodeRows(["Name S01E01.mp4", "Name S01E02.mp4", "extra.mp4"], "Fallback");
  assert.deepEqual(rows, [
    { fileName: "Name S01E01.mp4", showName: "Name", season: "1", episode: "1" },
    { fileName: "Name S01E02.mp4", showName: "Name", season: "1", episode: "2" },
    { fileName: "extra.mp4", showName: "Fallback", season: "", episode: "" },
  ]);
  assert.deepEqual(E.episodeRows(["x.mp4"]), [{ fileName: "x.mp4", showName: "", season: "", episode: "" }]);
});

test("the show name for all", () => {
  const rows = E.episodeRows(["Name S01E01.mp4", "name s01e02.mp4"]);
  assert.equal(E.commonShowName(rows), "Name");
  assert.equal(E.commonShowName(E.episodeRows(["A S01E01.mp4", "B S01E02.mp4"])), "");
  assert.equal(E.commonShowName(E.episodeRows(["x.mp4", "y.mp4"])), "");
  assert.equal(E.commonShowName([]), "");
  const filled = E.fillShowName(E.episodeRows(["A S01E01.mp4", "b.mp4"]), "The Show");
  assert.deepEqual(filled.map((r) => r.showName), ["The Show", "The Show"]);
  assert.deepEqual(filled.map((r) => r.episode), ["1", ""], "only the names change");
  const kept = E.fillShowName(E.episodeRows(["A S01E01.mp4"]), "  ");
  assert.equal(kept[0].showName, "A", "a blank field wipes nothing");
});

test("what is missing: a show name and an episode per file; the season may be empty", () => {
  assert.deepEqual(E.rowProblems({ fileName: "f", showName: "S", season: "", episode: "3" }), []);
  assert.deepEqual(E.rowProblems({ fileName: "f", showName: " ", season: "", episode: "" }),
    ["the show's name", "an episode number"]);
  assert.deepEqual(E.rowProblems({ fileName: "f", showName: "S", season: "x", episode: "-1" }),
    ["a season number (0 or more, or empty)", "an episode number (0 or more)"]);
  assert.deepEqual(E.rowProblems({ fileName: "f", showName: "S", season: "", episode: "1000001" }),
    ["an episode number (0 or more)"]);
  assert.equal(E.formProblem([{ fileName: "ok.mp4", showName: "S", season: "1", episode: "1" },
    { fileName: "ep2.mp4", showName: "", season: "", episode: "" }]),
  "ep2.mp4 needs the show's name and an episode number.");
  assert.equal(E.formProblem([{ fileName: "e.mp4", showName: "", season: "x", episode: "" }]),
    "e.mp4 needs the show's name, a season number (0 or more, or empty) and an episode number.");
  assert.equal(E.formProblem([]), "");
});

test("the classify bodies, in the files' order", () => {
  const rows = E.episodeRows(["Name S01E01.mp4", "Name S01E02.mp4"]);
  assert.deepEqual(E.episodeBodies(rows), { bodies: [
    { mediaType: "show", show: { name: "Name", season: 1, episode: 1 } },
    { mediaType: "show", show: { name: "Name", season: 1, episode: 2 } },
  ] });
  assert.deepEqual(E.episodeBodies([{ fileName: "e.mp4", showName: " S ", season: "", episode: "0" }]),
    { bodies: [{ mediaType: "show", show: { name: "S", episode: 0 } }] });
  assert.deepEqual(E.episodeBodies([{ fileName: "e.mp4", showName: "S", season: "", episode: "" }]),
    { error: "e.mp4 needs an episode number." });
});
