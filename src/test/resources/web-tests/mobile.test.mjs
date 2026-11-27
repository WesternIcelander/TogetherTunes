// Unit tests for web/src/mobile.ts (compiled), run with Node's test runner by MobileJsTest.
// The module path comes in MOBILE_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const M = await import(pathToFileURL(process.env.MOBILE_JS).href);

const at = (view, over = {}) => ({ pane: "", view, ...over });

test("the layout switches below 800 px", () => {
  assert.equal(M.MOBILE_MAX_WIDTH, 800);
  assert.equal(M.MOBILE_QUERY, "(max-width: 799.98px)");
});

test("five bottom tabs in the drawing's order", () => {
  assert.deepEqual(M.MOBILE_TABS.map((t) => t.label), ["Library", "Now Playing", "Room", "Search", "Account"]);
});

test("each page belongs to a tab", () => {
  assert.equal(M.mobileTab(at("list", { list: "songs" })), "library");
  assert.equal(M.mobileTab(at("list", { list: "playlist" })), "library");
  assert.equal(M.mobileTab(at("list", { list: "search" })), "search");
  assert.equal(M.mobileTab(at("item")), "library");
  assert.equal(M.mobileTab(at("home")), "library");
  assert.equal(M.mobileTab(at("room")), "room");
  assert.equal(M.mobileTab(at("rooms")), "room");
  assert.equal(M.mobileTab(at("roomManage")), "room");
  for (const v of ["account", "admin", "encodes", "library", "playlists"]) assert.equal(M.mobileTab(at(v)), "account", v);
  // a pane wins over the view underneath it
  assert.equal(M.mobileTab(at("room", { pane: "now" })), "now");
  assert.equal(M.mobileTab(at("list", { pane: "rooms", list: "songs" })), "room");
  assert.equal(M.mobileTab(at("room", { pane: "library" })), "library");
  assert.equal(M.mobileTab(at("list", { pane: "account" })), "account");
});

test("the back button: lists to the library, the room view to the rooms, account pages to the account menu", () => {
  assert.deepEqual(M.mobileBack(at("list", { list: "playlist" })), { label: "Library", pane: "library" });
  assert.equal(M.mobileBack(at("list", { list: "search" })), undefined);
  assert.deepEqual(M.mobileBack(at("room")), { label: "Rooms", pane: "rooms" });
  assert.deepEqual(M.mobileBack(at("admin")), { label: "Account", pane: "account" });
  assert.deepEqual(M.mobileBack(at("item")), { label: "Back", history: true });
  assert.deepEqual(M.mobileBack(at("roomManage")), { label: "Back", history: true });
  // a tab's first screen (a pane) has none
  for (const pane of ["library", "now", "rooms", "account"]) assert.equal(M.mobileBack(at("list", { pane })), undefined);
});

test("tapping a tab", () => {
  const place = at("list", { list: "songs" });
  assert.deepEqual(M.tabAction("library", place, undefined), { pane: "library" });
  // Now Playing: the room's Now Playing View URL (the pane on a phone); the pane itself without a room
  assert.deepEqual(M.tabAction("now", place, "/rooms/a", "/rooms/a/now-playing"), { path: "/rooms/a/now-playing" });
  assert.deepEqual(M.tabAction("now", place, undefined, undefined), { pane: "now" });
  assert.deepEqual(M.tabAction("search", place, undefined), { path: "/search" });
  assert.deepEqual(M.tabAction("account", place, undefined), { pane: "account" });
  // Room: This Room when in a room, the room list without one or from the room view itself
  assert.deepEqual(M.tabAction("room", place, "/rooms/a"), { path: "/rooms/a" });
  assert.deepEqual(M.tabAction("room", place, undefined), { pane: "rooms" });
  assert.deepEqual(M.tabAction("room", at("room"), "/rooms/a"), { pane: "rooms" });
  assert.deepEqual(M.tabAction("room", at("room", { pane: "now" }), "/rooms/a"), { path: "/rooms/a" });
});

test("the Room tab's sub-tabs: Rooms | This Room", () => {
  assert.equal(M.roomSubTab(at("room")), "this");
  assert.equal(M.roomSubTab(at("roomManage")), "this");
  assert.equal(M.roomSubTab(at("rooms")), "rooms");
  assert.equal(M.roomSubTab(at("room", { pane: "rooms" })), "rooms");
  assert.equal(M.roomSubTab(at("list", { list: "songs" })), undefined);
  assert.equal(M.roomSubTab(at("room", { pane: "now" })), undefined);
});

test("pane names from history.state", () => {
  for (const p of ["", "library", "now", "rooms", "account"]) assert.equal(M.isPane(p), true, p);
  for (const p of [undefined, null, "queue", 1, {}]) assert.equal(M.isPane(p), false, String(p));
  assert.equal(M.PANE_TITLES.now, "Now Playing");
});

test("a long press allows a little movement, not a scroll", () => {
  assert.equal(M.LONG_PRESS_MS, 500);
  assert.equal(M.movedTooFar(3, 4), false);
  assert.equal(M.movedTooFar(6, 8), false);
  assert.equal(M.movedTooFar(8, 8), true);
  assert.equal(M.movedTooFar(0, -11), true);
});

test("Select mode: toggles, and gives the selection in the list's order", () => {
  const s = new M.Selection();
  assert.equal(s.size, 0);
  assert.equal(s.toggle("3"), true);
  assert.equal(s.toggle("1"), true);
  assert.equal(s.toggle("2"), true);
  assert.equal(s.toggle("2"), false);
  assert.equal(s.size, 2);
  assert.equal(s.has("1"), true);
  assert.equal(s.has("2"), false);
  assert.deepEqual(s.inOrder(["1", "2", "3", "4"]), ["1", "3"]);
  assert.deepEqual(s.inOrder(["3"]), ["3"]);
  s.clear();
  assert.equal(s.size, 0);
  assert.equal(M.selectedText(0), "Tap songs to select them");
  assert.equal(M.selectedText(2), "2 selected");
});

test("the top bar's title shows once the large title is under the bar", () => {
  assert.equal(M.showBarTitle(120, 44), false);
  assert.equal(M.showBarTitle(44, 44), true);
  assert.equal(M.showBarTitle(-10, 44), true);
  assert.equal(M.showBarTitle(undefined, 44), true);
});

test("the pane shown: an opened pane wins, then Now Playing on the Now Playing View URL; none on the desktop", () => {
  assert.equal(M.shownPane(true, "", true, true), "now");
  assert.equal(M.shownPane(true, "", false, true), "");
  assert.equal(M.shownPane(true, "library", true, true), "library");
  assert.equal(M.shownPane(true, "account", false, true), "account");
  // without the tabs (not signed in) the URL shows the room view, where the request parts are
  assert.equal(M.shownPane(true, "", true, false), "");
  // the desktop layout: the same URL is the Now Playing View, no pane
  assert.equal(M.shownPane(false, "", true, true), "");
  assert.equal(M.shownPane(false, "library", false, true), "");
});

test("Now Playing player: a landscape video is full width at its own height, centred in the space above the controls", () => {
  const space = { width: 390, available: 844 - 44 - 56, controls: 190, margin: 24 };
  assert.deepEqual(M.nowPlayerSize(space, true, { width: 1280, height: 720 }), { area: 554, height: 219, artwork: 0 });
  // before the video's size is known: 16:9
  assert.deepEqual(M.nowPlayerSize(space, true, undefined), { area: 554, height: 219, artwork: 0 });
  assert.deepEqual(M.nowPlayerSize(space, true, { width: 0, height: 0 }), { area: 554, height: 219, artwork: 0 });
});

test("Now Playing player: a portrait or tall video fills the space above the controls, no more", () => {
  const space = { width: 390, available: 744, controls: 190, margin: 24 };
  // 9:16 at full width would be 693 px; the controls need 190 of 744
  assert.deepEqual(M.nowPlayerSize(space, true, { width: 720, height: 1280 }), { area: 554, height: 554, artwork: 0 });
  // square: 390 fits
  assert.deepEqual(M.nowPlayerSize(space, true, { width: 500, height: 500 }), { area: 554, height: 390, artwork: 0 });
  // a phone in landscape: the controls keep their place, the video gets what is left
  const landscape = { width: 780, available: 360 - 44 - 56, controls: 140, margin: 16 };
  assert.deepEqual(M.nowPlayerSize(landscape, true, { width: 1280, height: 720 }), { area: 120, height: 120, artwork: 0 });
  assert.deepEqual(M.nowPlayerSize(landscape, true, { width: 720, height: 1280 }), { area: 120, height: 120, artwork: 0 });
  // no space at all: nothing, never negative
  assert.deepEqual(M.nowPlayerSize({ ...space, available: 100 }, true, undefined), { area: 0, height: 0, artwork: 0 });
});

test("Now Playing player: the artwork is a square with margins, smaller when the window is short", () => {
  const space = { width: 390, available: 744, controls: 190, margin: 24 };
  // 390 - 2 × 24 = 342 wide; 554 - 48 = 506 high
  assert.deepEqual(M.nowPlayerSize(space, false), { area: 554, height: 0, artwork: 342 });
  // a phone in landscape: the height decides (260 - 140 = 120 - 2 × 16 = 88)
  assert.deepEqual(M.nowPlayerSize({ width: 780, available: 260, controls: 140, margin: 16 }, false),
    { area: 120, height: 0, artwork: 88 });
  assert.deepEqual(M.nowPlayerSize({ width: 780, available: 600, controls: 190, margin: 24 }, false),
    { area: 410, height: 0, artwork: 362 });
  assert.deepEqual(M.nowPlayerSize({ width: 390, available: 150, controls: 190, margin: 24 }, false),
    { area: 0, height: 0, artwork: 0 });
});

test("the mini bar's controls are kept out of its Now Playing tap target", () => {
  const parts = M.MINI_BAR_CONTROLS.split(",").map((x) => x.trim());
  for (const p of ["button", "input", "label", ".menu", "[role=menu]"]) assert.ok(parts.includes(p), p);
  // The title link is part of the target (it is the keyboard way in), so links aren't excluded.
  assert.ok(!parts.some((p) => p === "a" || p.startsWith("a[")));
});
