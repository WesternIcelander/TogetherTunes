// Unit tests for web/src/ytdlp.ts and web/src/audit.ts (compiled), run with Node's test runner by
// AdminToolsJsTest. Module paths come in YTDLP_JS and AUDIT_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const Y = await import(pathToFileURL(process.env.YTDLP_JS).href);
const A = await import(pathToFileURL(process.env.AUDIT_JS).href);

test("yt-dlp box: version line and the update's outcome", () => {
  assert.match(Y.versionText({ available: false }), /isn't installed/);
  assert.equal(Y.versionText({ available: true, version: "2026.03.17" }), "yt-dlp version 2026.03.17.");
  assert.match(Y.versionText({ available: true }), /couldn't be read/);
  assert.equal(Y.updateText(undefined), undefined);
  const base = { startedAt: 1, log: "" };
  assert.equal(Y.updateText({ ...base, state: "running" }), "Updating…");
  assert.equal(Y.updating({ available: true, update: { ...base, state: "running" } }), true);
  assert.equal(Y.updating({ available: true, update: { ...base, state: "failed" } }), false);
  assert.equal(Y.updating({ available: true }), false);
  assert.match(Y.updateText({ ...base, state: "failed", exitCode: 1 }), /failed \(exit code 1\)/);
  assert.match(Y.updateText({ ...base, state: "failed" }), /^The update failed\./);
  assert.equal(Y.updateText({ ...base, state: "succeeded", versionBefore: "a", versionAfter: "b" }), "Updated from a to b.");
  assert.equal(Y.updateText({ ...base, state: "succeeded", versionBefore: "a", versionAfter: "a" }), "Already up to date (a).");
  assert.equal(Y.updateText({ ...base, state: "succeeded" }), "The update finished.");
});

test("audit log: query path, details and time", () => {
  assert.equal(A.auditPath({ action: "", actor: "" }), "/api/admin/audit?limit=50");
  assert.equal(A.auditPath({ action: " auth.login ", actor: "u 1" }, "c&1", 20),
    "/api/admin/audit?action=auth.login&actor=u%201&cursor=c%261&limit=20");
  assert.equal(A.detailsText(undefined), "");
  assert.equal(A.detailsText(null), "");
  assert.equal(A.detailsText({ itemId: "x", kind: "youtube", n: 2, gone: null, list: ["a"] }),
    "itemId: x, kind: youtube, n: 2, list: [\"a\"]");
  assert.equal(A.detailsText("plain"), "\"plain\"");
  assert.equal(A.detailsText([1, 2]), "[1,2]");
  assert.match(A.formatTime(Date.UTC(2026, 10, 11, 12, 0, 5)), /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:05$/);
  assert.ok(A.AUDIT_ACTIONS.includes("admin.ytdlp_update"));
  assert.ok(A.AUDIT_ACTIONS.includes("auth.login"));
});
