// Unit tests for web/src/customcards.ts (compiled), run with Node's test runner by CustomCardsJsTest.
// The module path comes in CUSTOM_CARDS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const C = await import(pathToFileURL(process.env.CUSTOM_CARDS_JS).href);

test("paths", () => {
  assert.equal(C.serverCardsPath(), "/api/admin/cards");
  assert.equal(C.roomCardsPath("a/b"), "/api/rooms/a%2Fb/cards");
  assert.equal(C.screenPath("/api/admin/cards", "idle"), "/api/admin/cards/idle");
});

test("the spec follows the output", () => {
  assert.match(C.specText({ profile: "1080p30", width: 1920, height: 1080 }), /16:9, at least 1920×1080 \(the 1080p30 output\)/);
  assert.match(C.specText({ profile: "720p30", width: 1280, height: 720 }), /no text drawn/);
});

test("what a screen shows", () => {
  const image = { width: 1920, height: 1080, format: "png", size: 1, uploadedAt: 1, url: "/x" };
  assert.equal(C.screenStatus({ screen: "idle", label: "Queue is empty", image, warnings: [] }, false),
    "Custom image, 1920×1080 PNG");
  assert.equal(C.screenStatus({ screen: "idle", label: "Queue is empty", image, warnings: [] }, true),
    "Room's image, 1920×1080 PNG");
  assert.equal(C.screenStatus({ screen: "idle", label: "x", server: { width: 1280, height: 720 }, warnings: [] }, true),
    "Server's image, 1280×720");
  assert.equal(C.screenStatus({ screen: "idle", label: "x", server: { width: 1280, height: 720 }, warnings: [] }, false),
    "Built-in card");
  assert.equal(C.screenStatus({ screen: "gap", label: "x", warnings: [] }, true), "Built-in card");
});

test("a room whose images don't apply says so", () => {
  const output = { profile: "720p30", width: 1280, height: 720 };
  const withImage = [{ screen: "idle", label: "x", image: { width: 1, height: 1, format: "png", size: 1, uploadedAt: 1, url: "" }, warnings: [] }];
  assert.equal(C.roomNote({ output, screens: withImage, applies: true }), undefined);
  assert.equal(C.roomNote({ output, screens: [{ screen: "idle", label: "x", warnings: [] }], applies: false }), undefined);
  assert.match(C.roomNote({ output, screens: withImage, applies: false }), /doesn't allow/);
});

test("the file check", () => {
  assert.equal(C.checkFile({ size: 10, type: "image/png", name: "a.png" }), null);
  assert.equal(C.checkFile({ size: 10, type: "image/jpeg", name: "a.jpg" }), null);
  assert.equal(C.checkFile({ size: 10, type: "", name: "A.JPEG" }), null);
  assert.equal(C.checkFile({ size: 10, type: "image/gif", name: "a.gif" }), "Pick a PNG or JPEG image.");
  assert.equal(C.checkFile({ size: C.MAX_CARD_BYTES + 1, type: "image/png", name: "a.png" }), "The image is larger than 20 MB.");
  assert.equal(C.checkFile({ size: 0, type: "image/png", name: "a.png" }), "The file is empty.");
});
