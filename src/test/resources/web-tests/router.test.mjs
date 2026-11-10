// Unit tests for web/src/router.ts (compiled), run with Node's test runner by ShellJsTest.
// The module path comes in ROUTER_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const R = await import(pathToFileURL(process.env.ROUTER_JS).href);

const HERE = "https://tt.example/rooms/lounge";
const isLounge = (ref) => ref === "lounge" || ref === "0190abcd-ef01-7000-8000-000000000001";

function click(over = {}) {
  return { button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, defaultPrevented: false,
    target: "", download: false, ...over };
}

test("routes: the pages the shell shows, rooms, and everything else", () => {
  for (const p of ["/", "/index.html", "/rooms/", "/library/", "/playlists/", "/encodes/", "/account/", "/admin/",
    "/items/0190abcd-ef01-7000-8000-000000000001", "/rooms/lounge/manage", "/rooms/lounge/manage/"]) {
    assert.deepEqual(R.routeOf(p), { kind: "page" }, p);
  }
  assert.deepEqual(R.routeOf("/rooms/lounge"), { kind: "room", ref: "lounge" });
  assert.deepEqual(R.routeOf("/rooms/lounge/"), { kind: "room", ref: "lounge" });
  assert.deepEqual(R.routeOf("/rooms/caf%C3%A9"), { kind: "room", ref: "café" });
  for (const p of ["/login.html", "/register.html", "/password.html", "/setup.html", "/test/ws-auto.html",
    "/api/rooms", "/media/x/audio.m4a", "/r/lounge", "/rooms/%E0%A4%A", "/library", "/items/", "/js/room.js",
    "/rooms/a/b/c"]) {
    assert.deepEqual(R.routeOf(p), { kind: "other" }, p);
  }
});

test("site pages are shown in place; the playing room shows its view again", () => {
  for (const href of ["/library/", "/playlists/", "/playlists/?id=x", "/account/", "/admin/#roles", "/",
    "/rooms/", "/items/abc", "/rooms/lounge/manage", "/rooms/other/manage", "https://tt.example/library/"]) {
    assert.equal(R.decide(href, HERE, isLounge, click()), "page", href);
    assert.equal(R.decide(href, HERE, isLounge), "page", href + " (script)");
  }
  assert.equal(R.decide("/rooms/lounge", "https://tt.example/library/", isLounge, click()), "room");
  assert.equal(R.decide("/rooms/0190abcd-ef01-7000-8000-000000000001/", "https://tt.example/library/", isLounge),
    "room");
  // Relative links resolve against the shown page.
  assert.equal(R.decide("../library/", "https://tt.example/playlists/", isLounge, click()), "page");
  assert.equal(R.decide("?id=y", "https://tt.example/playlists/?id=x", isLounge, click()), "page");
});

test("another room, auth pages, the API, downloads and other sites go to the browser", () => {
  for (const href of ["/rooms/other", "/rooms/other?mode=remote", "/r/lounge", "/login.html?next=%2F",
    "/register.html", "/password.html", "/setup.html", "/api/me", "/media/a/audio.m4a", "/test/ws-auto.html",
    "https://elsewhere.example/library/", "http://tt.example/library/", "https://tt.example:8443/library/",
    "mailto:a@b.example", "javascript:void(0)", "data:text/html,x"]) {
    assert.equal(R.decide(href, HERE, isLounge, click()), "browser", href);
  }
});

test("clicks meant for a new tab or window, or already handled, go to the browser", () => {
  const to = "/library/";
  assert.equal(R.decide(to, HERE, isLounge, click({ ctrlKey: true })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ metaKey: true })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ shiftKey: true })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ altKey: true })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ button: 1 })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ defaultPrevented: true })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ target: "_blank" })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ target: "other" })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ download: true })), "browser");
  assert.equal(R.decide(to, HERE, isLounge, click({ target: "_self" })), "page");
  assert.equal(R.decide(to, HERE, isLounge, click({ target: " _SELF " })), "page");
});

test("a fragment on the same page is left to the browser; a bad URL too", () => {
  assert.equal(R.decide("#members", "https://tt.example/rooms/lounge/manage", isLounge, click()), "hash");
  assert.equal(R.decide("/admin/#roles", "https://tt.example/admin/#users", isLounge, click()), "hash");
  // The same page without a fragment: shown again.
  assert.equal(R.decide("/library/", "https://tt.example/library/", isLounge, click()), "page");
  assert.equal(R.decide("http://[bad", HERE, isLounge, click()), "browser");
  assert.equal(R.decide("/library/", "not a url", isLounge, click()), "browser");
});

test("pageKey drops the fragment", () => {
  assert.equal(R.pageKey("https://tt.example/admin/?a=1#roles"), "/admin/?a=1");
  assert.equal(R.pageKey("nope"), "nope");
});
