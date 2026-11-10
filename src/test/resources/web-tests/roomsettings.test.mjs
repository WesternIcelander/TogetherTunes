// Unit tests for web/src/roomsettings.ts (compiled), run with Node's test runner by RoomManageJsTest.
// The module path comes in ROOMSETTINGS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.ROOMSETTINGS_JS).href);

const ALL_ON = {
  guests: { enabled: true, canVote: true, canRequestLibrary: true, canUpload: true, canYoutube: true },
  overlay: { enabled: true },
  outputs: { rtmp: { enabled: true }, ts: { enabled: true }, hls: { enabled: true } },
};

function clone(o) {
  return JSON.parse(JSON.stringify(o));
}

test("every room setting of docs/rooms.md is defined once, with its default", () => {
  const keys = S.SETTINGS.map((d) => d.key);
  assert.equal(new Set(keys).size, keys.length);
  assert.deepEqual(keys.slice().sort(), ["allowDownvotes", "estimatedDelayMs.hls", "estimatedDelayMs.rtmp",
    "estimatedDelayMs.ts", "gapMs", "guests.canRequestLibrary", "guests.canUpload", "guests.canVote",
    "guests.canYoutube", "libraryRequests", "noRepeatWindow", "outputs.rtmp.idleStopMinutes", "outputs.rtmp.playbackUrl", "outputs.ts.join", "overlay.corner",
    "overlay.enabled", "overlay.heightPct", "overlay.margin", "requestsPerActor", "transitionCard", "uploadRequests",
    "voteSkipThreshold"]);
  for (const d of S.SETTINGS) assert.ok(S.GROUPS.some((g) => g[0] === d.group), d.key);
});

test("server switches grey out exactly what they turn off", () => {
  for (const d of S.SETTINGS) assert.equal(S.serverDisabledReason(d.key, ALL_ON), null, d.key);
  for (const d of S.SETTINGS) assert.equal(S.serverDisabledReason(d.key, undefined), null, d.key);

  const noUpload = clone(ALL_ON);
  noUpload.guests.canUpload = false;
  assert.equal(S.serverDisabledReason("guests.canUpload", noUpload), "Disabled by the server admin");
  assert.equal(S.serverDisabledReason("guests.canVote", noUpload), null);

  const noGuests = clone(ALL_ON);
  noGuests.guests.enabled = false;
  for (const k of ["guests.canVote", "guests.canRequestLibrary", "guests.canUpload", "guests.canYoutube"]) {
    assert.match(S.serverDisabledReason(k, noGuests), /^Guests are disabled by the server admin/, k);
  }
  assert.equal(S.serverDisabledReason("libraryRequests", noGuests), null);

  const noOverlay = clone(ALL_ON);
  noOverlay.overlay.enabled = false;
  for (const k of ["overlay.enabled", "overlay.corner", "overlay.heightPct", "overlay.margin"]) {
    assert.match(S.serverDisabledReason(k, noOverlay), /server admin/, k);
  }

  const noTs = clone(ALL_ON);
  noTs.outputs.ts.enabled = false;
  assert.match(S.serverDisabledReason("outputs.ts.join", noTs), /\.ts output/);
  assert.match(S.serverDisabledReason("estimatedDelayMs.ts", noTs), /\.ts output/);
  assert.equal(S.serverDisabledReason("estimatedDelayMs.rtmp", noTs), null);
  assert.equal(S.serverDisabledReason("estimatedDelayMs.hls", noTs), null);
  const noRtmp = clone(ALL_ON);
  noRtmp.outputs.rtmp.enabled = false;
  assert.match(S.serverDisabledReason("outputs.rtmp.playbackUrl", noRtmp), /RTMP/);
  assert.match(S.serverDisabledReason("estimatedDelayMs.rtmp", noRtmp), /RTMP/);
});

test("the RTMP idle stop: the server's maximum is shown, and a higher value acts as it (clamped)", () => {
  const def = S.SETTINGS.find((d) => d.key === "outputs.rtmp.idleStopMinutes");
  assert.equal(def.default, 10);
  assert.equal(def.min, 1);
  assert.throws(() => S.parseInput(def, "0"), /1 to 1440/);
  assert.equal(S.parseInput(def, "15"), 15);
  const sw = clone(ALL_ON);
  sw.outputs.rtmp.maxIdleStopMinutes = 60;
  assert.equal(S.serverCapNote(def.key, 10, sw), "This server stops idle pushes after at most 60 minutes.");
  assert.equal(S.serverCapNote(def.key, 90, sw), "This server stops idle pushes after at most 60 minutes, so 60 applies.");
  assert.equal(S.serverCapNote(def.key, 90, ALL_ON), null);
  assert.equal(S.serverCapNote(def.key, 90, undefined), null);
  assert.equal(S.serverCapNote("gapMs", 90, sw), null);
  const noRtmp = clone(sw);
  noRtmp.outputs.rtmp.enabled = false;
  assert.match(S.serverDisabledReason(def.key, noRtmp), /RTMP/);
});

test("dotted names read and write nested objects", () => {
  const o = { guests: { canVote: true }, libraryRequests: true };
  assert.equal(S.getPath(o, "guests.canVote"), true);
  assert.equal(S.getPath(o, "libraryRequests"), true);
  assert.equal(S.getPath(o, "outputs.rtmp.playbackUrl"), undefined);
  assert.equal(S.getPath(o, "libraryRequests.x"), undefined);
  S.setPath(o, "outputs.rtmp.playbackUrl", "rtmp://x/y");
  S.setPath(o, "guests.canUpload", false);
  assert.deepEqual(o, { guests: { canVote: true, canUpload: false }, libraryRequests: true,
    outputs: { rtmp: { playbackUrl: "rtmp://x/y" } } });
});

function def(key) {
  return S.SETTINGS.find((d) => d.key === key);
}

test("form input is parsed per kind, with the server's ranges", () => {
  assert.equal(S.parseInput(def("allowDownvotes"), true), true);
  assert.equal(S.parseInput(def("allowDownvotes"), false), false);
  assert.equal(def("libraryRequests").default, true);
  assert.equal(S.parseInput(def("libraryRequests"), false), false);
  assert.equal(S.parseInput(def("overlay.corner"), "tl"), "tl");
  assert.throws(() => S.parseInput(def("overlay.corner"), "middle"));
  assert.equal(S.parseInput(def("requestsPerActor"), " 7 "), 7);
  assert.throws(() => S.parseInput(def("requestsPerActor"), "0"), /1 to 1000/);
  assert.throws(() => S.parseInput(def("requestsPerActor"), "2.5"), /whole number/);
  assert.throws(() => S.parseInput(def("requestsPerActor"), ""), /whole number/);
  assert.equal(S.parseInput(def("noRepeatWindow"), "0"), 0);
  assert.equal(S.parseInput(def("estimatedDelayMs.hls"), "120000"), 120000);
  assert.throws(() => S.parseInput(def("estimatedDelayMs.hls"), "120001"));
  assert.equal(S.parseInput(def("outputs.rtmp.playbackUrl"), "  "), "");
  assert.throws(() => S.parseInput(def("outputs.rtmp.playbackUrl"), "x".repeat(2049)), /2048/);
  // The threshold: a percentage in the form, a fraction in (0, 1] or "off" on the wire.
  const t = def("voteSkipThreshold");
  assert.equal(S.parseInput(t, "50"), 0.5);
  assert.equal(S.parseInput(t, "33,33"), 0.3333);
  assert.equal(S.parseInput(t, "100"), 1);
  assert.equal(S.parseInput(t, ""), "off");
  assert.equal(S.parseInput(t, "Off"), "off");
  assert.throws(() => S.parseInput(t, "0"));
  assert.throws(() => S.parseInput(t, "101"));
  assert.throws(() => S.parseInput(t, "-5"));
  assert.equal(S.thresholdToPercent(0.5), "50");
  assert.equal(S.thresholdToPercent(0.3333), "33.33");
  assert.equal(S.thresholdToPercent("off"), "");
});

test("the patch holds only what changed, nested", () => {
  const current = {
    libraryRequests: true, requestsPerActor: 3, voteSkipThreshold: 0.5,
    guests: { canVote: true, canUpload: false }, outputs: { rtmp: { playbackUrl: "" }, ts: { join: "next" } },
  };
  assert.equal(S.buildPatch(current, { libraryRequests: true, requestsPerActor: 3, voteSkipThreshold: 0.5 }), null);
  assert.equal(S.buildPatch(current, {}), null);
  assert.deepEqual(S.buildPatch(current, { requestsPerActor: 5, "guests.canVote": true, "guests.canUpload": true,
    "outputs.ts.join": "latest", voteSkipThreshold: "off" }),
  { requestsPerActor: 5, guests: { canUpload: true }, outputs: { ts: { join: "latest" } }, voteSkipThreshold: "off" });
  // Percent rounding doesn't count as a change.
  assert.equal(S.buildPatch({ voteSkipThreshold: 0.1 + 0.2 }, { voteSkipThreshold: 0.3 }), null);
  // Names that aren't settings are never sent.
  assert.equal(S.buildPatch(current, { bogus: 1 }), null);
});

test("defaults are described as the form shows them", () => {
  assert.equal(S.describeDefault(def("voteSkipThreshold")), "50%");
  assert.equal(S.describeDefault(def("guests.canUpload")), "off");
  assert.equal(S.describeDefault(def("outputs.rtmp.playbackUrl")), "none");
  assert.equal(S.describeDefault(def("requestsPerActor")), "3");
  assert.equal(S.describeDefault(def("overlay.corner")), "bottom right");
});

test("member management follows the room roles", () => {
  assert.deepEqual(S.assignableRoles("owner"), ["moderator", "member", "banned"]);
  assert.deepEqual(S.assignableRoles("moderator"), ["member", "banned"]);
  assert.deepEqual(S.assignableRoles("member"), []);
  assert.deepEqual(S.assignableRoles(undefined), []);
  const me = "u1";
  assert.equal(S.canManageMember("owner", me, { id: "u2", role: "moderator" }), true);
  assert.equal(S.canManageMember("owner", me, { id: "u2", role: "banned" }), true);
  assert.equal(S.canManageMember("owner", me, { id: me, role: "owner" }), false);
  assert.equal(S.canManageMember("moderator", me, { id: "u2", role: "member" }), true);
  assert.equal(S.canManageMember("moderator", me, { id: "u2", role: "banned" }), true);
  assert.equal(S.canManageMember("moderator", me, { id: "u2", role: "moderator" }), false);
  assert.equal(S.canManageMember("moderator", me, { id: "u2", role: "owner" }), false);
  assert.equal(S.canManageMember("moderator", me, { id: me, role: "moderator" }), false);
  assert.equal(S.canManageMember("member", me, { id: "u2", role: "member" }), false);
});

test("uploads need library requests: the companion change in the form, with a notice", () => {
  const form = { libraryRequests: false, uploadRequests: false, "guests.canRequestLibrary": true, "guests.canUpload": true };
  const get = (k) => form[k];
  let c = S.coupledChange("uploadRequests", true, get);
  assert.deepEqual([c.key, c.value], ["libraryRequests", true]);
  assert.match(c.notice, /^Uploads need library requests: turned "Allow library requests" on too\.$/);
  c = S.coupledChange("guests.canRequestLibrary", false, get);
  assert.deepEqual([c.key, c.value], ["guests.canUpload", false]);
  assert.match(c.notice, /turned "Guests can upload files" off too/);
  // nothing to do: library on already, library turned on, upload turned off, other settings
  assert.equal(S.coupledChange("uploadRequests", true, (k) => (k === "libraryRequests" ? true : undefined)), null);
  assert.equal(S.coupledChange("libraryRequests", true, get), null);
  assert.equal(S.coupledChange("guests.canUpload", false, get), null);
  assert.equal(S.coupledChange("libraryRequests", false, get), null, "upload already off");
  assert.equal(S.coupledChange("allowDownvotes", true, get), null);
  // the admin page labels switches by key
  assert.match(S.coupledChange("guests.canUpload", true, () => false, (k) => k).notice, /"guests.canRequestLibrary" on/);
});

test("the notice for settings the server adjusted", () => {
  const after = { libraryRequests: true, uploadRequests: false };
  assert.equal(S.adjustedNotice(undefined, (k) => after[k]), null);
  assert.equal(S.adjustedNotice([], (k) => after[k]), null);
  assert.equal(S.adjustedNotice(["libraryRequests"], (k) => after[k]),
    "Uploads need library requests: turned \"Allow library requests\" on too.");
  assert.equal(S.adjustedNotice(["uploadRequests"], (k) => after[k]),
    "Uploads need library requests: turned \"Allow uploads as requests\" off too.");
  assert.ok(S.SETTINGS.some((d) => d.key === "uploadRequests" && d.default === true && d.group === "requests"));
});
