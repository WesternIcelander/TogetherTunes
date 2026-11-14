// Unit tests for web/src/shows.ts (compiled), run with Node's test runner by ShowsJsTest.
// The module path comes in SHOWS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.SHOWS_JS).href);

const ep = (id, season, episode) => (season === undefined ? { id, episode } : { id, season, episode });

test("ordering across seasons: next in the season, else the next season's lowest", () => {
  const all = [ep("s2e1", 2, 1), ep("s1e2", 1, 2), ep("s1e1", 1, 1), ep("s0e1", 0, 1)];
  assert.equal(S.nextEpisode(all, { season: 1, episode: 1 }).id, "s1e2");
  assert.equal(S.nextEpisode(all, { season: 1, episode: 2 }).id, "s2e1");
  assert.equal(S.nextEpisode(all, { season: 0, episode: 1 }).id, "s1e1");
  const sorted = all.slice().sort(S.compareEpisodes).map((e) => e.id);
  assert.deepEqual(sorted, ["s0e1", "s1e1", "s1e2", "s2e1"]);
});

test("gaps are skipped", () => {
  const all = [ep("s1e1", 1, 1), ep("s1e4", 1, 4), ep("s3e2", 3, 2)];
  assert.equal(S.nextEpisode(all, { season: 1, episode: 1 }).id, "s1e4");
  assert.equal(S.nextEpisode(all, { season: 1, episode: 4 }).id, "s3e2");
  assert.equal(S.nextEpisode(all, { season: 2, episode: 9 }).id, "s3e2");
});

test("missing season numbers come before every season", () => {
  const all = [ep("e1", undefined, 1), ep("e3", undefined, 3), ep("s0e5", 0, 5)];
  assert.equal(S.nextEpisode(all, { episode: 1 }).id, "e3");
  assert.equal(S.nextEpisode(all, { episode: 3 }).id, "s0e5");
  assert.equal(S.seasonLabel(undefined), "No season");
  assert.equal(S.seasonLabel(0), "Specials");
  assert.equal(S.seasonLabel(3), "Season 3");
});

test("nothing next", () => {
  const all = [ep("s1e1", 1, 1), ep("s1e2", 1, 2)];
  assert.equal(S.nextEpisode(all, { season: 1, episode: 2 }), undefined);
  assert.equal(S.nextEpisode([], { season: 1, episode: 1 }), undefined);
  assert.equal(S.nextEpisode([ep("dup", 1, 2)], { season: 1, episode: 2 }), undefined);
});

test("show keys and when a room offers Next episode", () => {
  assert.equal(S.showKey("  The SHOW "), "the show");
  assert.equal(S.offersNextEpisode(true, { mediaType: "show" }), true);
  assert.equal(S.offersNextEpisode(true, undefined), true);
  assert.equal(S.offersNextEpisode(true, { mediaType: "movie" }), false);
  assert.equal(S.offersNextEpisode(false, { mediaType: "show" }), false);
});
