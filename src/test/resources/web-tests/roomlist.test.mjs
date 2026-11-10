// Unit tests for web/src/roomlist.ts (compiled), run with Node's test runner by RoomsPageJsTest.
// The module path comes in ROOMLIST_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const R = await import(pathToFileURL(process.env.ROOMLIST_JS).href);

test("labels: known values get names, unknown ones are shown as they are", () => {
  assert.equal(R.visibilityLabel("public"), "Public");
  assert.equal(R.visibilityLabel("unlisted"), "Unlisted");
  assert.equal(R.visibilityLabel("private"), "Private");
  assert.equal(R.visibilityLabel("secret"), "secret");
  assert.equal(R.controlModeLabel("owner"), "Owner picks");
  assert.equal(R.controlModeLabel("vote"), "Vote");
  assert.equal(R.controlModeLabel("open"), "Open");
  assert.equal(R.controlModeLabel("chaos"), "chaos");
  assert.equal(R.roleLabel("owner"), "Owner");
  assert.equal(R.roleLabel("moderator"), "Moderator");
  assert.equal(R.roleLabel("member"), "Member");
  assert.equal(R.roleLabel(undefined), "");
});

test("room page path: /rooms/{slug}, encoded", () => {
  assert.equal(R.roomPagePath({ slug: "lounge" }), "/rooms/lounge");
  assert.equal(R.roomPagePath({ slug: "a/b?c" }), "/rooms/a%2Fb%3Fc");
});

test("slug hints follow the server's rules; blank is fine (derived from the name)", () => {
  assert.equal(R.slugProblem(""), null);
  assert.equal(R.slugProblem("   "), null);
  assert.equal(R.slugProblem("lounge"), null);
  assert.equal(R.slugProblem(" Lounge-2 "), null); // trimmed and lower-cased like the server
  assert.equal(R.slugProblem("abc"), null);
  assert.equal(R.slugProblem("a".repeat(40)), null);
  assert.notEqual(R.slugProblem("ab"), null);
  assert.notEqual(R.slugProblem("a".repeat(41)), null);
  assert.notEqual(R.slugProblem("-room"), null);
  assert.notEqual(R.slugProblem("room-"), null);
  assert.notEqual(R.slugProblem("my room"), null);
  assert.notEqual(R.slugProblem("röom"), null);
  assert.notEqual(R.slugProblem("my_room"), null);
  assert.notEqual(R.slugProblem("0190ABCD-ef01-7000-8000-000000000001"), null);
});

test("create body: name trimmed, slug normalised and left out when blank", () => {
  assert.deepEqual(R.createBody({ name: "  Lounge ", slug: "", visibility: "public", controlMode: "vote" }),
    { name: "Lounge", visibility: "public", controlMode: "vote" });
  assert.deepEqual(R.createBody({ name: "Lounge", slug: " My-Room ", visibility: "unlisted", controlMode: "owner" }),
    { name: "Lounge", slug: "my-room", visibility: "unlisted", controlMode: "owner" });
  assert.equal("slug" in R.createBody({ name: "x", slug: "   ", visibility: "private", controlMode: "open" }), false);
});

test("create errors map to their field, others are form-wide", () => {
  assert.equal(R.fieldForError("invalid_name"), "name");
  assert.equal(R.fieldForError("name_too_long"), "name");
  assert.equal(R.fieldForError("invalid_slug"), "slug");
  assert.equal(R.fieldForError("slug_taken"), "slug");
  assert.equal(R.fieldForError("invalid_visibility"), "visibility");
  assert.equal(R.fieldForError("invalid_control_mode"), "controlMode");
  assert.equal(R.fieldForError("too_many_rooms"), null);
  assert.equal(R.fieldForError("forbidden"), null);
  assert.equal(R.fieldForError("network_error"), null);
});

test("only users with rooms.create get the form", () => {
  assert.equal(R.canCreateRooms({ kind: "user", permissions: ["rooms.create"] }), true);
  assert.equal(R.canCreateRooms({ kind: "user", permissions: ["library.upload"] }), false);
  assert.equal(R.canCreateRooms({ kind: "guest" }), false);
  assert.equal(R.canCreateRooms({ kind: "anonymous" }), false);
  assert.equal(R.canCreateRooms({ kind: "guest", permissions: ["rooms.create"] }), false);
});
