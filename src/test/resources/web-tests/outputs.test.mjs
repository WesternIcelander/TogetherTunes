// Unit tests for web/src/outputs.ts (compiled), run with Node's test runner by OutputsJsTest.
// The module path comes in OUTPUTS_JS, remote.ts's in REMOTE_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const O = await import(pathToFileURL(process.env.OUTPUTS_JS).href);
const R = await import(pathToFileURL(process.env.REMOTE_JS).href);

test("output.status: replaces, adds in order, removes on off, ignores malformed messages", () => {
  const start = [{ kind: "rtmp", status: "connecting", estimatedDelayMs: 5000 }, { kind: "hls", status: "ready", estimatedDelayMs: 8000 }];
  let list = O.applyOutputStatus(start, { type: "output.status", kind: "rtmp", status: "connected", estimatedDelayMs: 6000 });
  assert.deepEqual(list[0], { kind: "rtmp", status: "connected", estimatedDelayMs: 6000 });
  assert.equal(start[0].status, "connecting", "the input list isn't changed");
  // no delay in the message: the known one stays
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "retrying", detail: "Connection refused" });
  assert.deepEqual(list[0], { kind: "rtmp", status: "retrying", estimatedDelayMs: 6000, detail: "Connection refused" });
  // a new one goes in its place: rtmp, ts, hls
  list = O.applyOutputStatus(list, { kind: "ts", status: "ready", estimatedDelayMs: 2000 });
  assert.deepEqual(list.map((o) => o.kind), ["rtmp", "ts", "hls"]);
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "off" });
  assert.deepEqual(list.map((o) => o.kind), ["ts", "hls"]);
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "off" });
  assert.deepEqual(list.map((o) => o.kind), ["ts", "hls"]);
  assert.deepEqual(O.applyOutputStatus(list, { kind: 3, status: "ready" }), list);
  assert.deepEqual(O.applyOutputStatus(list, { kind: "ts" }), list);
  assert.deepEqual(O.applyOutputStatus(undefined, { kind: "hls", status: "ready" }), [{ kind: "hls", status: "ready" }]);
  // MP3 goes last
  list = O.applyOutputStatus([{ kind: "hls", status: "ready" }], { kind: "mp3", status: "ready" });
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "connecting" });
  assert.deepEqual(list.map((o) => o.kind), ["rtmp", "hls", "mp3"]);
});

test("the MP3 output: a generic label and description, no allowlist note of its own", () => {
  assert.deepEqual(O.OUTPUT_KINDS, ["rtmp", "ts", "hls", "mp3"]);
  assert.equal(O.OUTPUT_LABELS.mp3, "MP3 audio");
  assert.match(O.OUTPUT_DESCRIPTIONS.mp3, /internet-radio players/);
  const mp3Row = { id: "o", kind: "mp3", enabled: true, serverEnabled: true, active: true, createdAt: 1 };
  assert.deepEqual(O.outputNotes([mp3Row]), []);
  assert.equal(O.canCreate("mp3", [mp3Row], true), false);
  assert.equal(O.canCreate("mp3", [], true), true);
  assert.equal(O.canCreate("mp3", [], false), false);
});

test("output.status feeds the remote delay: the active output and its estimatedDelayMs", () => {
  let list = O.applyOutputStatus([], { kind: "hls", status: "ready", estimatedDelayMs: 8000 });
  assert.deepEqual(R.outputDelay(list, {}), { kind: "hls", delayMs: 8000 });
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "connected", estimatedDelayMs: 5000 });
  assert.deepEqual(R.outputDelay(list, {}), { kind: "rtmp", delayMs: 5000 });
  // an RTMP push in error doesn't carry the room: HLS does
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "error" });
  assert.deepEqual(R.outputDelay(list, {}), { kind: "hls", delayMs: 8000 });
  list = O.applyOutputStatus(list, { kind: "rtmp", status: "off" });
  list = O.applyOutputStatus(list, { kind: "hls", status: "off" });
  // nothing active: RTMP with the room setting, else its default
  assert.deepEqual(R.outputDelay(list, { estimatedDelayMs: { rtmp: 4000 } }), { kind: "rtmp", delayMs: 4000 });
  assert.deepEqual(R.outputDelay(list, undefined), { kind: "rtmp", delayMs: 5000 });
});

test("active key changes only when the set of active outputs does", () => {
  assert.equal(O.activeKey(undefined), "");
  assert.equal(O.activeKey([{ kind: "ts", status: "ready" }, { kind: "rtmp", status: "connecting" }]), "rtmp,ts");
  assert.equal(O.activeKey([{ kind: "rtmp", status: "connected" }, { kind: "ts", status: "ready" }]), "rtmp,ts");
});

test("RTMP playback URL: the room setting, only while RTMP is active", () => {
  const settings = { outputs: { rtmp: { playbackUrl: " rtsp://stream.vrcdn.live/live/abc " } } };
  assert.equal(O.rtmpPlaybackUrl([{ kind: "rtmp", status: "connected" }], settings), "rtsp://stream.vrcdn.live/live/abc");
  assert.equal(O.rtmpPlaybackUrl([{ kind: "ts", status: "ready" }], settings), undefined);
  assert.equal(O.rtmpPlaybackUrl([], settings), undefined);
  assert.equal(O.rtmpPlaybackUrl([{ kind: "rtmp" }], { outputs: { rtmp: { playbackUrl: "" } } }), undefined);
  assert.equal(O.rtmpPlaybackUrl([{ kind: "rtmp" }], undefined), undefined);
});

const row = (over) => ({ id: "o1", kind: "rtmp", enabled: true, serverEnabled: true, active: true, createdAt: 1,
  rtmpUrl: "rtmp://ingest.example/live", hasRtmpKey: true, rtmpKey: "live_123456789_abcdWXYZ", rtmpAvailable: true, ...over });

test("push line: live status first, failures and next try for retrying/error, off and no ffmpeg", () => {
  assert.equal(O.pushLine(undefined, row({}), 0), "Connecting…");
  assert.equal(O.pushLine({ kind: "rtmp", status: "connected" }, row({}), 0), "Connected");
  const failing = row({ status: { state: "retrying", detail: "Connection refused", failures: 2, since: 1, retryAt: 10_000 } });
  assert.equal(O.pushLine({ kind: "rtmp", status: "retrying" }, failing, 5_000), "Retrying (2 failures in a row), next try in 5 s");
  assert.equal(O.pushLine({ kind: "rtmp", status: "retrying" }, failing, 20_000), "Retrying (2 failures in a row)");
  // the live state moved on: the API's counts are stale
  assert.equal(O.pushLine({ kind: "rtmp", status: "connected" }, failing, 5_000), "Connected");
  assert.equal(O.pushLine(undefined, row({ status: { state: "error", failures: 1, since: 1 } }), 0), "Error (1 failure in a row)");
  assert.equal(O.pushLine(undefined, row({ enabled: false, active: false }), 0), "Off");
  assert.equal(O.pushLine(undefined, row({ serverEnabled: false, active: false }), 0), "Off (disabled by the server admin)");
  assert.match(O.pushLine(undefined, row({ rtmpAvailable: false }), 0), /^Error: .*ffmpeg/);
  // turned off by the server's auto turn-off: the owner is told why and what to do
  const idle = row({ enabled: false, active: false, stopped: { reason: "idle", at: 1, idleMinutes: 10 } });
  assert.equal(O.pushLine(undefined, idle, 0), "Off: queue empty for 10 minutes. Turn on to start again.");
  assert.equal(O.autoOffLine(row({ enabled: false, active: false, stopped: { reason: "idle", at: 1, idleMinutes: 1 } })),
    "Off: queue empty for 1 minute. Turn on to start again.");
  assert.equal(O.autoOffLine(row({ kind: "ts", enabled: false, active: false,
    stopped: { reason: "no_listeners", at: 1, idleMinutes: 10 } })), "Off: no listeners for 10 minutes. Turn on to start again.");
  assert.equal(O.autoOffLine(row({ enabled: false, active: false, stopped: { reason: "later", at: 1, idleMinutes: 10 } })),
    undefined);
  assert.equal(O.autoOffLine(row({ stopped: { reason: "idle", at: 1, idleMinutes: 10 } })), undefined); // on again
  assert.equal(O.pushLine(undefined, row({ enabled: false, active: false, serverEnabled: false,
    stopped: { reason: "idle", at: 1, idleMinutes: 10 } }), 0), "Off (disabled by the server admin)");
  assert.equal(O.pushDetail({ kind: "rtmp", status: "retrying", detail: "live" }, failing), "live");
  assert.equal(O.pushDetail(undefined, failing), "Connection refused");
  assert.equal(O.pushDetail(undefined, row({ active: false, status: failing.status })), undefined);
});

test("notes: player allowlist with any self-hosted URL, HLS desync while HLS is on", () => {
  assert.deepEqual(O.outputNotes([]), []);
  assert.deepEqual(O.outputNotes([row({})]), []);
  assert.deepEqual(O.outputNotes([row({ kind: "ts" })]), [O.ALLOWLIST_NOTE]);
  assert.deepEqual(O.outputNotes([row({ kind: "hls" })]), [O.ALLOWLIST_NOTE, O.HLS_DESYNC_NOTE]);
  assert.deepEqual(O.outputNotes([row({ kind: "hls", active: false })]), [O.ALLOWLIST_NOTE]);
  assert.match(O.ALLOWLIST_NOTE, /allow untrusted URLs/);
  assert.doesNotMatch(O.ALLOWLIST_NOTE + O.HLS_DESYNC_NOTE, /VRChat/);
  assert.match(O.HLS_DESYNC_NOTE, /a few seconds apart/);
});

test("create only a kind the room doesn't have, with the server switch on; RTMP URLs", () => {
  assert.equal(O.canCreate("ts", [], true), true);
  assert.equal(O.canCreate("ts", [], false), false);
  assert.equal(O.canCreate("ts", [row({ kind: "ts" })], true), false);
  assert.equal(O.canCreate("hls", [row({ kind: "ts" })], true), true);
  assert.equal(O.validRtmpUrl("rtmp://ingest.vrcdn.live/live"), true);
  assert.equal(O.validRtmpUrl(" rtmps://a.example "), true);
  assert.equal(O.validRtmpUrl("https://a.example/live"), false);
  assert.equal(O.validRtmpUrl("rtmp://"), false);
  assert.equal(O.statusLabel("ready"), "Ready");
  assert.equal(O.statusLabel(undefined), "Off");
  assert.equal(O.statusLabel("weird"), "weird");
});

test("stream URLs: only token URLs (private rooms) rotate; the URLs follow the room's slug and visibility", () => {
  const url = (over) => ({ id: "o2", kind: "ts", enabled: true, serverEnabled: true, active: true, createdAt: 1, ...over });
  assert.equal(O.canRotate(url({ urlKind: "token" })), true);
  assert.equal(O.canRotate(url({ urlKind: "slug" })), false);
  assert.equal(O.canRotate(url({})), false);
  assert.equal(O.urlKey(undefined), "");
  assert.equal(O.urlKey({ slug: "club", visibility: "public" }), "public/club");
  assert.notEqual(O.urlKey({ slug: "club", visibility: "public" }), O.urlKey({ slug: "club", visibility: "private" }));
  assert.notEqual(O.urlKey({ slug: "club", visibility: "public" }), O.urlKey({ slug: "disco", visibility: "public" }));
});
