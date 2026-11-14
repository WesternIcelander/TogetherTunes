// Unit tests for web/src/prefs.ts (compiled), run with Node's test runner by SettingsJsTest.
// The module path comes in PREFS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const P = await import(pathToFileURL(process.env.PREFS_JS).href);

test("parsing: defaults for anything missing or not understood", () => {
  assert.deepEqual(P.parsePrefs(undefined), { doubleClickAlone: "play", webAudio: "auto", debug: false });
  assert.deepEqual(P.parsePrefs({ doubleClickAlone: "queue", webAudio: "off", debug: true }),
    { doubleClickAlone: "queue", webAudio: "off", debug: true });
  assert.deepEqual(P.parsePrefs({ doubleClickAlone: "x", webAudio: 1, debug: "yes", other: 1 }),
    { doubleClickAlone: "play", webAudio: "auto", debug: false });
});

test("patches carry only what changes", () => {
  const cur = P.parsePrefs({});
  assert.equal(P.prefsPatch(cur, {}), null);
  assert.equal(P.prefsPatch(cur, { webAudio: "auto" }), null);
  assert.deepEqual(P.prefsPatch(cur, { webAudio: "on", debug: false }), { webAudio: "on" });
  assert.deepEqual(P.changedKeys(undefined, cur), ["doubleClickAlone", "webAudio", "debug"]);
  assert.deepEqual(P.changedKeys(cur, { ...cur, debug: true }), ["debug"]);
});

function store(initial, saveImpl) {
  const saved = [];
  let loads = 0;
  const s = new P.PrefsStore(async () => { loads++; return initial; }, async (patch) => {
    saved.push(patch);
    return saveImpl !== undefined ? saveImpl(patch) : { ...initial, ...patch };
  });
  return { s, saved, loads: () => loads };
}

test("loads once and tells listeners what differs from the defaults", async () => {
  const { s, loads } = store({ webAudio: "off", debug: true });
  const heard = [];
  s.subscribe((p, changed, byUser) => heard.push([changed, byUser]));
  const [a, b] = await Promise.all([s.load(), s.load()]);
  assert.equal(a, b);
  assert.equal(loads(), 1);
  assert.deepEqual(heard, [[["webAudio", "debug"], false]]);
  assert.equal(s.get().webAudio, "off");
  await s.load();
  assert.equal(loads(), 1);
});

test("a change applies at once (byUser, synchronously) and is saved", async () => {
  const { s, saved } = store({});
  await s.load();
  const heard = [];
  s.subscribe((p, changed, byUser) => heard.push([p.webAudio, changed, byUser]));
  const done = s.set({ webAudio: "on" });
  // Told before the save finished: still inside the user's gesture.
  assert.deepEqual(heard, [["on", ["webAudio"], true]]);
  assert.equal(s.get().webAudio, "on");
  await done;
  assert.deepEqual(saved, [{ webAudio: "on" }]);
  assert.equal(heard.length, 1);
  // Nothing to change: no request.
  await s.set({ webAudio: "on" });
  assert.equal(saved.length, 1);
});

test("a failed save puts the old values back", async () => {
  const { s } = store({ debug: false }, () => { throw new Error("nope"); });
  await s.load();
  const heard = [];
  s.subscribe((p, changed, byUser) => heard.push([p.debug, byUser]));
  await assert.rejects(s.set({ debug: true }), /nope/);
  assert.deepEqual(heard, [[true, true], [false, false]]);
  assert.equal(s.get().debug, false);
});

test("reset forgets them (another user) and tells the listeners the defaults", async () => {
  const { s, loads } = store({ debug: true });
  await s.load();
  const heard = [];
  const off = s.subscribe((p, changed) => heard.push([p.debug, changed]));
  s.reset();
  assert.equal(s.get(), undefined);
  assert.deepEqual(heard, [[false, ["debug"]]]);
  await s.load();
  assert.equal(loads(), 2);
  off();
  s.reset();
  assert.equal(heard.length, 2);
});

test("a load overtaken by a reset doesn't come back", async () => {
  let resolve;
  const s = new P.PrefsStore(() => new Promise((r) => { resolve = r; }), async (p) => p);
  const loading = s.load();
  s.reset();
  resolve({ debug: true });
  await loading;
  assert.equal(s.get(), undefined);
});
