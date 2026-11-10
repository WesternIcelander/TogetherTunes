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

test("seek mode: only scattered seeks back off (up to 8 s); a systematic miss keeps the normal cooldown", () => {
  const c = new L.DriftController("seek");
  assert.equal(c.cooldownMs, 2500);
  // The same delay every time (the lead is just wrong): no backoff, however often it misses.
  for (let i = 0; i < 6; i++) c.landed(-104, { delayMs: 270 });
  assert.equal(c.cooldownMs, 2500);
  // Delays that scatter by more than 100 ms: doubling, capped at 8 s.
  c.landed(-200, { delayMs: 500 });
  assert.equal(c.cooldownMs, 5000);
  c.landed(150, { delayMs: 120 });
  assert.equal(c.cooldownMs, L.SEEK_MODE_MAX_COOLDOWN_MS);
  assert.equal(L.SEEK_MODE_MAX_COOLDOWN_MS, 8000);
  c.landed(-90, { delayMs: 450 });
  assert.equal(c.cooldownMs, 8000);
  // A landing in the band resets it, even with scattered history.
  c.landed(20, { delayMs: 300 });
  assert.equal(c.cooldownMs, 2500);
  // A drift that never came to rest counts as scatter.
  const u = new L.DriftController("seek");
  u.landed(100, { stable: false });
  assert.equal(u.cooldownMs, 5000);
  u.reset();
  assert.equal(u.cooldownMs, 2500);
  // rate mode ignores landings
  const r = new L.DriftController("rate");
  r.landed(500, { stable: false });
  assert.equal(r.cooldownMs, 2500);
});

test("seek mode: after a landing in the band the seek threshold widens to 80 ms until a miss or reset", () => {
  const c = new L.DriftController("seek");
  assert.equal(c.seekBandMs, 60);
  c.landed(12, { delayMs: 320 });
  assert.equal(c.converged, true);
  assert.equal(c.seekBandMs, L.SEEK_MODE_CONVERGED_MS);
  assert.deepEqual(c.decide(10_000, 79), { kind: "rate", rate: 1 });
  assert.deepEqual(c.decide(10_000, -80), { kind: "rate", rate: 1 });
  assert.deepEqual(c.decide(10_000, 81), { kind: "seek" });
  c.landed(-70, { delayMs: 390 }); // missed the 60 ms band: back to 60
  assert.equal(c.seekBandMs, 60);
  c.landed(5, { stable: false }); // not at rest: not converged
  assert.equal(c.converged, false);
  c.landed(5);
  c.reset();
  assert.equal(c.seekBandMs, 60);
});

test("seek landing: judged on the settled drift 0.8 s+ after seeked, not at seeked", () => {
  const l = new L.SeekLanding(0, "correct", 40, true);
  assert.equal(l.seekedAt, undefined);
  // seeked after 90 ms, landed "exactly"; then the clock stands still for 300 ms (iPad diagnostics).
  assert.deepEqual(l.sample(90, 2), { kind: "settling" });
  assert.equal(l.seekedAt, 90);
  assert.deepEqual(l.sample(250, -100), { kind: "settling" });
  assert.deepEqual(l.sample(500, -300), { kind: "settling" });
  assert.deepEqual(l.sample(750, -316), { kind: "settling" });
  assert.deepEqual(l.sample(900, -316), { kind: "settling" }); // first settled sample
  assert.deepEqual(l.sample(950, -500), { kind: "settling" }); // too close to the last: not counted
  assert.deepEqual(l.sample(1150, -317), { kind: "settling" });
  assert.deepEqual(l.sample(1400, -316),
    { kind: "settled", driftMs: -316, stable: true, seekedDriftMs: 2, afterMs: 1310 });
  // Never at rest: the median of the last three after 3 s.
  const m = new L.SeekLanding(0, "correct", 0, true);
  m.sample(100, 0);
  let r;
  for (let t = 1000, d = -100; t <= 3600; t += 250, d -= 40) {
    r = m.sample(t, d);
    if (r.kind !== "settling") break;
  }
  assert.equal(r.kind, "settled");
  assert.equal(r.stable, false);
  assert.ok(r.afterMs >= L.SETTLE_MAX_MS);
  // Far off: lost (dropped seek), reported at once; seeked too late: expired.
  assert.deepEqual(new L.SeekLanding(0, "x", 0, true).sample(100, -20_000), { kind: "lost", driftMs: -20_000 });
  const late = new L.SeekLanding(0, "x", 0, true);
  late.sample(100, 0);
  assert.deepEqual(late.sample(600, 800), { kind: "lost", driftMs: 800 });
  assert.deepEqual(new L.SeekLanding(0, "x", 0, true).sample(L.LANDING_MAX_AGE_MS + 1, 0), { kind: "expired" });
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

test("seek lead: an unbuffered seek's landing is a first guess only, replaced by the first real sample", () => {
  const lead = new L.SeekLead();
  assert.equal(lead.guess(-313), true); // a slow seek to unbuffered data settled 313 ms behind
  assert.equal(lead.ms, 313);
  assert.equal(lead.samples, 0);
  assert.equal(lead.landed(-10), true); // aimed 313 ahead, 10 behind: the whole estimate is replaced
  assert.equal(lead.ms, 323);
  assert.equal(lead.guess(-900), false); // once measured, guesses are ignored
  assert.equal(lead.ms, 323);
  assert.equal(new L.SeekLead().guess(-20_000), false); // a lost seek isn't a guess
});

/**
 * A simulated element, driven like the player (listen.ts): a seek takes `seekMs` (the element is
 * stopped and ticks skip it), then reports `seeked` at the asked position (a tick runs right then),
 * and then, like WebKit, keeps its clock still for `freezeMs` more (`freezeJitterMs`: ± that,
 * random but repeatable) before playing on at its rate. Ticks every 250 ms; a seek's landing is
 * measured with SeekLanding and teaches the lead when `buffered(now)` says the target was buffered.
 * `pushes`: [time, ms] the element jumps by (e.g. an outside stall), to make it drift.
 */
function simulate(mode, { startDriftMs, seekMs, seconds, clockSkewPpm = 0, freezeMs = 0, freezeJitterMs = 0,
    buffered = () => true, pushes = [], seekMsAt = () => seekMs }) {
  const ctl = new L.DriftController(mode);
  const lead = new L.SeekLead();
  let seed = 12345;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  let pos = startDriftMs; // element position, ms; the timeline is at `now`
  let rate = 1;
  let seekingUntil = -1;
  let frozenUntil = -1;
  let seekTo = 0;
  let landing;
  const rates = new Set([1]);
  let rateChanges = 0;
  const seekTimes = [];
  const drifts = [];
  const cooldowns = [];
  const landings = [];
  const step = 10;
  const pending = pushes.slice();
  for (let now = 0; now <= seconds * 1000; now += step) {
    let seekedNow = false;
    if (seekingUntil >= 0) {
      if (now < seekingUntil) continue;
      pos = seekTo;
      seekingUntil = -1;
      seekedNow = true;
      frozenUntil = now + Math.max(0, freezeMs + (freezeJitterMs ? (rnd() * 2 - 1) * freezeJitterMs : 0));
    } else if (now >= frozenUntil) {
      pos += step * rate * (1 + clockSkewPpm / 1e6);
    }
    while (pending.length > 0 && pending[0][0] <= now) pos += pending.shift()[1];
    if (now % 250 !== 0 && !seekedNow) continue;
    const drift = pos - now;
    drifts.push({ now, drift });
    if (landing !== undefined) {
      const r = landing.sample(now, drift);
      if (r.kind === "settling") continue;
      if (r.kind === "settled") {
        const used = landing.learn && lead.landed(r.driftMs);
        if (!landing.learn) lead.guess(r.driftMs);
        ctl.landed(r.driftMs, { stable: r.stable, delayMs: used ? landing.leadMs - r.driftMs : undefined });
        landings.push({ now, ...r });
      }
      landing = undefined;
    }
    const a = ctl.decide(now, drift);
    cooldowns.push(ctl.cooldownMs);
    if (a.kind === "seek") {
      seekTimes.push(now);
      rate = 1;
      seekTo = now + lead.ms;
      seekingUntil = now + seekMsAt(now);
      landing = new L.SeekLanding(now, "sim", lead.ms, buffered(now));
    } else if (a.rate !== rate) {
      rate = a.rate;
      rates.add(rate);
      rateChanges++;
    }
  }
  return { rates, rateChanges, seeks: seekTimes.length, seekTimes, drifts, lead: lead.ms, cooldowns, landings };
}

test("simulation, seek mode (Safari-like 400 ms seeks): lands within 60 ms after a few seeks, rate always 1", () => {
  for (const start of [-5000, -900, 200, 1200]) {
    const r = simulate("seek", { startDriftMs: start, seekMs: 400, seconds: 60, clockSkewPpm: 300 });
    assert.deepEqual([...r.rates], [1]);
    const late = r.drifts.filter((d) => d.now >= 15_000);
    assert.ok(late.every((d) => Math.abs(d.drift) <= L.SEEK_MODE_CONVERGED_MS + 20), JSON.stringify(late.slice(0, 5)));
    assert.ok(r.seeks <= 6, "seeks " + r.seeks);
    assert.ok(Math.abs(r.lead - 400) <= 20, "lead " + r.lead);
  }
});

test("simulation, WebKit (clock still ~300 ms after seeked, as in the iPad/iPhone reports): converges in ≤ 2 seeks", () => {
  // The iPad report: drift −319, correction seeks of 73–103 ms, then ~300 ms frozen; the iPhone:
  // −174, seeks ~60–90 ms, 280–320 ms frozen.
  // The iPod touch 7 (f552z8): seeks landed at `seeked` within ~25 ms after 75–80 ms, then ~375 ms
  // frozen (seeked +359, settled −16); the old code then parked it at +374 for over 20 s and
  // started a new entry at −434.
  for (const [start, seekMs, freezeMs] of [[-319, 90, 310], [-174, 75, 300], [-104, 75, 280], [210, 90, 330],
      [-500, 100, 300], [374, 75, 375], [-434, 80, 370]]) {
    const r = simulate("seek", { startDriftMs: start, seekMs, freezeMs, freezeJitterMs: 17, seconds: 180 });
    const tag = `start ${start}: ` + JSON.stringify({ seeks: r.seekTimes, lead: r.lead, landings: r.landings });
    assert.deepEqual([...r.rates], [1], tag);
    assert.ok(r.seeks <= 2, tag);
    // In sync from the end of the second seek's settling on, and for the rest of the 3 minutes.
    const after = r.drifts.filter((d) => d.now >= 6000);
    assert.ok(after.every((d) => Math.abs(d.drift) <= L.SEEK_MODE_MS), tag);
    assert.ok(Math.abs(r.lead - (seekMs + freezeMs)) <= 40, tag);
    assert.ok(Math.max(...r.cooldowns) <= L.SEEK_MODE_MAX_COOLDOWN_MS, tag);
  }
});

test("simulation, WebKit: a systematic miss is fixed by the lead, never parked behind a long cooldown", () => {
  // Learnt lead 120 ms short of the real delay (as in the iPhone report, −104 settled): the second
  // seek must come after the normal cooldown and land, not 30 s later.
  const r = simulate("seek", { startDriftMs: -104, seekMs: 75, freezeMs: 300, seconds: 60,
    pushes: [[20_000, -250], [40_000, 180]] });
  const tag = JSON.stringify({ seeks: r.seekTimes, landings: r.landings });
  for (const [t] of [[0], [20_000], [40_000]]) {
    // every disturbance is corrected within ~5 s (one or two seeks), never parked off-sync
    const later = r.drifts.filter((d) => d.now >= t + 5000 && d.now < t + 20_000);
    assert.ok(later.every((d) => Math.abs(d.drift) <= L.SEEK_MODE_MS), tag + " at " + t);
  }
  assert.ok(r.seeks <= 5, tag);
  assert.ok(r.cooldowns.every((c) => c === L.SEEK_MODE_COOLDOWN_MS), tag);
});

test("simulation, WebKit after converging: steady drift and a slow clock skew seek rarely", () => {
  // 50 ppm: 3 ms per minute. After converging, no seek until the drift passes 80 ms, i.e. not
  // within the 10 minutes.
  const r = simulate("seek", { startDriftMs: -319, seekMs: 90, freezeMs: 310, freezeJitterMs: 17, seconds: 600,
    clockSkewPpm: 50 });
  assert.ok(r.seeks <= 2, JSON.stringify(r.seekTimes));
  // 300 ppm (18 ms/min): one correction every few minutes, not every 2.5 s.
  const s = simulate("seek", { startDriftMs: -319, seekMs: 90, freezeMs: 310, freezeJitterMs: 17, seconds: 600,
    clockSkewPpm: 300 });
  assert.ok(s.seeks <= 6, JSON.stringify(s.seekTimes));
  // (apart from the second after each seek, where the reading at `seeked` is ~300 ms ahead)
  const late = s.drifts.filter((d) => d.now >= 6000 && !s.seekTimes.some((t) => d.now >= t && d.now < t + 1000));
  const bad = late.filter((d) => Math.abs(d.drift) > L.SEEK_MODE_CONVERGED_MS + 5);
  assert.deepEqual(bad, [], JSON.stringify({ seeks: s.seekTimes, landings: s.landings }));
});

test("simulation, WebKit: a slow seek to unbuffered data doesn't spoil the lead", () => {
  // As the iPhone's owner seek: the hard seek takes 1375 ms (download), later ones ~75 ms.
  const r = simulate("seek", { startDriftMs: -3000, seekMs: 75, freezeMs: 300, seconds: 60,
    seekMsAt: (now) => (now === 0 ? 1375 : 75), buffered: (now) => now !== 0 });
  const tag = JSON.stringify({ seeks: r.seekTimes, lead: r.lead, landings: r.landings });
  assert.ok(Math.abs(r.lead - 375) <= 30, tag);
  assert.ok(r.seeks <= 3, tag);
  assert.ok(r.drifts.filter((d) => d.now >= 10_000).every((d) => Math.abs(d.drift) <= L.SEEK_MODE_MS), tag);
});

test("simulation, WebKit: a moderately slow first seek to unbuffered data gives a first guess (≤ 2 seeks)", () => {
  const r = simulate("seek", { startDriftMs: -3000, seekMs: 75, freezeMs: 300, seconds: 60,
    seekMsAt: (now) => (now === 0 ? 120 : 75), buffered: (now) => now !== 0 });
  const tag = JSON.stringify({ seeks: r.seekTimes, lead: r.lead, landings: r.landings });
  assert.ok(r.seeks <= 2, tag);
  assert.ok(Math.abs(r.lead - 375) <= 30, tag);
  assert.ok(r.drifts.filter((d) => d.now >= 6000).every((d) => Math.abs(d.drift) <= L.SEEK_MODE_MS), tag);
});

test("simulation, WebKit with scattering seeks: backs off, but never beyond 8 s", () => {
  const r = simulate("seek", { startDriftMs: -300, seekMs: 90, freezeMs: 300, freezeJitterMs: 280, seconds: 120 });
  assert.deepEqual([...r.rates], [1]);
  assert.ok(Math.max(...r.cooldowns) <= L.SEEK_MODE_MAX_COOLDOWN_MS);
  // gaps between seeks never longer than the cap plus the settling time
  for (let i = 1; i < r.seekTimes.length; i++) {
    assert.ok(r.seekTimes[i] - r.seekTimes[i - 1] <= L.SEEK_MODE_MAX_COOLDOWN_MS + L.SETTLE_MAX_MS + 500
      || r.drifts.filter((d) => d.now > r.seekTimes[i - 1] + 4000 && d.now < r.seekTimes[i])
        .every((d) => Math.abs(d.drift) <= L.SEEK_MODE_CONVERGED_MS), JSON.stringify(r.seekTimes));
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

test("end guard: no seek aimed into the last second of an item; unknown end never guards", () => {
  assert.equal(L.END_GUARD_MS, 1000);
  const end = 237_331;
  // f552z8: stuck re-seeks to 231167 and 236179 ms of a 237331 ms item (lead 0): 1.15 s before
  // the end is still a seek; with the ~370 ms lead WebKit learns it would be inside the guard.
  assert.equal(L.inEndGuard(231_167, 0, end), false);
  assert.equal(L.inEndGuard(236_179, 0, end), false);
  assert.equal(L.inEndGuard(236_179, 370, end), true);
  // The lead counts: a seek aimed past end − 1 s would be clamped to the last frames and end at once.
  assert.equal(L.inEndGuard(235_900, 300, end), false);
  assert.equal(L.inEndGuard(236_100, 300, end), true);
  assert.equal(L.inEndGuard(end - 1000, 0, end), true);
  assert.equal(L.inEndGuard(end - 1001, 0, end), false);
  assert.equal(L.inEndGuard(end + 500, 0, end), true);
  // A negative lead doesn't move the guard later; a bad lead counts as 0.
  assert.equal(L.inEndGuard(end - 900, -200, end), true);
  assert.equal(L.inEndGuard(end - 1500, NaN, end), false);
  for (const unknown of [undefined, NaN, Infinity, 0]) {
    assert.equal(L.inEndGuard(10_000_000, 500, unknown), false, String(unknown));
  }
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

test("direct path: an original with a positive gain plays audio.m4a, a negative gain stays a volume", () => {
  const loud = { ...audioItem, originalGainDb: -3.2 };
  const quiet = { ...audioItem, originalGainDb: 2.5 };
  // Negative gain: the original, its gain applied as volume.
  assert.deepEqual(L.chooseSource(loud, yes, false, false, L.DIRECT_MAX_GAIN_DB),
    { url: "/media/a/original", gainDb: -3.2, video: false, original: true });
  // Positive gain: volume can't exceed 1, so the loudness-normalised audio.m4a, with the reason.
  assert.deepEqual(L.chooseSource(quiet, yes, false, false, L.DIRECT_MAX_GAIN_DB),
    { url: "/media/a/audio.m4a", gainDb: 0, video: false, original: false, originalSkippedGainDb: 2.5 });
  // Exactly 0 dB or no gain: the original.
  assert.equal(L.chooseSource({ ...audioItem, originalGainDb: 0 }, yes, false, false, L.DIRECT_MAX_GAIN_DB).original, true);
  assert.equal(L.chooseSource({ ...audioItem, originalGainDb: undefined }, yes, false, false, L.DIRECT_MAX_GAIN_DB).original, true);
  // Web Audio (no limit, the default): the original with its positive gain.
  assert.deepEqual(L.chooseSource(quiet, yes, false),
    { url: "/media/a/original", gainDb: 2.5, video: false, original: true });
  // No audio.m4a to fall back to: the original anyway.
  const { audioUrl: _a, ...noAudio } = quiet;
  assert.equal(L.chooseSource(noAudio, yes, false, false, L.DIRECT_MAX_GAIN_DB).url, "/media/a/original");
  assert.equal(L.DIRECT_MAX_GAIN_DB, 0);
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
