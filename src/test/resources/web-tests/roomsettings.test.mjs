// Unit tests for web/src/roomsettings.ts (compiled), run with Node's test runner by RoomManageJsTest.
// The module path comes in ROOMSETTINGS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.ROOMSETTINGS_JS).href);

const ALL_ON = {
  guests: { enabled: true, canVote: true, canRequestLibrary: true, canUpload: true, canYoutube: true },
  overlay: { enabled: true },
  outputs: { rtmp: { enabled: true }, ts: { enabled: true }, hls: { enabled: true }, mp3: { enabled: true } },
};

function clone(o) {
  return JSON.parse(JSON.stringify(o));
}

test("every room setting of docs/rooms.md is defined once, with its default", () => {
  const keys = S.SETTINGS.map((d) => d.key);
  assert.equal(new Set(keys).size, keys.length);
  assert.deepEqual(keys.slice().sort(), ["allowDownvotes", "audioOnly", "estimatedDelayMs.hls", "estimatedDelayMs.mp3", "estimatedDelayMs.rtmp",
    "estimatedDelayMs.ts", "gapMs", "guests.canRequestLibrary", "guests.canUpload", "guests.canVote",
    "guests.canYoutube", "libraryRequests", "noRepeatWindow", "outputs.autoOff", "outputs.autoOffMinutes", "outputs.rtmp.playbackUrl", "outputs.ts.join", "overlay.corner",
    "overlay.heightPct", "overlay.margin", "overlay.mode", "requestsPerActor", "transitionCard", "uploadRequests",
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
  for (const k of ["overlay.mode", "overlay.corner", "overlay.heightPct", "overlay.margin"]) {
    assert.match(S.serverDisabledReason(k, noOverlay), /server admin/, k);
  }

  const noTs = clone(ALL_ON);
  noTs.outputs.ts.enabled = false;
  assert.match(S.serverDisabledReason("outputs.ts.join", noTs), /\.ts output/);
  assert.match(S.serverDisabledReason("estimatedDelayMs.ts", noTs), /\.ts output/);
  assert.equal(S.serverDisabledReason("estimatedDelayMs.rtmp", noTs), null);
  assert.equal(S.serverDisabledReason("estimatedDelayMs.hls", noTs), null);
  assert.equal(S.serverDisabledReason("estimatedDelayMs.mp3", noTs), null);
  const noMp3 = clone(ALL_ON);
  noMp3.outputs.mp3.enabled = false;
  assert.match(S.serverDisabledReason("estimatedDelayMs.mp3", noMp3), /MP3 output/);
  // an older server without the mp3 switch: not greyed out
  const old = clone(ALL_ON);
  delete old.outputs.mp3;
  assert.equal(S.serverDisabledReason("estimatedDelayMs.mp3", old), null);
  const noRtmp = clone(ALL_ON);
  noRtmp.outputs.rtmp.enabled = false;
  assert.match(S.serverDisabledReason("outputs.rtmp.playbackUrl", noRtmp), /RTMP/);
  assert.match(S.serverDisabledReason("estimatedDelayMs.rtmp", noRtmp), /RTMP/);
});

test("auto turn-off: the server's maximum is shown, a higher value acts as it (clamped), and the force greys the switch", () => {
  const def = S.SETTINGS.find((d) => d.key === "outputs.autoOffMinutes");
  assert.equal(def.default, 10);
  assert.equal(def.min, 1);
  assert.throws(() => S.parseInput(def, "0"), /1 to 1440/);
  assert.equal(S.parseInput(def, "15"), 15);
  const sw = clone(ALL_ON);
  sw.outputs.maxAutoOffMinutes = 60;
  assert.equal(S.serverCapNote(def.key, 10, sw), "This server turns unused outputs off after at most 60 minutes.");
  assert.equal(S.serverCapNote(def.key, 90, sw), "This server turns unused outputs off after at most 60 minutes, so 60 applies.");
  assert.equal(S.serverCapNote(def.key, 90, ALL_ON), null);
  assert.equal(S.serverCapNote(def.key, 90, undefined), null);
  assert.equal(S.serverCapNote("gapMs", 90, sw), null);
  const onOff = S.SETTINGS.find((d) => d.key === "outputs.autoOff");
  assert.equal(onOff.default, true);
  assert.equal(onOff.kind, "bool");
  assert.equal(S.serverDisabledReason("outputs.autoOff", sw), null);
  const forced = clone(sw);
  forced.outputs.forceAutoOff = true;
  assert.match(S.serverDisabledReason("outputs.autoOff", forced), /^Required by the server admin/);
  assert.equal(S.serverDisabledReason("outputs.autoOffMinutes", forced), null, "the minutes stay the room's");
  // the switch applies to every output: one output's server switch doesn't grey it
  const noRtmp = clone(sw);
  noRtmp.outputs.rtmp.enabled = false;
  assert.equal(S.serverDisabledReason("outputs.autoOff", noRtmp), null);
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

test("overlay preview URL follows the form's valid overlay values", () => {
  assert.equal(S.overlayPreviewUrl("r 1", {}), "/api/rooms/r%201/overlay/preview.png");
  assert.equal(S.overlayPreviewUrl("abc", { "overlay.corner": "tl", "overlay.heightPct": " 30 ", "overlay.margin": "0" }),
    "/api/rooms/abc/overlay/preview.png?corner=tl&heightPct=30&margin=0");
  // invalid or out-of-range values are left out (the saved ones apply)
  assert.equal(S.overlayPreviewUrl("abc", { "overlay.corner": "middle", "overlay.heightPct": "4", "overlay.margin": "x" }),
    "/api/rooms/abc/overlay/preview.png");
  assert.equal(S.overlayPreviewUrl("abc", { "overlay.heightPct": "50", "overlay.margin": "21" }),
    "/api/rooms/abc/overlay/preview.png?heightPct=50");
  assert.deepEqual([...S.OVERLAY_PREVIEW_KEYS], ["overlay.corner", "overlay.heightPct", "overlay.margin"]);
});

test("overlay notes: none while the server has it off; videos per the owner's permission; never movies and shows", () => {
  assert.deepEqual(S.overlayNotes(undefined), []);
  const off = clone(ALL_ON);
  off.overlay.enabled = false;
  assert.deepEqual(S.overlayNotes(off), []);
  const video = clone(ALL_ON);
  video.overlay.videoOverlay = true;
  const v = S.overlayNotes(video);
  assert.equal(v.length, 3);
  assert.match(v[0], /only in the stream outputs/);
  assert.match(v[1], /music videos and uncategorized videos show it too/);
  assert.match(v[2], /Movies and shows never show it/);
  const cards = clone(ALL_ON);
  cards.overlay.videoOverlay = false;
  const c = S.overlayNotes(cards);
  assert.match(c[1], /Videos play without the overlay/);
  assert.match(c[1], /Cards .* still show it/);
  assert.equal(S.overlayNotes(ALL_ON).length, 2); // an older server without videoOverlay
});

test("overlay modes: off, always, only when the queue is empty", () => {
  const def = S.SETTINGS.find((d) => d.key === "overlay.mode");
  assert.equal(def.kind, "select");
  assert.equal(def.default, "off");
  assert.deepEqual(def.options.map((o) => o[0]), ["off", "always", "idleOnly"]);
  assert.equal(S.parseInput(def, "idleOnly"), "idleOnly");
  assert.throws(() => S.parseInput(def, "sometimes"));
  assert.equal(S.describeDefault(def), "off");
  assert.match(def.help, /Movies and shows never/);
  // The patch carries the mode nested, as the server stores it.
  assert.deepEqual(S.buildPatch({ overlay: { mode: "off" } }, { "overlay.mode": "idleOnly" }),
    { overlay: { mode: "idleOnly" } });
});
