// Unit tests for web/src/links.ts (compiled), run with Node's test runner by LinksJsTest.
// The module path comes in LINKS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const L = await import(pathToFileURL(process.env.LINKS_JS).href);

const target = (id, title = "T " + id) => ({ id, title, status: "ready", mediaType: "music_video" });
const mv = (id) => ({ type: "music_video", item: target(id) });
const lv = (id) => ({ type: "lyric_video", item: target(id) });
const ext = (url, label) => (label === undefined ? { type: "external", url } : { type: "external", url, label });

test("type labels", () => {
  assert.deepEqual(L.LINK_TYPES.map(L.linkTypeLabel), ["Music Video", "Lyric Video", "External link"]);
});

test("names are numbered from the second one of a type", () => {
  assert.deepEqual(L.linkNames([mv("a")]), ["Music Video"]);
  assert.deepEqual(L.linkNames([mv("a"), lv("b"), mv("c"), ext("https://x.example/"), mv("d")]),
    ["Music Video", "Lyric Video", "Music Video 2", "External link", "Music Video 3"]);
  assert.deepEqual(L.linkNames([]), []);
});

test("external links show their label, else the host", () => {
  assert.equal(L.externalText(ext("https://www.Example.com/a/b?c", "  ")), "example.com");
  assert.equal(L.externalText(ext("https://user@shop.example:8443/x")), "shop.example");
  assert.equal(L.externalText(ext("https://a.example/", "The story")), "The story");
  assert.equal(L.hostOf("not a url"), "not a url");
});

test("external URLs: http(s) with a host only", () => {
  assert.equal(L.externalUrl("  https://a.example/x?y=1  "), "https://a.example/x?y=1");
  assert.equal(L.externalUrl("HTTP://a.example"), "HTTP://a.example");
  for (const bad of ["", "ftp://a.example/", "javascript:alert(1)", "https://", "a.example", "https://a.example/ x",
    "https://user:pw@a.example/", "https://:80/", "https://a.example/" + "x".repeat(2000)]) {
    assert.equal(L.externalUrl(bad), undefined, bad);
  }
});

test("labels are cleaned to one line", () => {
  assert.equal(L.cleanLabel("  a \n\t b  "), "a b");
});

test("list editing: move within bounds, remove", () => {
  assert.deepEqual(L.moveLink(["a", "b", "c"], 2, -1), ["a", "c", "b"]);
  assert.deepEqual(L.moveLink(["a", "b"], 0, -1), ["a", "b"]);
  assert.deepEqual(L.moveLink(["a", "b"], 1, 1), ["a", "b"]);
  assert.deepEqual(L.removeLink(["a", "b", "c"], 1), ["a", "c"]);
});

test("what can be added: count, self link, duplicates, URL and label", () => {
  assert.equal(L.addProblem("song", [], mv("v")), undefined);
  assert.match(L.addProblem("song", [], mv("song")), /itself/);
  assert.match(L.addProblem("song", [mv("v")], lv("v")), /already linked/);
  assert.match(L.addProblem("song", [], { type: "music_video" }), /Choose/);
  assert.equal(L.addProblem("song", [], ext("https://a.example/", "Shop")), undefined);
  assert.match(L.addProblem("song", [ext("https://a.example/")], ext(" https://a.example/ ")), /already in the list/);
  assert.match(L.addProblem("song", [], ext("a.example")), /http/);
  assert.match(L.addProblem("song", [], ext("https://a.example/", "x".repeat(101))), /100/);
  assert.equal(L.addProblem("song", [], ext("https://a.example/", "ü".repeat(100))), undefined);
  const full = Array.from({ length: 20 }, (_, i) => ext("https://a.example/" + i));
  assert.match(L.addProblem("song", full, mv("v")), /at most 20/);
  assert.equal(L.addProblem("song", full.slice(1), mv("v")), undefined);
});

test("the save body and change check", () => {
  const list = [mv("v"), ext(" https://a.example/ ", "  A  shop "), ext("https://b.example/", " ")];
  assert.deepEqual(L.linksBody(list), { links: [
    { type: "music_video", itemId: "v" },
    { type: "external", url: "https://a.example/", label: "A shop" },
    { type: "external", url: "https://b.example/" },
  ] });
  assert.ok(L.sameLinks([{ ...mv("v"), id: "l1" }], [mv("v")]), "ids and summaries don't matter");
  assert.ok(!L.sameLinks([mv("v"), lv("w")], [lv("w"), mv("v")]), "order does");
  assert.ok(!L.sameLinks([mv("v")], [lv("v")]), "type does");
});

test("invalid_link reasons read as sentences", () => {
  for (const r of ["bad_type", "self_link", "duplicate", "bad_url", "bad_label", "not_an_item", "too_many", undefined]) {
    assert.match(L.invalidLinkText(r), /\.$/);
  }
  assert.match(L.invalidLinkText("self_link"), /itself/);
});

test("song menu entries in a room: requests of each video, numbered, then Open for external links", () => {
  const room = { alone: false, signedIn: true, youtube: true };
  assert.deepEqual(L.linkMenuEntries([mv("a"), ext("https://www.shop.example/x"), mv("b"), lv("c"),
    ext("https://a.example/", "Story")], room), [
    { kind: "request", itemId: "a", label: "Add Music Video to Queue" },
    { kind: "open", url: "https://www.shop.example/x", label: "Open shop.example" },
    { kind: "request", itemId: "b", label: "Add Music Video 2 to Queue" },
    { kind: "request", itemId: "c", label: "Add Lyric Video to Queue" },
    { kind: "open", url: "https://a.example/", label: "Open Story" },
  ]);
});

test("song menu entries alone: play now and queue", () => {
  assert.deepEqual(L.linkMenuEntries([lv("c")], { alone: true, signedIn: true, youtube: true }), [
    { kind: "playNow", itemId: "c", label: "Play Lyric Video now" },
    { kind: "queue", itemId: "c", label: "Add Lyric Video to Queue" },
  ]);
});

test("Find a music video… only without a linked video, for signed-in users who may import links", () => {
  const find = { kind: "findVideo", label: "Find a music video…" };
  assert.deepEqual(L.linkMenuEntries([], { alone: false, signedIn: true, youtube: true }), [find]);
  assert.deepEqual(L.linkMenuEntries([ext("https://a.example/")], { alone: true, signedIn: true, youtube: true }),
    [{ kind: "open", url: "https://a.example/", label: "Open a.example" }, find]);
  assert.deepEqual(L.linkMenuEntries([], { alone: false, signedIn: false, youtube: true }), [], "guests");
  assert.deepEqual(L.linkMenuEntries([], { alone: false, signedIn: true, youtube: false }), [], "no library.youtube");
  assert.deepEqual(L.linkMenuEntries([mv("a")], { alone: false, signedIn: true, youtube: true }).map((e) => e.kind),
    ["request"], "a linked video: no search");
  assert.deepEqual(L.linkMenuEntries([mv("a")], { alone: false, signedIn: false, youtube: false }).map((e) => e.kind),
    ["request"], "guests still get the linked video");
});
