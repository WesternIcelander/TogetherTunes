// Unit tests for web/src/invites.ts (compiled), run with Node's test runner by SettingsJsTest.
// The module path comes in INVITES_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const I = await import(pathToFileURL(process.env.INVITES_JS).href);

test("the create body: hours 1 to 720, an optional one-line note of at most 200 characters", () => {
  assert.deepEqual(I.inviteBody("168", ""), { body: { expiresInHours: 168 } });
  assert.deepEqual(I.inviteBody(24, "  for Bob "), { body: { expiresInHours: 24, note: "for Bob" } });
  assert.ok("error" in I.inviteBody("0", ""));
  assert.ok("error" in I.inviteBody("721", ""));
  assert.ok("error" in I.inviteBody("1.5", ""));
  assert.ok("error" in I.inviteBody("x", ""));
  assert.ok("error" in I.inviteBody(24, "a\nb"));
  assert.ok("error" in I.inviteBody(24, "x".repeat(201)));
  assert.deepEqual(I.inviteBody(24, "x".repeat(200)).body.note.length, 200);
  for (const [hours] of I.EXPIRY_OPTIONS) assert.ok("body" in I.inviteBody(hours, ""));
  assert.ok(I.EXPIRY_OPTIONS.some(([hours]) => hours === I.DEFAULT_EXPIRY_HOURS));
});

test("status text and revoking", () => {
  const base = { id: "i", createdAt: 1, expiresAt: 2 };
  assert.equal(I.inviteStatusText({ ...base, status: "pending" }), "Not used yet");
  assert.equal(I.inviteStatusText({ ...base, status: "used", usedBy: { kind: "user", id: "u", name: "Bob", isGuest: false } }),
    "Used by Bob");
  assert.equal(I.inviteStatusText({ ...base, status: "used" }), "Used");
  assert.equal(I.inviteStatusText({ ...base, status: "revoked" }), "Revoked");
  assert.equal(I.inviteStatusText({ ...base, status: "expired" }), "Expired");
  assert.equal(I.inviteStatusText({ ...base, status: "odd" }), "odd");
  assert.equal(I.canRevoke({ ...base, status: "pending" }), true);
  for (const status of ["used", "revoked", "expired"]) assert.equal(I.canRevoke({ ...base, status }), false);
});
