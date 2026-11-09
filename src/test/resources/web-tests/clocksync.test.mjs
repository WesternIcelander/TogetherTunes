// Unit tests for web/src/clocksync.ts (compiled), run with Node's test runner by ClockSyncJsTest.
// The module path comes in CLOCKSYNC_JS. Time is simulated: a virtual clock, a timer queue and a
// "network" with per-exchange delays, so the tests are exact and instant.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const { ClockSync, sample } = await import(pathToFileURL(process.env.CLOCKSYNC_JS).href);

/** A virtual world: client clock, server clock = client + serverOffset, timers, network. */
function world(serverOffset) {
  const w = {
    now: 1_000_000,
    serverOffset,
    timers: [],
    nextId: 1,
    sent: [],
    connected: true,
    /** Delay [up, down] for the next exchanges; default 10/10. undefined = the reply is lost. */
    delays: [],
    setTimeout(f, ms) {
      const id = w.nextId++;
      w.timers.push({ id, at: w.now + ms, f });
      return id;
    },
    clearTimeout(id) {
      w.timers = w.timers.filter((t) => t.id !== id);
    },
    send(t0) {
      if (!w.connected) return false;
      w.sent.push(t0);
      const d = w.delays.length > 0 ? w.delays.shift() : [10, 10];
      if (d === undefined) return true;
      const [up, down] = d;
      const ts = w.now + up + w.serverOffset;
      w.setTimeout(() => w.sync.reply(t0, ts, w.now), up + down);
      return true;
    },
    /** Runs timers until `until` (absolute client ms). */
    runUntil(until) {
      for (;;) {
        w.timers.sort((a, b) => a.at - b.at || a.id - b.id);
        const t = w.timers[0];
        if (t === undefined || t.at > until) break;
        w.timers.shift();
        w.now = t.at;
        t.f();
      }
      w.now = until;
    },
    advance(ms) {
      w.runUntil(w.now + ms);
    },
  };
  w.sync = new ClockSync((t0) => w.send(t0), {
    now: () => w.now,
    setTimeout: (f, ms) => w.setTimeout(f, ms),
    clearTimeout: (h) => w.clearTimeout(h),
  });
  return w;
}

const close = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) <= eps, `${a} != ${b}`);

test("sample: rtt and offset from one exchange", () => {
  const s = sample(100, 5060, 120);
  assert.equal(s.rtt, 20);
  assert.equal(s.offset, 5060 - 110);
});

test("a round is 8 sequential exchanges and keeps the lowest-RTT sample", () => {
  const w = world(5000);
  // Asymmetric delays bias the offset; the 4/4 exchange is symmetric and has the lowest RTT.
  w.delays = [[30, 5], [5, 40], [20, 20], [4, 4], [50, 10], [10, 60], [25, 5], [5, 25]];
  w.sync.start();
  assert.equal(w.sent.length, 1, "one exchange at a time");
  w.advance(1000);
  assert.equal(w.sent.length, 8);
  close(w.sync.offsetMs, 5000);
  assert.equal(w.sync.rttMs, 8);
  close(w.sync.serverNow(), w.now + 5000);
  w.advance(20_000);
  assert.equal(w.sent.length, 8, "no more exchanges until the next round");
});

test("rounds repeat every 30 s and are smoothed with an EMA", () => {
  const w = world(5000);
  const updates = [];
  w.sync.onUpdate = (offset, rtt) => updates.push([offset, rtt]);
  w.sync.start();
  w.advance(29_999);
  assert.equal(w.sent.length, 8);
  close(w.sync.offsetMs, 5000);
  w.serverOffset = 5010; // the server clock drifted by 10 ms
  w.advance(1);
  assert.equal(w.sent.length, 9, "second round starts 30 s after the first");
  w.advance(29_999);
  assert.equal(w.sent.length, 16);
  close(w.sync.offsetMs, 5000 + 0.3 * 10);
  w.advance(30_000);
  close(w.sync.offsetMs, 5003 + 0.3 * 7);
  assert.equal(updates.length, 3);
});

test("a round whose best RTT is above 2x the minimum is ignored", () => {
  const w = world(5000);
  w.delays = Array(8).fill([10, 10]); // min RTT 20
  w.sync.start();
  w.advance(1000); // checks happen 1 s into each 30 s period, after that period's round
  close(w.sync.offsetMs, 5000);
  // Round 2: congested, best RTT 60 (> 40) and biased by 20 ms: ignored.
  w.delays = Array(8).fill([50, 10]);
  w.advance(30_000);
  close(w.sync.offsetMs, 5000);
  assert.equal(w.sync.rttMs, 20);
  // Round 3: RTT 30 (<= 40) passes and is averaged in.
  w.serverOffset = 5010;
  w.delays = Array(8).fill([15, 15]);
  w.advance(30_000);
  close(w.sync.offsetMs, 5003);
  assert.equal(w.sync.rttMs, 30);
});

test("the minimum RTT is taken over recent rounds, so a slower network is accepted eventually", () => {
  const w = world(5000);
  w.sync.start(); // RTT 20
  w.advance(1000); // checks happen 1 s into each 30 s period, after that period's round
  w.advance(30_000);
  w.serverOffset = 5100;
  for (let i = 0; i < 9; i++) {
    w.delays = Array(8).fill([40, 40]); // RTT 80 > 2 x 20
    w.advance(30_000);
    close(w.sync.offsetMs, 5000, 1e-6);
  }
  // The RTT-20 round has left the 10-round window: RTT 80 is now the minimum.
  w.delays = Array(8).fill([40, 40]);
  w.advance(30_000);
  close(w.sync.offsetMs, 5000 + 0.3 * 100);
});

test("tiny RTTs are not filtered for sub-millisecond jitter", () => {
  const w = world(5000);
  w.delays = Array(8).fill([0.1, 0.1]);
  w.sync.start();
  w.advance(1000);
  w.serverOffset = 5001;
  w.delays = Array(8).fill([0.5, 0.5]); // 1 ms: above 2x 0.2 ms, within the 5 ms slack
  w.advance(30_000);
  close(w.sync.offsetMs, 5000 + 0.3, 1e-6);
});

test("a large jump replaces the offset instead of easing towards it", () => {
  const w = world(5000);
  w.sync.start();
  w.advance(1000);
  w.serverOffset = 65_000; // the client slept and its monotonic clock fell behind
  w.advance(30_000);
  close(w.sync.offsetMs, 65_000);
});

test("a lost reply times out after 2 s; a late reply is ignored", () => {
  const w = world(5000);
  w.delays = [undefined, [3000, 3000]]; // first is lost, second arrives after the timeout
  w.sync.start();
  w.advance(1999);
  assert.equal(w.sent.length, 1);
  w.advance(1);
  assert.equal(w.sent.length, 2, "next exchange after the reply timeout");
  w.advance(2000);
  assert.equal(w.sent.length, 3);
  w.advance(10_000);
  assert.equal(w.sent.length, 8);
  close(w.sync.offsetMs, 5000);
  assert.equal(w.sync.rttMs, 20, "the 6 s reply wasn't used");
});

test("no offset before the first round: the client's wall clock", () => {
  const w = world(5000);
  assert.equal(w.sync.offsetMs, undefined);
  const before = Date.now();
  const t = w.sync.serverNow();
  assert.ok(t >= before && t <= Date.now());
});

test("disconnected: the round ends, the offset is kept, the next connect measures again", () => {
  const w = world(5000);
  w.sync.start();
  w.advance(1000);
  w.sync.stop();
  w.connected = false;
  w.advance(120_000);
  assert.equal(w.sent.length, 8, "stopped: no rounds");
  assert.equal(w.timers.length, 0);
  close(w.sync.offsetMs, 5000);
  w.sync.start(); // send fails: round ends without a sample
  assert.equal(w.sent.length, 8);
  close(w.sync.offsetMs, 5000);
  w.sync.stop();
  w.connected = true;
  w.serverOffset = 5010;
  w.sync.start();
  w.advance(1000);
  assert.equal(w.sent.length, 16);
  close(w.sync.offsetMs, 5003);
});

test("syncNow measures at once between rounds, but not during one or when stopped", () => {
  const w = world(5000);
  w.sync.syncNow();
  assert.equal(w.sent.length, 0, "not started");
  w.sync.start();
  w.sync.syncNow();
  assert.equal(w.sent.length, 1, "a round is running");
  w.advance(10_000);
  w.sync.syncNow();
  assert.equal(w.sent.length, 9);
  w.advance(1000);
  assert.equal(w.sent.length, 16);
  w.advance(28_999);
  assert.equal(w.sent.length, 16, "the 30 s timer restarted with the new round");
  w.advance(1);
  assert.equal(w.sent.length, 17);
});
