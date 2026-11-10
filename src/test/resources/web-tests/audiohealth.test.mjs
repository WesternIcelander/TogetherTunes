// Unit tests for web/src/audiohealth.ts (compiled), run with Node's test runner by ListenJsTest.
// The module path comes in AUDIOHEALTH_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const A = await import(pathToFileURL(process.env.AUDIOHEALTH_JS).href);

/** A window of `n` samples of a 0.5 sine, with `zeros` ranges [from, to) set to exact 0. */
function sine(n, zeros = []) {
  const w = new Float32Array(n);
  for (let i = 0; i < n; i++) w[i] = 0.5 * Math.sin(2 * Math.PI * 440 * i / 48000) + 0.001; // never exactly 0
  for (const [a, b] of zeros) w.fill(0, a, b);
  return w;
}

test("windowStats: peak, energy, and gaps only inside a window with signal", () => {
  const s = A.windowStats(sine(2048));
  assert.ok(s.peak > 0.49 && s.peak < 0.51);
  assert.equal(s.n, 2048);
  assert.equal(s.gaps, 0);
  assert.ok(s.sumSq > 0);
  // A dropout of 200 samples in the middle, and one of 100 (below GAP_MIN_SAMPLES).
  assert.equal(A.windowStats(sine(2048, [[500, 700], [1000, 1100]])).gaps, 1);
  // Runs at the edges count too (a dropout straddling two windows).
  assert.equal(A.windowStats(sine(2048, [[0, 300], [1800, 2048]])).gaps, 2);
  // All zeros: silence, not a gap.
  const z = A.windowStats(new Float32Array(2048));
  assert.deepEqual(z, { peak: 0, sumSq: 0, n: 2048, gaps: 0 });
  // Negative samples count by their size.
  assert.equal(A.windowStats([0, -0.8, 0.3]).peak, 0.8);
});

test("LevelMeter sums windows until take()", () => {
  const m = new A.LevelMeter();
  assert.deepEqual(m.take(), { windows: 0, peak: 0, rms: 0, gaps: 0, silent: 0 });
  m.add(A.windowStats(sine(2048, [[500, 700]])));
  m.add(A.windowStats(new Float32Array(2048)));
  m.add({ peak: 0.9, sumSq: 0, n: 10, gaps: 0 });
  const l = m.take();
  assert.equal(l.windows, 3);
  assert.equal(l.peak, 0.9);
  assert.equal(l.gaps, 1);
  assert.equal(l.silent, 1);
  assert.ok(l.rms > 0.2 && l.rms < 0.3);
  assert.equal(m.take().windows, 0); // started over
});

test("toDb: dBFS rounded, exact silence as null", () => {
  assert.equal(A.toDb(1), 0);
  assert.equal(A.toDb(0.5), -6);
  assert.equal(A.toDb(0.1), -20);
  assert.equal(A.toDb(0), null);
  assert.equal(A.toDb(NaN), null);
});

test("ClockWatch: audio-clock ms per 1000 ms of wall clock", () => {
  const c = new A.ClockWatch();
  assert.equal(c.sample(1000, 10), undefined); // first sample
  assert.equal(c.sample(2000, 11), 1000); // normal
  assert.equal(c.sample(2100, 11.1), undefined); // too short an interval: ignored
  assert.equal(c.sample(3000, 11.5), 500); // half speed (measured from 2000)
  assert.equal(c.sample(4000, 11.5), 0); // stalled
  c.reset();
  assert.equal(c.sample(5000, 20), undefined);
});

const loud = { windows: 25, peak: 0.4, rms: 0.1, gaps: 0, silent: 0 };
const quiet = { windows: 25, peak: 0, rms: 0, gaps: 0, silent: 25 };

test("MuteDetector: 10 silent seconds in a row after having been heard", () => {
  const d = new A.MuteDetector();
  // Silence before anything was heard (a silent intro, or a path that never worked) doesn't count.
  for (let i = 0; i < 20; i++) assert.equal(d.sample({ shouldSound: true, level: quiet }), false);
  assert.equal(d.heard, false);
  assert.equal(d.sample({ shouldSound: true, level: loud }), false);
  assert.equal(d.heard, true);
  for (let i = 1; i < A.MUTE_SECONDS; i++) {
    assert.equal(d.sample({ shouldSound: true, level: quiet }), false);
    assert.equal(d.silentSeconds, i);
  }
  // A second with signal starts over.
  assert.equal(d.sample({ shouldSound: true, level: loud }), false);
  assert.equal(d.silentSeconds, 0);
  for (let i = 1; i < A.MUTE_SECONDS; i++) assert.equal(d.sample({ shouldSound: true, level: quiet }), false);
  // Seconds in which it shouldn't sound (paused, gain 0, seeking) or the meter read too few windows
  // neither count nor reset.
  assert.equal(d.sample({ shouldSound: false, level: quiet }), false);
  assert.equal(d.sample({ shouldSound: true, level: { ...quiet, windows: A.MIN_WINDOWS_PER_SECOND - 1 } }), false);
  assert.equal(d.silentSeconds, A.MUTE_SECONDS - 1);
  // The element isn't decoding audio: its silence isn't Web Audio's.
  assert.equal(d.sample({ shouldSound: true, level: quiet, decodedBytes: 0 }), false);
  assert.equal(d.sample({ shouldSound: true, level: quiet, decodedBytes: 4000 }), true);
  assert.equal(d.tripped, true);
  assert.equal(d.sample({ shouldSound: true, level: loud }), true); // stays tripped
});

test("webAudioWanted: ?webaudio=0 turns Web Audio off", () => {
  assert.equal(A.webAudioWanted(null), true);
  assert.equal(A.webAudioWanted(undefined), true);
  assert.equal(A.webAudioWanted("1"), true);
  assert.equal(A.webAudioWanted(""), true);
  for (const v of ["0", "off", "false", "no", " OFF "]) assert.equal(A.webAudioWanted(v), false, v);
});

test("delta: counter changes, nothing when missing or reset", () => {
  assert.equal(A.delta(150, 100), 50);
  assert.equal(A.delta(100, 100), 0);
  assert.equal(A.delta(50, 100), undefined); // went back (new source)
  assert.equal(A.delta(undefined, 100), undefined);
  assert.equal(A.delta(100, undefined), undefined);
});

// --- WebAudioCheck: the iOS sample-rate mismatch (report uhmxlu) and steady dropouts ---

const music = (gaps = 0, silent = 0) => ({ windows: 25, peak: 0.5, rms: 0.1, gaps, silent });
const sec = (ck, extra = {}) => ({ ck, running: true, shouldSound: true, level: music(), ...extra });

test("snapRate: the implied rate snaps to a standard one", () => {
  assert.equal(A.snapRate(44100 * 1.088), 48000); // uhmxlu: 47981
  assert.equal(A.snapRate(44100 * 1.086), 48000);
  assert.equal(A.snapRate(48000 * 0.919), 44100);
  assert.equal(A.snapRate(48000 * 2), 96000);
  assert.equal(A.snapRate(45203), 45203); // nothing within 1.5 %: as measured
});

test("WebAudioCheck: the uhmxlu clock (1086–1091 ms per s) trips after 3 samples, implying 48 kHz", () => {
  const c = new A.WebAudioCheck(44100);
  assert.equal(c.sample(sec(undefined)).kind, "pending"); // ClockWatch's first sample
  assert.equal(c.sample(sec(1088, { level: music(8) })).kind, "pending");
  assert.equal(c.sample(sec(1091, { level: music(11) })).kind, "pending");
  const v = c.sample(sec(1086, { level: music(5) }));
  assert.equal(v.kind, "clock"); // the clock wins over the gaps it causes
  assert.equal(v.impliedRate, 48000);
  assert.equal(v.sampleRate, 44100);
  assert.equal(v.ratio, 1.088); // the median
  assert.equal(c.failed, true);
  assert.equal(c.sample(sec(1000)).kind, "clock"); // sticks
  c.reset();
  assert.equal(c.verdict.kind, "pending");
});

test("WebAudioCheck: the clock threshold is 2 % for 3 samples in a row", () => {
  const c = new A.WebAudioCheck(48000);
  for (const ck of [1000 + A.CLOCK_TOLERANCE, 1000 - A.CLOCK_TOLERANCE, 1015, 985]) assert.equal(c.sample(sec(ck)).kind, "pending");
  assert.equal(c.sample(sec(1000)).kind, "ok"); // 5 clean samples
  // Two off, one clean (starts over), then three off.
  assert.equal(c.sample(sec(1030)).kind, "ok");
  assert.equal(c.sample(sec(1030)).kind, "ok");
  assert.equal(c.sample(sec(1001)).kind, "ok");
  assert.equal(c.sample(sec(1030)).kind, "ok");
  assert.equal(c.sample(sec(1030)).kind, "ok");
  // Not running, no sample, or a stall / catch-up neither count nor reset.
  assert.equal(c.sample(sec(1030, { running: false })).kind, "ok");
  assert.equal(c.sample(sec(undefined)).kind, "ok");
  assert.equal(c.sample(sec(0)).kind, "ok");
  assert.equal(c.sample(sec(A.CLOCK_MAX + 1)).kind, "ok");
  const v = c.sample(sec(1030));
  assert.equal(v.kind, "clock");
  assert.equal(v.impliedRate, 49440); // 3 % off 48 kHz isn't a standard rate
});

test("WebAudioCheck: 3+ gaps a second for 3 s with the clock fine", () => {
  const c = new A.WebAudioCheck(48000);
  // Not before a clock sample said the clock is fine.
  assert.equal(c.sample(sec(undefined, { level: music(6) })).kind, "pending");
  assert.equal(c.sample(sec(undefined, { level: music(6) })).kind, "pending");
  assert.equal(c.sample(sec(undefined, { level: music(6) })).kind, "pending");
  const v = c.sample(sec(1000, { level: music(9) }));
  assert.equal(v.kind, "gaps");
  assert.equal(v.gapsPerSecond, 7); // the last 3 seconds: 6, 6, 9
});

test("WebAudioCheck: gaps below the rate, while it shouldn't sound, or in sparse material don't trip", () => {
  const c = new A.WebAudioCheck(48000);
  c.sample(sec(1000));
  // Two seconds with gaps, then one clean second starts over.
  c.sample(sec(1000, { level: music(A.GAP_RATE) }));
  c.sample(sec(1000, { level: music(A.GAP_RATE) }));
  assert.equal(c.sample(sec(1000, { level: music(A.GAP_RATE - 1) })).kind, "pending");
  assert.equal(c.sample(sec(1000)).kind, "ok"); // 5 clean clock samples, no run of gaps going on
  // Two seconds with gaps again: still "ok" (not 3 in a row), a third one trips.
  assert.equal(c.sample(sec(1000, { level: music(4) })).kind, "ok");
  assert.equal(c.sample(sec(1000, { level: music(4) })).kind, "ok");
  // Paused / seeking / not running, or too few windows: neither count nor reset.
  assert.equal(c.sample(sec(1000, { shouldSound: false, level: music(9) })).kind, "ok");
  assert.equal(c.sample(sec(1000, { level: { ...music(9), windows: A.MIN_WINDOWS_PER_SECOND - 1 } })).kind, "ok");
  assert.equal(c.sample(sec(1000, { running: false, level: music(9) })).kind, "ok");
  assert.equal(c.sample(sec(1000, { level: music(4) })).kind, "gaps");
  // A click track: "gaps" next to every click, but nearly every window is silent.
  const d = new A.WebAudioCheck(48000);
  for (let i = 0; i < 10; i++) assert.notEqual(d.sample(sec(1000, { level: music(4, 21) })).kind, "gaps");
  // At the silent-share limit it still counts.
  const e = new A.WebAudioCheck(48000);
  e.sample(sec(1000, { level: music(4, 5) }));
  e.sample(sec(1000, { level: music(4, 5) }));
  assert.equal(e.sample(sec(1000, { level: music(4, 5) })).kind, "gaps");
});

// --- The "Web Audio" setting ---

test("parseWebAudioSetting and webAudioOverride", () => {
  assert.equal(A.parseWebAudioSetting(null), "auto");
  assert.equal(A.parseWebAudioSetting("on"), "on");
  assert.equal(A.parseWebAudioSetting("off"), "off");
  assert.equal(A.parseWebAudioSetting("bogus"), "auto");
  assert.equal(A.webAudioOverride(null), undefined);
  assert.equal(A.webAudioOverride(""), undefined);
  assert.equal(A.webAudioOverride("maybe"), undefined);
  for (const v of ["0", "off", "false", "no", " OFF "]) assert.equal(A.webAudioOverride(v), false, v);
  for (const v of ["1", "on", "true", "yes"]) assert.equal(A.webAudioOverride(v), true, v);
});

test("parseWebAudioMemory: valid, expired, malformed", () => {
  const now = 1_800_000_000_000;
  assert.deepEqual(A.parseWebAudioMemory(JSON.stringify({ rate: 48000, reason: "clock", at: now - 1000 }), now),
    { rate: 48000, reason: "clock", at: now - 1000 });
  assert.deepEqual(A.parseWebAudioMemory(JSON.stringify({ direct: true, at: now }), now), { direct: true, at: now });
  assert.equal(A.parseWebAudioMemory(JSON.stringify({ direct: true, at: now - A.MEMORY_MAX_AGE_MS - 1 }), now), undefined);
  assert.equal(A.parseWebAudioMemory(JSON.stringify({ at: now }), now), undefined); // nothing learnt
  assert.equal(A.parseWebAudioMemory(JSON.stringify({ rate: 5, at: now }), now), undefined);
  assert.equal(A.parseWebAudioMemory("{", now), undefined);
  assert.equal(A.parseWebAudioMemory("null", now), undefined);
  assert.equal(A.parseWebAudioMemory(null, now), undefined);
});

test("webAudioPlan: URL over setting over Auto's memory", () => {
  const direct = { direct: true, rate: 48000, at: 1 };
  const rate = { rate: 48000, at: 1 };
  // Default: Auto, Web Audio, may fall back.
  assert.deepEqual(A.webAudioPlan(undefined, "auto", undefined), { webAudio: true, autoFallback: true, why: "default", volumeSettable: false });

  // Auto with memory.
  assert.deepEqual(A.webAudioPlan(undefined, "auto", direct), { webAudio: false, autoFallback: false, why: "remembered", volumeSettable: false });
  assert.deepEqual(A.webAudioPlan(undefined, "auto", rate),
    { webAudio: true, sampleRate: 48000, autoFallback: true, why: "remembered", volumeSettable: false });
  // On: Web Audio even if Auto learnt "direct" (the rate it learnt is used), never falls back.
  assert.deepEqual(A.webAudioPlan(undefined, "on", direct), { webAudio: true, sampleRate: 48000, autoFallback: false, why: "setting", volumeSettable: false });
  assert.deepEqual(A.webAudioPlan(undefined, "on", undefined), { webAudio: true, autoFallback: false, why: "setting", volumeSettable: false });
  // Off.
  assert.deepEqual(A.webAudioPlan(undefined, "off", rate), { webAudio: false, autoFallback: false, why: "setting", volumeSettable: false });
  // ?webaudio=0/1 overrides everything.
  for (const setting of ["auto", "on", "off"]) {
    assert.deepEqual(A.webAudioPlan(false, setting, rate), { webAudio: false, autoFallback: false, why: "url", volumeSettable: false });
    assert.equal(A.webAudioPlan(true, setting, direct).webAudio, true);
    assert.equal(A.webAudioPlan(true, setting, direct).autoFallback, false);
  }
});

test("webAudioPlan: Auto plays direct where the volume can be set; On, Off and the URL still force a path", () => {
  const direct = { direct: true, at: 1 };
  const rate = { rate: 48000, at: 1 };
  // Desktop Safari, Chrome, Firefox, Android: Auto is direct, whatever it learnt before.
  for (const memory of [undefined, direct, rate]) {
    assert.deepEqual(A.webAudioPlan(undefined, "auto", memory, true),
      { webAudio: false, autoFallback: false, why: "volume", volumeSettable: true });
  }
  // iOS (volume read-only): Auto is Web Audio with the fallback, as before.
  assert.deepEqual(A.webAudioPlan(undefined, "auto", undefined, false),
    { webAudio: true, autoFallback: true, why: "default", volumeSettable: false });
  assert.equal(A.webAudioPlan(undefined, "auto", direct, false).why, "remembered");
  // On / ?webaudio=1 force Web Audio, Off / ?webaudio=0 force direct.
  assert.deepEqual(A.webAudioPlan(undefined, "on", undefined, true),
    { webAudio: true, autoFallback: false, why: "setting", volumeSettable: true });
  assert.equal(A.webAudioPlan(true, "auto", undefined, true).webAudio, true);
  assert.equal(A.webAudioPlan(true, "auto", undefined, true).why, "url");
  assert.equal(A.webAudioPlan(undefined, "off", undefined, true).why, "setting");
  assert.equal(A.webAudioPlan(false, "on", undefined, true).why, "url");
});

test("volumeSettable: reads the volume back after setting 0.5, and restores it", () => {
  // Desktop: the write sticks.
  const desktop = { volume: 0.8 };
  assert.equal(A.volumeSettable(desktop), true);
  assert.equal(desktop.volume, 0.8);
  // iOS: the write is ignored, reading always gives 1.
  const ios = {};
  Object.defineProperty(ios, "volume", { get: () => 1, set: () => undefined });
  assert.equal(A.volumeSettable(ios), false);
  // A throwing setter is not settable.
  const throwing = {};
  Object.defineProperty(throwing, "volume", { get: () => 1, set: () => { throw new Error("read-only"); } });
  assert.equal(A.volumeSettable(throwing), false);
  // A setter that rounds coarsely still counts if 0.5 reads back as 0.5.
  let v = 1;
  const rounding = { get volume() { return v; }, set volume(x) { v = Math.round(x * 100) / 100; } };
  assert.equal(A.volumeSettable(rounding), true);
  assert.equal(v, 1);
});

test("WebAudioCheck on the first health samples of report uhmxlu (iPod touch 7) trips within 3 s of running", () => {
  // [ck, ctx running, out of the playing element: windows, gaps, silent]
  const h = [[undefined, false], [0, false], [0, false], [96, true, [3, 0, 3]], [1090, true, [24, 0, 24]],
    [1087, true, [13, 7, 0]], [1090, true, [24, 0, 24]], [1088, true, [24, 8, 4]], [1084, true, [25, 10, 2]]];
  const c = new A.WebAudioCheck(44100);
  const kinds = h.map(([ck, running, o]) => c.sample({ ck, running, shouldSound: running,
    level: o === undefined ? undefined : { windows: o[0], peak: 0.2, rms: 0.03, gaps: o[1], silent: o[2] } }).kind);
  assert.deepEqual(kinds, ["pending", "pending", "pending", "pending", "pending", "pending", "clock", "clock", "clock"]);
  assert.equal(c.verdict.impliedRate, 48000);
});
