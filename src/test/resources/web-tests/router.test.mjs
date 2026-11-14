// Unit tests for web/src/router.ts (compiled), run with Node's test runner by ShellJsTest.
// The module path comes in ROUTER_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const R = await import(pathToFileURL(process.env.ROUTER_JS).href);

const HERE = "https://tt.example/rooms/lounge";
const isLounge = (ref) => ref === "lounge" || ref === "0190abcd-ef01-7000-8000-000000000001";
const ID = "0190abcd-ef01-7000-8000-000000000001";

function click(over = {}) {
  return { button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, defaultPrevented: false,
    target: "", download: false, ...over };
}

function view(name, params = {}, path) {
  return { kind: "view", view: name, params, path };
}

test("fixed views, with and without the trailing slash and under the old page names", () => {
  const cases = {
    "/": view("home", {}, "/"), "/index.html": view("home", {}, "/"),
    "/login": view("login", {}, "/login"), "/login.html": view("login", {}, "/login"),
    "/register": view("register", {}, "/register"), "/register.html": view("register", {}, "/register"),
    "/password": view("password", {}, "/password"), "/password.html": view("password", {}, "/password"),
    "/setup": view("setup", {}, "/setup"), "/setup.html": view("setup", {}, "/setup"),
    "/rooms": view("rooms", {}, "/rooms/"), "/rooms/": view("rooms", {}, "/rooms/"),
    "/library": view("library", {}, "/library/"), "/library/": view("library", {}, "/library/"),
    "/playlists": view("playlists", {}, "/playlists/"), "/playlists/": view("playlists", {}, "/playlists/"),
    "/encodes/": view("encodes", {}, "/encodes/"), "/account": view("account", {}, "/account/"),
    "/admin/": view("admin", {}, "/admin/"),
    // the desktop layout's middle lists (one view, the list named in its params)
    "/songs": view("list", { list: "songs" }, "/songs"), "/songs/": view("list", { list: "songs" }, "/songs"),
    "/shows": view("list", { list: "shows" }, "/shows"), "/liked": view("list", { list: "liked" }, "/liked"),
    "/uncategorized": view("list", { list: "uncategorized" }, "/uncategorized"),
    "/search": view("list", { list: "search" }, "/search"), "/search/": view("list", { list: "search" }, "/search"),
  };
  for (const [path, route] of Object.entries(cases)) assert.deepEqual(R.routeOf(path), route, path);
});

test("deep links: rooms, room management, items and playlists", () => {
  assert.deepEqual(R.routeOf("/rooms/lounge"), view("room", { ref: "lounge" }, "/rooms/lounge"));
  assert.deepEqual(R.routeOf("/rooms/lounge/"), view("room", { ref: "lounge" }, "/rooms/lounge"));
  assert.deepEqual(R.routeOf("/rooms/caf%C3%A9"), view("room", { ref: "café" }, "/rooms/caf%C3%A9"));
  assert.deepEqual(R.routeOf("/rooms/lounge/manage"), view("roomManage", { ref: "lounge" }, "/rooms/lounge/manage"));
  assert.deepEqual(R.routeOf("/rooms/lounge/manage/"), view("roomManage", { ref: "lounge" }, "/rooms/lounge/manage"));
  assert.deepEqual(R.routeOf("/items/" + ID), view("item", { id: ID }, "/items/" + ID));
  assert.deepEqual(R.routeOf("/playlists/" + ID), view("list", { list: "playlist", id: ID }, "/playlists/" + ID));
  assert.equal(R.playlistPath(ID), "/playlists/" + ID);
  assert.equal(R.playlistPath("a/b"), "/playlists/a%2Fb");
  assert.deepEqual(R.routeOf(R.playlistPath("a b")), view("list", { list: "playlist", id: "a b" }, "/playlists/a%20b"));
});

test("unknown UI paths are the Not found view; the server's own paths are not views", () => {
  for (const p of ["/items/", "/rooms/a/b/c", "/library/x/y", "/nope", "/rooms/%E0%A4%A", "/room.html",
    "/item.html"]) {
    assert.equal(R.routeOf(p).view, "notFound", p);
    assert.equal(R.isAppPath(p), true, p);
  }
  for (const p of ["/api/rooms", "/api", "/media/x/audio.m4a", "/live/t/index.m3u8", "/auth/oidc/x/start",
    "/r/lounge", "/ws", "/test/ws-auto.html", "/test/", "/js/room.js", "/css/main.css", "/favicon.ico",
    "/rooms/x/cover.jpg", "//library", "/a//b", "/./x", "/../x", "/.hidden", "/a\\b", "relative"]) {
    assert.deepEqual(R.routeOf(p), { kind: "other" }, p);
    assert.equal(R.isAppPath(p), false, p);
  }
});

test("every view has a template, and every view but Not found a module", () => {
  for (const [name, info] of Object.entries(R.VIEWS)) {
    assert.match(info.template, /^view-[a-z-]+$/, name);
    if (name !== "notFound") assert.equal(typeof info.module, "string", name);
  }
  for (const name of ["login", "register", "password", "setup"]) assert.equal(R.VIEWS[name].identity, true, name);
  assert.deepEqual(R.SERVER_PREFIXES, ["api", "media", "live", "auth", "r", "ws", "test", "js", "css"]);
});

test("without a playing room, every view is shown in place", () => {
  for (const href of ["/library/", "/playlists/", "/playlists/x", "/account/", "/admin/#roles", "/", "/rooms/",
    "/items/abc", "/rooms/lounge", "/rooms/other", "/rooms/other?mode=remote", "/rooms/other/manage", "/login?next=%2F",
    "/login.html", "/register", "/password", "/setup", "/nope", "https://tt.example/library/"]) {
    assert.equal(R.decide(href, HERE, undefined, click()), "view", href);
    assert.equal(R.decide(href, "https://tt.example/library/", undefined), "view", href + " (script)");
  }
  // Relative links resolve against the shown view.
  assert.equal(R.decide("../library/", "https://tt.example/playlists/", undefined, click()), "view");
  assert.equal(R.decide("?id=y", "https://tt.example/playlists/?id=x", undefined, click()), "view");
});

test("while a room plays: other views in place, its own view shown again, another room and identity views load", () => {
  for (const href of ["/library/", "/playlists/", "/playlists/?id=x", "/account/", "/admin/#roles", "/", "/rooms/",
    "/items/abc", "/rooms/lounge/manage", "/rooms/other/manage", "/nope", "https://tt.example/library/"]) {
    assert.equal(R.decide(href, HERE, isLounge, click()), "view", href);
    assert.equal(R.decide(href, HERE, isLounge), "view", href + " (script)");
  }
  assert.equal(R.decide("/rooms/lounge", "https://tt.example/library/", isLounge, click()), "room");
  assert.equal(R.decide("/rooms/" + ID + "/", "https://tt.example/library/", isLounge), "room");
  for (const href of ["/rooms/other", "/rooms/other?mode=remote", "/login?next=%2F", "/login.html", "/register",
    "/password.html", "/setup"]) {
    assert.equal(R.decide(href, "https://tt.example/library/", isLounge, click()), "browser", href);
  }
});

test("the server's routes, other sites and odd schemes go to the browser", () => {
  for (const href of ["/r/lounge", "/api/me", "/media/a/audio.m4a", "/test/ws-auto.html", "/js/app.js",
    "/auth/oidc/x/start", "https://elsewhere.example/library/", "http://tt.example/library/",
    "https://tt.example:8443/library/", "mailto:a@b.example", "javascript:void(0)", "data:text/html,x"]) {
    assert.equal(R.decide(href, HERE, isLounge, click()), "browser", href);
    assert.equal(R.decide(href, HERE, undefined, click()), "browser", href);
  }
});

test("clicks meant for a new tab or window, or already handled, go to the browser", () => {
  const to = "/library/";
  for (const playing of [isLounge, undefined]) {
    assert.equal(R.decide(to, HERE, playing, click({ ctrlKey: true })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ metaKey: true })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ shiftKey: true })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ altKey: true })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ button: 1 })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ defaultPrevented: true })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ target: "_blank" })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ target: "other" })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ download: true })), "browser");
    assert.equal(R.decide(to, HERE, playing, click({ target: "_self" })), "view");
    assert.equal(R.decide(to, HERE, playing, click({ target: " _SELF " })), "view");
  }
});

test("a fragment on the same view is left to the browser; a bad URL too", () => {
  assert.equal(R.decide("#members", "https://tt.example/rooms/lounge/manage", isLounge, click()), "hash");
  assert.equal(R.decide("/admin/#roles", "https://tt.example/admin/#users", undefined, click()), "hash");
  // The same view without a fragment: shown again.
  assert.equal(R.decide("/library/", "https://tt.example/library/", undefined, click()), "view");
  assert.equal(R.decide("http://[bad", HERE, isLounge, click()), "browser");
  assert.equal(R.decide("/library/", "not a url", isLounge, click()), "browser");
});

test("pageKey drops the fragment", () => {
  assert.equal(R.pageKey("https://tt.example/admin/?a=1#roles"), "/admin/?a=1");
  assert.equal(R.pageKey("nope"), "nope");
});
