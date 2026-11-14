// Unit tests for web/src/panels.ts (compiled), run with Node's test runner by PanelsJsTest.
// The module path comes in PANELS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const P = await import(pathToFileURL(process.env.PANELS_JS).href);

const you = (over = {}) => ({ identified: true, canVote: true, canRequest: true, canYoutube: true, canSkip: false,
  canModerate: false, canRequestLibrary: true, ...over });
const entry = (id, over = {}) => ({ entryId: id, status: "ready", section: "manual", item: { source: "youtube" },
  ...over });
const state = (over = {}, youOver = {}) => ({ room: { controlMode: "vote", you: you(youOver), settings: {} },
  queue: [], suggestions: [], ...over });
const ME = { kind: "user", id: "u1" };

test("the queue splits into Requests and From Playlist, in order", () => {
  const q = [entry("a"), entry("b", { section: "autoplay" }), entry("c", { section: undefined }),
    entry("d", { section: "autoplay" })];
  const { requests, fromPlaylist } = P.queueSections(q);
  assert.deepEqual(requests.map((e) => e.entryId), ["a", "c"]);
  assert.deepEqual(fromPlaylist.map((e) => e.entryId), ["b", "d"]);
});

test("suggestions go above the queue for people who may edit it, below for the rest", () => {
  assert.equal(P.suggestionsPlace(state()), "none");
  const s = state({ suggestions: [entry("s")] });
  assert.equal(P.suggestionsPlace(s), "below");
  s.room.you.canModerate = true;
  assert.equal(P.suggestionsPlace(s), "above");
});

test("entry buttons follow the rights", () => {
  // vote mode: requests get the vote button; autoplay entries ↑ (request) instead
  let s = state();
  let b = P.entryButtons(s, entry("a"), ME);
  assert.deepEqual(b, { vote: true, canVote: true, request: false, remove: false, retry: false });
  b = P.entryButtons(s, entry("p", { section: "autoplay" }), ME);
  assert.equal(b.vote, false);
  assert.equal(b.request, true);
  // the adder (for autoplay: whose playlist it came from) may remove
  b = P.entryButtons(s, entry("p", { section: "autoplay", addedBy: { kind: "user", id: "u1" } }), ME);
  assert.equal(b.remove, true);
  // moderators remove anything; open mode lets requesters remove
  assert.equal(P.entryButtons(state({}, { canModerate: true }), entry("a"), ME).remove, true);
  assert.equal(P.entryButtons({ ...s, room: { ...s.room, controlMode: "open" } }, entry("a"), ME).remove, true);
  assert.equal(P.entryButtons({ ...s, room: { ...s.room, controlMode: "owner" } }, entry("a"), ME).vote, false);
  // no library request right: no ↑ on From Playlist
  assert.equal(P.entryButtons(state({}, { canRequestLibrary: false }), entry("p", { section: "autoplay" }), ME).request,
    false);
  // read-only (banned / timed out): nothing but looking
  s = state({}, { restriction: { kind: "timeout", until: 5 }, canModerate: true });
  b = P.entryButtons(s, entry("p", { section: "autoplay" }), ME);
  assert.deepEqual([b.canVote, b.request, b.remove], [false, false, false]);
  // retry: a failed YouTube entry, for moderators, or its requester who may still add links
  const failed = entry("f", { status: "failed", addedBy: { kind: "user", id: "u1" } });
  assert.equal(P.entryButtons(state(), failed, ME).retry, true);
  assert.equal(P.entryButtons(state({}, { canYoutube: false }), failed, ME).retry, false);
  assert.equal(P.entryButtons(state(), { ...failed, item: { source: "upload" } }, ME).retry, false);
  assert.equal(P.entryButtons(state(), failed, undefined).retry, false);
});

test("player bar: controls for people who may control the room, else mute + heart", () => {
  assert.equal(P.playerControls(undefined), "listener");
  assert.equal(P.playerControls(you()), "listener");
  assert.equal(P.playerControls(you({ canSkip: true })), "controls");
  assert.equal(P.playerControls(you({ canSkip: true, restriction: { kind: "ban" } })), "listener");
  assert.equal(P.canHeart("user"), true);
  assert.equal(P.canHeart("guest"), false);
  assert.equal(P.canHeart("anonymous"), false);
  assert.equal(P.canSkipNow(you({ canSkip: true })), true);
  assert.equal(P.canSkipNow(you()), false);
});

test("vote to skip", () => {
  const s = state({ skipVotes: { votes: 1, needed: 3 } });
  assert.deepEqual(P.voteSkip(s, true), { show: true, enabled: true, label: "Vote to skip (1/3)" });
  assert.equal(P.voteSkip(s, false).show, false);
  assert.equal(P.voteSkip({ ...s, room: { ...s.room, controlMode: "owner" } }, true).show, false);
  assert.equal(P.voteSkip(state({ skipVotes: { votes: 0, needed: 1 } }, { canVote: false }), true).enabled, false);
  assert.equal(P.voteSkip(state(), true).show, false);
});

test("mute until end of song unmutes when another entry (or nothing) is current", () => {
  const m = new P.MuteState();
  assert.equal(m.current("e1"), false);
  m.muteUntilEndOf(undefined); // nothing playing: nothing to mute
  assert.equal(m.muted, false);
  m.muteUntilEndOf("e1");
  assert.equal(m.untilEndOfSong, true);
  assert.equal(m.current("e1"), true);
  assert.equal(m.current("e2"), false);
  m.muteUntilEndOf("e2");
  assert.equal(m.current(undefined), false);
  m.mute();
  assert.equal(m.current("e3"), true, "a plain mute stays");
  assert.equal(m.untilEndOfSong, false);
  m.toggle();
  assert.equal(m.muted, false);
  m.toggle();
  assert.equal(m.muted, true);
  m.unmute();
  assert.equal(m.muted, false);
});

test("volume", () => {
  assert.equal(P.volumeGain(100), 1);
  assert.equal(P.volumeGain(50), 0.25);
  assert.equal(P.volumeGain(0), 0);
  assert.equal(P.volumeGain(150), 1);
  assert.equal(P.volumeGain(NaN), 1);
  assert.equal(P.parseVolume(null), 100);
  assert.equal(P.parseVolume(""), 100);
  assert.equal(P.parseVolume("abc"), 100);
  assert.equal(P.parseVolume("42.4"), 42);
  assert.equal(P.parseVolume("-3"), 0);
  assert.equal(P.parseVolume("300"), 100);
});

test("playlist header text", () => {
  assert.equal(P.formatTotal(4 * 3600_000 + 20 * 60_000), "4h 20m");
  assert.equal(P.formatTotal(23 * 60_000 + 10_000), "23m");
  assert.equal(P.formatTotal(0), "0m");
  assert.equal(P.formatTotal(20_000), "20s");
  assert.equal(P.formatTotal(59_600), "1m");
  assert.equal(P.formatTotal(NaN), "0m");
  assert.equal(P.totalDuration([{ durationMs: 1000 }, {}, { durationMs: -5 }, { durationMs: 2000 }]), 3000);
  assert.equal(P.songCount(1), "1 song");
  assert.equal(P.songCount(69), "69 songs");
});

test("autoplay selections: my dots and others' playlists", () => {
  const sel = [
    { user: { kind: "user", id: "u1", name: "Me" }, playlists: [{ id: "p1", name: "Gaming", entryCount: 3 }] },
    { user: { kind: "user", id: "u2", name: "Leif" }, playlists: [{ id: "p2", name: "Lofi", entryCount: 1 },
      { id: "p3", name: "K-Pop", entryCount: 2 }] },
  ];
  assert.deepEqual([...P.mySelectedPlaylists(sel, "u1")], ["p1"]);
  assert.deepEqual(P.othersAutoplay(sel, "u1").map((o) => [o.owner, o.name]), [["Leif", "Lofi"], ["Leif", "K-Pop"]]);
  assert.equal(P.othersAutoplay(sel, undefined).length, 3);
});

test("links, search path, shuffle, people", () => {
  assert.deepEqual(P.LIBRARY_LINKS.map((l) => l.label), ["All My Songs", "Movies/Shows", "Liked Songs"]);
  assert.equal(P.searchPath("  a b "), "/search?q=a%20b");
  const seq = [0.9, 0.1, 0.5];
  let i = 0;
  const out = P.shuffled([1, 2, 3, 4], () => seq[i++ % seq.length]);
  assert.deepEqual([...out].sort(), [1, 2, 3, 4]);
  assert.equal(P.personModeText({ actor: {}, mode: "remote" }), "Requests-only");
  assert.equal(P.personModeText({ actor: {}, mode: "listen" }), "Listening");
  assert.equal(P.presenceText({ listening: 3, remote: 5, anonymous: 0 }), "3 listening · 5 requests-only");
  assert.equal(P.presenceText({ listening: 1, remote: 0, anonymous: 2 }), "1 listening · 0 requests-only · +2 anonymous");
});
