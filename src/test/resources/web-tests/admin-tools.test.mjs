// Unit tests for web/src/youtubeservice.ts and web/src/audit.ts (compiled), run with Node's
// test runner by AdminToolsJsTest. Module paths come in YTSERVICE_JS and AUDIT_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

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
  assert.ok(A.AUDIT_ACTIONS.includes("admin.ytdlp_update"), "older audit rows can still be filtered by it");
  assert.ok(A.AUDIT_ACTIONS.includes("admin.youtube_service_update"));
  assert.ok(A.AUDIT_ACTIONS.includes("auth.login"));
});
