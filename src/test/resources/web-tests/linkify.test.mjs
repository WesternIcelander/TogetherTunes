// Unit tests for web/src/linkify.ts (compiled): the admin's login / register messages as text with
// http(s) links (docs/auth.md#login-and-registration-messages). Run with Node's test runner by
// LoginPopupJsTest; the module path comes in LINKIFY_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const L = await import(pathToFileURL(process.env.LINKIFY_JS).href);

const joined = (parts) => parts.map((p) => p.text).join("");
const links = (parts) => parts.filter((p) => p.href !== undefined);

test("plain text stays one text part; empty gives nothing", () => {
  assert.deepEqual(L.linkParts(""), []);
  assert.deepEqual(L.linkParts("Hello\nworld"), [{ text: "Hello\nworld" }]);
});

test("http and https addresses become links, the rest stays text", () => {
  const text = "Welcome!\nInvites: https://example.org/ask?x=1&y=2#top or http://a.example:8080/p\nBye";
  const parts = L.linkParts(text);
  assert.equal(joined(parts), text);
  assert.deepEqual(parts, [
    { text: "Welcome!\nInvites: " },
    { text: "https://example.org/ask?x=1&y=2#top", href: "https://example.org/ask?x=1&y=2#top" },
    { text: " or " },
    { text: "http://a.example:8080/p", href: "http://a.example:8080/p" },
    { text: "\nBye" },
  ]);
  assert.deepEqual(links(L.linkParts("HTTPS://Example.ORG/A")), [{ text: "HTTPS://Example.ORG/A", href: "https://example.org/A" }]);
  assert.deepEqual(L.linkParts("https://x.example"), [{ text: "https://x.example", href: "https://x.example/" }]);
});

test("sentence punctuation and unbalanced brackets end the address", () => {
  assert.deepEqual(links(L.linkParts("See https://x.example/a.")), [{ text: "https://x.example/a", href: "https://x.example/a" }]);
  assert.deepEqual(links(L.linkParts("(see https://x.example/a), ok")),
    [{ text: "https://x.example/a", href: "https://x.example/a" }]);
  assert.deepEqual(links(L.linkParts("https://en.example/wiki/A_(b)!")),
    [{ text: "https://en.example/wiki/A_(b)", href: "https://en.example/wiki/A_(b)" }]);
  assert.equal(joined(L.linkParts("(see https://x.example/a), ok")), "(see https://x.example/a), ok");
});

test("no HTML injection: markup and other schemes stay text", () => {
  const evil = "<script>alert(1)</script> <a href=\"javascript:alert(1)\">x</a> javascript:alert(1) "
    + "data:text/html,<b>x</b> vbscript:x file:///etc/passwd ftp://x.example/";
  assert.deepEqual(L.linkParts(evil), [{ text: evil }]);
  // an address never swallows markup around it, and the markup stays text
  const mixed = "<b>https://x.example/a</b><img src=x onerror=alert(1)>";
  const parts = L.linkParts(mixed);
  assert.equal(joined(parts), mixed);
  assert.deepEqual(links(parts), [{ text: "https://x.example/a", href: "https://x.example/a" }]);
  assert.ok(parts.every((p) => p.href === undefined || /^https?:\/\//.test(p.href)));
  // quotes end an address, so an attribute can't be built from it
  assert.deepEqual(links(L.linkParts("https://x.example/\"onmouseover=alert(1)")),
    [{ text: "https://x.example/", href: "https://x.example/" }]);
});

test("only addresses that start a word, and only valid ones", () => {
  assert.deepEqual(L.linkParts("xhttps://x.example"), [{ text: "xhttps://x.example" }]);
  assert.deepEqual(L.linkParts("javascript://x.example/%0aalert(1)"), [{ text: "javascript://x.example/%0aalert(1)" }]);
  assert.deepEqual(L.linkParts("https:// nothing"), [{ text: "https:// nothing" }]);
  assert.deepEqual(L.linkParts("http://[bad"), [{ text: "http://[bad" }]);
  assert.deepEqual(links(L.linkParts("a-https://x.example")), [{ text: "https://x.example", href: "https://x.example/" }]);
});

test("links open in a new tab without opener or referrer", () => {
  assert.deepEqual({ ...L.LINK_ATTRIBUTES }, { target: "_blank", rel: "noopener noreferrer" });
});
