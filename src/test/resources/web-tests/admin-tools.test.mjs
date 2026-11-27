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

test("yt-dlp box: PO token plugin line and the Test button's result", () => {
  assert.equal(Y.pluginText({ available: false }), undefined);
  assert.equal(Y.pluginText({ available: true }), undefined, "not known");
  assert.equal(Y.pluginText({ available: true, poTokenPlugin: { loaded: false } }), Y.PLUGIN_MISSING);
  assert.match(Y.PLUGIN_MISSING, /no PO token provider plugin installed: see the deployment notes/);
  assert.equal(Y.pluginText({ available: true, poTokenPlugin: { loaded: true, version: "2.0.2" } }),
    "yt-dlp has a PO token provider plugin (version 2.0.2).");
  assert.equal(Y.pluginText({ available: true, poTokenPlugin: { loaded: true } }), "yt-dlp has a PO token provider plugin.");

  const base = { url: "http://pot:4416", defaultUrl: false };
  const okResult = Y.testText({ ...base, reachable: true, httpStatus: 200, version: "2.0.2", poTokenPlugin: { loaded: true } });
  assert.equal(okResult.ok, true);
  assert.equal(okResult.text, "OK. The PO token provider at http://pot:4416 answers (version 2.0.2).");
  const missing = Y.testText({ ...base, reachable: true, poTokenPlugin: { loaded: false } });
  assert.equal(missing.ok, false);
  assert.ok(missing.text.startsWith(Y.PLUGIN_MISSING), missing.text);
  const down = Y.testText({ ...base, reachable: false, error: "the connection was refused (is the server running?)",
    poTokenPlugin: { loaded: true } });
  assert.equal(down.ok, false);
  assert.match(down.text, /can't be reached: the connection was refused/);
  assert.match(down.text, /provider server is running/);
  const both = Y.testText({ ...base, reachable: false, poTokenPlugin: { loaded: false } });
  assert.ok(both.text.startsWith(Y.PLUGIN_MISSING) && /can't be reached: no answer/.test(both.text), both.text);
  const unknown = Y.testText({ ...base, reachable: true });
  assert.equal(unknown.ok, false);
  assert.match(unknown.text, /couldn't be checked/);
  assert.match(Y.testText({ url: "http://127.0.0.1:4416", defaultUrl: true, reachable: true, poTokenPlugin: { loaded: true } }).text,
    /default PO token provider URL http:\/\/127\.0\.0\.1:4416 \(none is set\)/);
  for (const r of [okResult, missing, down, both, unknown]) {
    assert.doesNotMatch(r.text, /bgutil|YouTube/i, "no plugin names in the UI");
  }
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
