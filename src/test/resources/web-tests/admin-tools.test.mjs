// Unit tests for web/src/youtubeservice.ts, web/src/ytdlp.ts and web/src/audit.ts (compiled), run with Node's
// test runner by AdminToolsJsTest. Module paths come in YTSERVICE_JS, YTDLP_JS and AUDIT_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const Y = await import(pathToFileURL(process.env.YTDLP_JS).href);
const A = await import(pathToFileURL(process.env.AUDIT_JS).href);
const S = await import(pathToFileURL(process.env.YTSERVICE_JS).href);

const STATUS = {
  configured: true, reachable: true,
  versions: { ytdlp: "2026.11.20", ytdlpEjs: "0.3.1", tokenPlugin: "1.2.2", tokenProvider: "1.2.2", deno: "2.5.6",
    ffmpeg: "N-12345", channel: "nightly" },
  providers: { ready: 1, total: 1 }, updating: false, staging: false,
  lastUpdate: { time: "2026-11-28T04:00:00+00:00", result: "up_to_date" }, activeJobs: 2,
};

test("YouTube service box: status, versions, providers and the last update", () => {
  assert.match(S.statusText({ configured: false, reachable: false }), /No YouTube service is set \(youtube\.serviceUrl\)/);
  assert.equal(S.statusText({ configured: true, reachable: false, error: "connection refused" }),
    "The YouTube service can't be reached: connection refused");
  assert.equal(S.statusText(STATUS), "The YouTube service is running. 2 downloads running.");
  assert.match(S.statusText({ ...STATUS, updating: true, activeJobs: 1 }), /^The YouTube service is updating its tools… 1 download running\.$/);
  assert.equal(S.updating({ ...STATUS, updating: true }), true);
  assert.equal(S.updating(STATUS), false);
  assert.deepEqual(S.versionLines(STATUS), ["yt-dlp 2026.11.20 (nightly)", "yt-dlp challenge solver 0.3.1",
    "Token provider plugin 1.2.2", "Token provider 1.2.2", "JavaScript runtime 2.5.6", "ffmpeg N-12345"]);
  assert.deepEqual(S.versionLines({ configured: true, reachable: false }), []);
  assert.equal(S.providerText(STATUS), "Token provider ready.");
  assert.equal(S.providerText({ ...STATUS, providers: { ready: 1, total: 2 } }), "Token providers ready: 1 of 2.");
  assert.equal(S.providerText({ ...STATUS, providers: { ready: 0, total: 0 } }), "No token provider is running.");
  assert.equal(S.providerText({ configured: true, reachable: true }), undefined);
  assert.deepEqual(S.lastUpdateText(STATUS), { text: "Last update 2026-11-28T04:00:00+00:00: already up to date.", warning: false });
  const failing = S.lastUpdateText({ ...STATUS, lastUpdate: { result: "failing" } });
  assert.equal(failing.warning, true);
  assert.match(failing.text, /even the newest tools fail their self-test; YouTube is probably broken until a fix ships/);
  assert.match(S.lastUpdateText({ ...STATUS, lastUpdate: { result: "rejected" } }).text, /self-test, so the current ones stay/);
  assert.match(S.lastUpdateText({ ...STATUS, lastUpdate: { result: "updated" } }).text, /updated to newer tools/);
  assert.equal(S.lastUpdateText({ configured: true, reachable: true }), undefined);
  for (const line of [...S.versionLines(STATUS), S.providerText(STATUS), S.statusText(STATUS), S.INTRO]) {
    assert.doesNotMatch(line, /bgutil|deno|PO token/i, "no plugin or runtime names: " + line);
  }
});

test("YouTube service box: Update now, the log and Test connection", () => {
  assert.match(S.startedText({ started: true }), /^Update started/);
  assert.equal(S.startedText({ started: false, reason: "update already running" }), "No update started: update already running.");
  assert.equal(S.startedText({ started: false }), "No update started.");
  assert.equal(S.logText({ truncated: false }), "The YouTube service hasn't updated its tools yet.");
  assert.equal(S.logText({ log: "a\nb\n", truncated: false }), "a\nb\n");
  assert.equal(S.logText({ log: "end\n", truncated: true }), "(only the end of the log)\nend\n");
  const ok = S.testText({ url: "http://yt:8080", reachable: true, tokenAccepted: true, httpStatus: 404 });
  assert.deepEqual(ok, { ok: true, text: "OK: the YouTube service at http://yt:8080 answers and accepts this server." });
  const refused = S.testText({ url: "http://yt:8080", reachable: true, tokenAccepted: false, httpStatus: 401 });
  assert.equal(refused.ok, false);
  assert.match(refused.text, /^The YouTube service at http:\/\/yt:8080 answers but refuses the token: set youtube\.serviceToken/);
  const down = S.testText({ url: "http://yt:8080", reachable: false, error: "connection refused" });
  assert.deepEqual(down, { ok: false, text: "Can't reach the YouTube service at http://yt:8080: connection refused." });
  assert.equal(S.testText({ reachable: false }).text, "Can't reach the YouTube service: no answer.");
  assert.deepEqual(S.testText({ reachable: true, httpStatus: 500, error: "Unexpected answer (HTTP 500)" }),
    { ok: false, text: "Unexpected answer (HTTP 500)" });
});

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
  assert.ok(A.AUDIT_ACTIONS.includes("admin.youtube_service_update"));
  assert.ok(A.AUDIT_ACTIONS.includes("auth.login"));
});
