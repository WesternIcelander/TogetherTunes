// Unit tests for web/src/volume.ts (compiled), run with Node's test runner by VolumeJsTest: the
// listener's volume subscription, the preview duck and the room gain with both mutes.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const V = await import(pathToFileURL(process.env.VOLUME_JS).href);

test("the volume channel tells subscribers at once and on every change only", () => {
  const ch = new V.VolumeChannel();
  const seen = [];
  const off = ch.subscribe((v) => seen.push(v));
  assert.deepEqual(seen, [{ gain: 1, muted: false }]);
  ch.set(0.25, false);
  ch.set(0.25, false); // no change: nobody is told
  ch.set(0.25, true);
  ch.set(2, true); // clamped
  ch.set(-1, false);
  ch.set(Number.NaN, false); // broken: full volume
  assert.deepEqual(seen, [{ gain: 1, muted: false }, { gain: 0.25, muted: false }, { gain: 0.25, muted: true },
    { gain: 1, muted: true }, { gain: 0, muted: false }, { gain: 1, muted: false }]);
  off();
  ch.set(0.5, false);
  assert.equal(seen.length, 6, "unsubscribed");
  assert.deepEqual(ch.get(), { gain: 0.5, muted: false });
  // the value handed out is a copy
  ch.get().gain = 0;
  assert.equal(ch.get().gain, 0.5);
});

test("a subscription ends with its signal", () => {
  const ch = new V.VolumeChannel();
  const ctl = new AbortController();
  const seen = [];
  ch.subscribe((v) => seen.push(v.gain), ctl.signal);
  ch.set(0.5, false);
  ctl.abort();
  ch.set(0.1, false);
  assert.deepEqual(seen, [1, 0.5]);
  const late = [];
  ch.subscribe((v) => late.push(v), ctl.signal);
  assert.deepEqual(late, [], "an aborted signal subscribes nothing");
});

test("the page's channels are shared singletons", () => {
  assert.ok(V.listenerVolume instanceof V.VolumeChannel);
  assert.ok(V.previewDuck instanceof V.PreviewDuck);
});

test("the duck holds while any holder ducks; subscribers hear changes only", () => {
  const d = new V.PreviewDuck();
  const seen = [];
  d.subscribe((on) => seen.push(on));
  const a = {}, b = {};
  d.set(a, true);
  d.set(a, true); // play then playing: still one holder
  d.set(b, true);
  d.set(a, false);
  assert.equal(d.ducked, true, "b still plays");
  d.set(b, false);
  d.set(b, false); // pause then emptied
  assert.equal(d.ducked, false);
  assert.deepEqual(seen, [false, true, false]);
});

test("the room's gain: the listener's mute and the duck each silence it, neither lifts the other", () => {
  assert.equal(V.roomGain(0.64, false, false), 0.64);
  assert.equal(V.roomGain(0.64, true, false), 0);
  assert.equal(V.roomGain(0.64, false, true), 0);
  assert.equal(V.roomGain(0.64, true, true), 0);
  // the preview plays at the listener's volume and mute
  assert.deepEqual(V.previewLevel({ gain: 0.25, muted: false }), { volume: 0.25, muted: false });
  assert.deepEqual(V.previewLevel({ gain: 0.25, muted: true }), { volume: 0.25, muted: true });
  assert.deepEqual(V.previewLevel({ gain: 3, muted: false }), { volume: 1, muted: false });
  assert.deepEqual([...V.DUCK_ON_EVENTS], ["play", "playing"]);
  assert.deepEqual([...V.DUCK_OFF_EVENTS], ["pause", "ended", "emptied", "error"]);
});
