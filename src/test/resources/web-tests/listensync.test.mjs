// Unit tests for web/src/listensync.ts (compiled), run with Node's test runner by ListenJsTest.
// The module path comes in LISTENSYNC_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const L = await import(pathToFileURL(process.env.LISTENSYNC_JS).href);

test("rate mode: hard seek above 750 ms, a fixed nudge above 40 ms, steady below", () => {
  const c = () => new L.DriftController("rate");
  assert.deepEqual(c().decide(0, 751), { kind: "seek" });
  assert.deepEqual(c().decide(0, -751), { kind: "seek" });
  assert.deepEqual(c().decide(0, 750), { kind: "rate", rate: 1 - L.RATE_STEP_FAST });
  assert.deepEqual(c().decide(0, -750), { kind: "rate", rate: 1 + L.RATE_STEP_FAST });
  assert.deepEqual(c().decide(0, 300), { kind: "rate", rate: 1 - L.RATE_STEP });
  assert.deepEqual(c().decide(0, 41), { kind: "rate", rate: 0.97 });
  assert.deepEqual(c().decide(0, -60), { kind: "rate", rate: 1.03 });
  assert.deepEqual(c().decide(0, 40), { kind: "rate", rate: 1 });
  assert.deepEqual(c().decide(0, -40), { kind: "rate", rate: 1 });
  assert.deepEqual(c().decide(0, 0), { kind: "rate", rate: 1 });
  assert.deepEqual(c().decide(0, NaN), { kind: "rate", rate: 1 }); // never a NaN rate or a seek to NaN
});

test("rate mode: a nudge holds its rate until within 10 ms or past zero (hysteresis)", () => {
  const c = new L.DriftController("rate");
  assert.equal(c.decide(0, 120).rate, 0.97); // ahead: slower
  assert.equal(c.decide(250, 90).rate, 0.97);
  assert.equal(c.decide(500, 35).rate, 0.97); // inside the 40 ms band but not yet within 10: keep going
  assert.equal(c.decide(750, 11).rate, 0.97);
  assert.equal(c.decide(1000, 10).rate, 1);
  assert.equal(c.decide(1250, 39).rate, 1); // back at 1 until the drift leaves the 40 ms band again
  assert.equal(c.decide(1500, -45).rate, 1.03); // behind: faster
  assert.equal(c.decide(1750, -400).rate, 1.03); // no change of step mid-nudge
  assert.equal(c.decide(2000, 5).rate, 1); // overshot past zero: stop
  // A fast nudge also holds until the end.
  const f = new L.DriftController("rate");
  assert.equal(f.decide(0, -500).rate, 1.05);
  assert.equal(f.decide(250, -100).rate, 1.05);
  assert.equal(f.decide(500, -9).rate, 1);
});

test("rate mode: a seek and reset() end a nudge", () => {
  const c = new L.DriftController("rate");
  c.decide(0, 100);
  assert.equal(c.rate, 0.97);
  assert.deepEqual(c.decide(250, 800), { kind: "seek" });
  assert.equal(c.rate, 1);
  c.decide(500, 100);
  c.reset();
  assert.equal(c.rate, 1);
});

test("seek mode: never a rate change; seeks above 60 ms at most once per cooldown, hard seeks at once", () => {
  const c = new L.DriftController("seek");
  assert.deepEqual(c.decide(0, 59), { kind: "rate", rate: 1 });
  assert.deepEqual(c.decide(0, -60), { kind: "rate", rate: 1 });
  assert.deepEqual(c.decide(0, 61), { kind: "seek" });
  assert.deepEqual(c.decide(1000, 200), { kind: "rate", rate: 1 }); // cooling down
  assert.deepEqual(c.decide(L.SEEK_MODE_COOLDOWN_MS, 200), { kind: "seek" });
  assert.deepEqual(c.decide(L.SEEK_MODE_COOLDOWN_MS + 10, 751), { kind: "seek" }); // hard: no cooldown
  for (const d of [45, -45, 300, -700]) {
    const a = new L.DriftController("seek").decide(0, d);
    assert.ok(a.kind === "seek" || a.rate === 1, String(d));
  }
});

test("seek mode: seeks that miss double the cooldown (up to 30 s), a landing in the band resets it", () => {
  const c = new L.DriftController("seek");
  assert.equal(c.cooldownMs, 2500);
  c.landed(100);
  assert.equal(c.cooldownMs, 5000);
  c.landed(-90);
  assert.equal(c.cooldownMs, 10_000);
  c.landed(80);
  c.landed(80);
  c.landed(80);
  assert.equal(c.cooldownMs, L.SEEK_MODE_MAX_COOLDOWN_MS);
  c.landed(20);
  assert.equal(c.cooldownMs, 2500);
  c.landed(100);
  c.reset();
  assert.equal(c.cooldownMs, 2500);
  // rate mode ignores landings
  const r = new L.DriftController("rate");
  r.landed(500);
  assert.equal(r.cooldownMs, 2500);
});

test("a seek that landed more than 150 ms off is followed by another at once, at most twice in a row", () => {
  for (const mode of ["rate", "seek"]) {
    const c = new L.DriftController(mode);
    assert.deepEqual(c.decide(0, 2000), { kind: "seek" });
    c.landed(-400);
    assert.deepEqual(c.decide(250, -400), { kind: "seek" }, mode); // no nudge, no cooldown
    c.landed(-200);
    assert.deepEqual(c.decide(500, -200), { kind: "seek" }, mode);
    c.landed(-200);
    assert.notDeepEqual(c.decide(750, -200), { kind: "seek" }, mode); // a third in a row: no
    c.landed(-30); // a good landing resets the count
    assert.equal(c.decide(1000, -30).kind, "rate");
    c.landed(300);
    assert.deepEqual(c.decide(L.SEEK_MODE_MAX_COOLDOWN_MS, 300), { kind: "seek" }, mode);
    // Only on the tick right after the landing, and not for a landing within 150 ms.
    const d = new L.DriftController(mode);
    d.landed(-120);
    assert.equal(d.decide(0, -120).kind, mode === "seek" ? "seek" : "rate"); // seek mode: > 60 ms, no cooldown yet
  }
});

test("sync mode: by engine features, not the user agent", () => {
  const h = (o) => ({ preservesPitch: false, webkitPreservesPitch: false, mozPreservesPitch: false, webkitPlaybackTarget: false, ...o });
  // Safari 15 (iPhone X, iOS 15.1): only the prefixed pitch flag, and AirPlay
  assert.equal(L.syncModeFor(h({ webkitPreservesPitch: true, webkitPlaybackTarget: true })), "seek");
  // newer Safari (Mac, iPadOS 26): both pitch flags, and AirPlay
  assert.equal(L.syncModeFor(h({ preservesPitch: true, webkitPreservesPitch: true, webkitPlaybackTarget: true })), "seek");
  // a WebKit without AirPlay still has only the prefixed flag
  assert.equal(L.syncModeFor(h({ webkitPreservesPitch: true })), "seek");
  // Chromium (also with the old prefixed flag), Gecko (Firefox, and Reynard on iOS that says it is Android)
  assert.equal(L.syncModeFor(h({ preservesPitch: true })), "rate");
  assert.equal(L.syncModeFor(h({ preservesPitch: true, webkitPreservesPitch: true })), "rate");
  assert.equal(L.syncModeFor(h({ preservesPitch: true, mozPreservesPitch: true })), "rate");
  assert.equal(L.syncModeFor(h({ webkitPreservesPitch: true, mozPreservesPitch: true })), "rate");
  // overrides
  assert.equal(L.syncModeFor(h({ webkitPlaybackTarget: true }), "rate"), "rate");
  assert.equal(L.syncModeFor(h({ preservesPitch: true }), "seek"), "seek");
  assert.equal(L.syncModeFor(h({ preservesPitch: true }), "bogus"), "rate");
  assert.equal(L.syncModeFor(h({}), null), "rate");
});

test("seek lead: learnt from where seeks landed, first as is, then halfway; lost seeks ignored", () => {
  const lead = new L.SeekLead();
  assert.equal(lead.ms, 0);
  assert.equal(lead.landed(-400), true); // aimed at the target, landed 400 ms behind
  assert.equal(lead.ms, 400);
  assert.equal(lead.landed(-100), true); // aimed 400 ahead, still 100 behind
  assert.equal(lead.ms, 450);
  assert.equal(lead.landed(60), true); // now 60 ahead
  assert.equal(lead.ms, 420);
  assert.equal(lead.landed(-30_000), false); // the seek was dropped (iOS reset it to 0): not a latency
  assert.equal(lead.landed(NaN), false);
  assert.equal(lead.ms, 420);
  assert.equal(lead.samples, 3);
  const big = new L.SeekLead();
  big.landed(-1500);
  big.landed(-1500);
  assert.equal(big.ms, L.MAX_LEAD_MS);
  const neg = new L.SeekLead();
  neg.landed(1000);
  assert.equal(neg.ms, L.MIN_LEAD_MS);
});

/**
 * A simulated element: it plays at its rate, and a seek takes `seekMs` (during which the element
 * is stopped), then lands where it was asked to go. Driven like the player: a tick every 250 ms,
 * skipped while seeking, landing measured on the first tick after a seek.
 */
function simulate(mode, { startDriftMs, seekMs, seconds, clockSkewPpm = 0 }) {
  const ctl = new L.DriftController(mode);
  const lead = new L.SeekLead();
  let pos = startDriftMs; // element position, ms; the timeline is at `now`
  let rate = 1;
  let seekingUntil = -1;
  let seekTo = 0;
  let landing = false;
  const rates = new Set([1]);
  let rateChanges = 0;
  let seeks = 0;
  const drifts = [];
  const step = 10;
  for (let now = 0; now <= seconds * 1000; now += step) {
    if (now < seekingUntil) continue;
    if (seekingUntil >= 0 && now >= seekingUntil) {
      pos = seekTo;
      seekingUntil = -1;
    } else {
      pos += step * rate * (1 + clockSkewPpm / 1e6);
    }
    if (now % 250 !== 0) continue;
    const drift = pos - now;
    drifts.push({ now, drift });
    if (landing) {
      landing = false;
      lead.landed(drift);
      ctl.landed(drift);
    }
    const a = ctl.decide(now, drift);
    if (a.kind === "seek") {
      seeks++;
      rate = 1;
      seekTo = now + lead.ms;
      seekingUntil = now + seekMs;
      landing = true;
    } else if (a.rate !== rate) {
      rate = a.rate;
      rates.add(rate);
      rateChanges++;
    }
  }
  return { rates, rateChanges, seeks, drifts, lead: lead.ms };
}

test("simulation, seek mode (Safari-like 400 ms seeks): lands within 60 ms after a few seeks, rate always 1", () => {
  for (const start of [-5000, -900, 200, 1200]) {
    const r = simulate("seek", { startDriftMs: start, seekMs: 400, seconds: 60, clockSkewPpm: 300 });
    assert.deepEqual([...r.rates], [1]);
    const late = r.drifts.filter((d) => d.now >= 15_000);
    assert.ok(late.every((d) => Math.abs(d.drift) <= L.SEEK_MODE_MS + 20), JSON.stringify(late.slice(0, 5)));
    assert.ok(r.seeks <= 6, "seeks " + r.seeks);
    assert.ok(Math.abs(r.lead - 400) <= 20, "lead " + r.lead);
  }
});

test("simulation, rate mode (Chrome-like 20 ms seeks): converges with only a few rate changes", () => {
  for (const start of [-600, -100, 80, 300, 2000]) {
    const r = simulate("rate", { startDriftMs: start, seekMs: 20, seconds: 60, clockSkewPpm: -200 });
    for (const x of r.rates) assert.ok([1, 0.97, 1.03, 0.95, 1.05].includes(x), String(x));
    const late = r.drifts.filter((d) => d.now >= 30_000);
    assert.ok(late.every((d) => Math.abs(d.drift) <= L.STEADY_MS + 5), "start " + start);
    assert.ok(r.rateChanges <= 12, "rate changes " + r.rateChanges + " from " + start);
  }
});

test("simulation, rate mode with Safari-like seeks: the lead keeps a hard seek from landing late", () => {
  const r = simulate("rate", { startDriftMs: -3000, seekMs: 500, seconds: 20 });
  // Without the lead the first hard seek lands 500 ms late and a 5 % nudge takes 10 s to recover;
  // with it the second one lands on time.
  const after = r.drifts.filter((d) => d.now >= 3000);
  assert.ok(after.every((d) => Math.abs(d.drift) <= 60), JSON.stringify(after.slice(0, 4)));
});

test("dB to linear gain", () => {
  assert.equal(L.dbToGain(0), 1);
  assert.equal(L.dbToGain(undefined), 1);
  assert.equal(L.dbToGain(null), 1);
  assert.equal(L.dbToGain(NaN), 1);
  assert.ok(Math.abs(L.dbToGain(-6.0206) - 0.5) < 1e-4);
  assert.ok(Math.abs(L.dbToGain(20) - 10) < 1e-9);
});

const audioItem = {
  kind: "audio", presentAsAudio: true, audioUrl: "/media/a/audio.m4a",
  originalUrl: "/media/a/original", originalGainDb: -3.2, originalType: "audio/flac",
};
const yes = () => true;
const no = () => false;

test("audio item with a playable original plays the original with its gain", () => {
  assert.deepEqual(L.chooseSource(audioItem, yes, false),
    { url: "/media/a/original", gainDb: -3.2, video: false, original: true });
});

test("canPlayType saying no, or a given-up original, falls back to audio.m4a", () => {
  const fallback = { url: "/media/a/audio.m4a", gainDb: 0, video: false, original: false };
  assert.deepEqual(L.chooseSource(audioItem, no, false), fallback);
  assert.deepEqual(L.chooseSource(audioItem, yes, true), fallback);
  let asked;
  L.chooseSource(audioItem, (t) => { asked = t; return true; }, false);
  assert.equal(asked, "audio/flac");
});

test("no original offered (not web-playable) → audio.m4a; no type → trust the server", () => {
  const { originalUrl, originalGainDb, originalType, ...plain } = audioItem;
  assert.equal(L.chooseSource(plain, yes, false).url, "/media/a/audio.m4a");
  const { originalType: _t, ...untyped } = audioItem;
  assert.equal(L.chooseSource(untyped, no, false).url, "/media/a/original");
  const noGain = { ...audioItem, originalGainDb: undefined };
  assert.equal(L.chooseSource(noGain, yes, false).gainDb, 0);
});

test("video items: mezz.mp4 in a video element, presentAsAudio → audio.m4a", () => {
  const video = { kind: "video", presentAsAudio: false, audioUrl: "/media/v/audio.m4a", videoUrl: "/media/v/mezz.mp4" };
  assert.deepEqual(L.chooseSource(video, yes, false), { url: "/media/v/mezz.mp4", gainDb: 0, video: true, original: false });
  assert.deepEqual(L.chooseSource({ ...video, presentAsAudio: true }, yes, false),
    { url: "/media/v/audio.m4a", gainDb: 0, video: false, original: false });
});

test("an item that isn't ready has no source", () => {
  assert.equal(L.chooseSource({ kind: "audio" }, yes, false), undefined);
});

test("preload: the first ready queue entry, about 10 s before the end", () => {
  const e = (id, status, itemStatus = status) => ({ entryId: id, status, item: { id: "i" + id, status: itemStatus } });
  const state = { current: e("c", "ready"), queue: [e("p", "processing"), e("f", "failed"), e("n", "ready"), e("m", "ready")] };
  assert.equal(L.nextToPreload(state).entryId, "n");
  assert.equal(L.nextToPreload({ current: undefined, queue: [] }), undefined);
  assert.equal(L.nextToPreload({ current: undefined, queue: [e("p", "processing")] }), undefined);
  assert.equal(L.shouldPreload(10_000), true);
  assert.equal(L.shouldPreload(9_000), true);
  assert.equal(L.shouldPreload(10_001), false);
});

test("an original is given up after two hard seeks within 10 s didn't hold", () => {
  const g = new L.SeekGuard();
  assert.equal(g.hardSeek(1000), true);
  assert.equal(g.hardSeek(5000), true);
  assert.equal(g.hardSeek(9000), false); // a third within 10 s of the first two
  const h = new L.SeekGuard();
  assert.equal(h.hardSeek(1000), true);
  assert.equal(h.hardSeek(5000), true);
  assert.equal(h.hardSeek(11_000), true); // the first is older than 10 s
  assert.equal(h.hardSeek(14_000), false);
});

test("re-anchoring resets the count (a timeline seek isn't the file's fault)", () => {
  const g = new L.SeekGuard();
  g.hardSeek(1000);
  g.hardSeek(2000);
  g.reset();
  assert.equal(g.hardSeek(3000), true);
});

test("audio-only mode: video items play audio.m4a, never mezz.mp4; audio items unchanged", () => {
  const video = { kind: "video", presentAsAudio: false, audioUrl: "/media/v/audio.m4a", videoUrl: "/media/v/mezz.mp4" };
  assert.deepEqual(L.chooseSource(video, yes, false, true), { url: "/media/v/audio.m4a", gainDb: 0, video: false, original: false });
  assert.equal(L.chooseSource(video, yes, false, false).url, "/media/v/mezz.mp4");
  assert.deepEqual(L.chooseSource(audioItem, yes, false, true),
    { url: "/media/a/original", gainDb: -3.2, video: false, original: true });
});

test("switch gate: crossfade after 3 steady ticks at least 200 ms apart", () => {
  const g = new L.SwitchGate(0);
  assert.equal(g.sample(100, undefined), false); // loading
  assert.equal(g.sample(250, 10), false);
  assert.equal(g.sample(300, 5), false); // too soon after the last: not counted
  assert.equal(g.sample(500, -20), false);
  assert.equal(g.sample(750, 40), true);
});

test("switch gate: drift above 40 ms or not playing restarts the count", () => {
  const g = new L.SwitchGate(0);
  g.sample(250, 10);
  g.sample(500, 10);
  assert.equal(g.sample(750, 41), false);
  assert.equal(g.sample(1000, 10), false);
  assert.equal(g.sample(1250, undefined), false);
  assert.equal(g.sample(1500, 10), false);
  assert.equal(g.sample(1750, 10), false);
  assert.equal(g.sample(2000, 10), true);
});

test("switch gate: seek mode counts drift within its 60 ms band as steady", () => {
  const g = new L.SwitchGate(0, L.SEEK_MODE_MS);
  assert.equal(g.sample(250, 55), false);
  assert.equal(g.sample(500, -60), false);
  assert.equal(g.sample(750, 50), true);
  const r = new L.SwitchGate(0);
  assert.equal(r.sample(250, 55), false);
  assert.equal(r.sample(500, 55), false);
  assert.equal(r.sample(750, 55), false);
});

test("switch gate: after 3 s it crossfades if the element plays, never while it doesn't", () => {
  const g = new L.SwitchGate(1000);
  assert.equal(g.sample(3999, 300), false);
  assert.equal(g.sample(4000, undefined), false);
  assert.equal(g.sample(4250, 300), true);
  assert.equal(L.CROSSFADE_MS, 150);
});
