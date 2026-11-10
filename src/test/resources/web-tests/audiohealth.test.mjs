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
