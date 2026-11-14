// Views stop when the shell takes them away (the song page fight): nav.ts viewScope and media.ts
// watchItem with fake timers and a fake fetch. Run by ShellJsTest; NAV_JS and MEDIA_JS are the
// compiled modules.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

// ---- fake timers (only what the modules use: window.set/clearTimeout/Interval)
let now = 0;
let nextId = 1;
const timers = new Map();
function setT(f, ms, repeat) {
  const id = nextId++;
  timers.set(id, { at: now + ms, f, ms, repeat });
  return id;
}
globalThis.window = {
  setTimeout: (f, ms) => setT(f, ms, false),
  clearTimeout: (id) => { timers.delete(id); },
  setInterval: (f, ms) => setT(f, ms, true),
  clearInterval: (id) => { timers.delete(id); },
};
globalThis.document = { getElementById: () => null };
globalThis.location = { pathname: "/elsewhere", search: "?other=1", href: "http://x/elsewhere" };
/** Runs the timers due in the next `ms`, letting promises settle between them. */
async function advance(ms) {
  const end = now + ms;
  for (;;) {
    await settle();
    let due;
    for (const [id, t] of timers) if (t.at <= end && (due === undefined || t.at < due[1].at)) due = [id, t];
    if (due === undefined) break;
    const [id, t] = due;
    now = t.at;
    if (t.repeat) t.at = now + t.ms; else timers.delete(id);
    t.f();
  }
  now = end;
  await settle();
}
async function settle() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await new Promise((r) => setImmediate(r));
}

// ---- fake fetch: an item still processing (so the page keeps polling), with a running job
const fetched = [];
let hold; // when set, requests wait for it
globalThis.fetch = async (path) => {
  fetched.push(path);
  if (hold !== undefined) await hold;
  const job = path.endsWith("/job");
  const body = job ? { id: "j", kind: "upload", state: "running", progress: 0.5, attempts: 1, createdAt: 0 }
    : { id: path.split("/")[3], status: "processing", title: "T" };
  return { ok: true, status: 200, statusText: "OK", text: async () => JSON.stringify(body) };
};

const nav = await import(pathToFileURL(process.env.NAV_JS).href);
const media = await import(pathToFileURL(process.env.MEDIA_JS).href);

const polls = (id) => fetched.filter((p) => p === "/api/items/" + id).length;

test("watchItem polls until its signal aborts, then never fetches or reports again", async () => {
  const c = new AbortController();
  const seen = [];
  media.watchItem("A", (w) => seen.push(w.item.id), undefined, c.signal);
  await advance(5000);
  assert.ok(polls("A") >= 3, "polled while processing: " + polls("A"));
  assert.ok(seen.length >= 3);
  c.abort();
  const before = polls("A");
  const reported = seen.length;
  await advance(20_000);
  assert.equal(polls("A"), before, "no polls after the abort");
  assert.equal(seen.length, reported);
});

test("an answer that arrives after the abort is not reported", async () => {
  const c = new AbortController();
  const seen = [];
  const errors = [];
  let release;
  hold = new Promise((r) => { release = r; });
  media.watchItem("B", (w) => seen.push(w.item.id), (e) => errors.push(e), c.signal);
  await settle();
  assert.equal(polls("B"), 1, "first request in flight");
  c.abort(); // the user went on to another song meanwhile
  release();
  hold = undefined;
  await advance(10_000);
  assert.deepEqual(seen, []);
  assert.deepEqual(errors, []);
  assert.equal(polls("B"), 1);
});

test("watchItem with an aborted signal does nothing; the stop function still works", async () => {
  const c = new AbortController();
  c.abort();
  media.watchItem("C", () => assert.fail("reported"), undefined, c.signal);
  await advance(5000);
  assert.equal(polls("C"), 0);
  const seen = [];
  const stop = media.watchItem("D", (w) => seen.push(w.item.id));
  await advance(2500);
  stop();
  const n = polls("D");
  await advance(10_000);
  assert.equal(polls("D"), n);
});

test("switching songs A → B → C quickly leaves only C polling", async () => {
  // Like the shell: each view gets its own controller; the previous one is aborted when the next shows.
  let current;
  const shown = [];
  for (const id of ["A2", "B2", "C2"]) {
    current?.abort();
    current = new AbortController();
    media.watchItem(id, (w) => shown.push(w.item.id), undefined, current.signal);
    await advance(300);
  }
  const a = polls("A2");
  const b = polls("B2");
  shown.length = 0;
  await advance(10_000);
  assert.equal(polls("A2"), a);
  assert.equal(polls("B2"), b);
  assert.ok(polls("C2") >= 5);
  assert.ok(shown.length > 0 && shown.every((id) => id === "C2"), shown.join());
});

test("viewScope: the view's own path, signal and elements, and timers ended by the signal", async () => {
  const c = new AbortController();
  const lookups = [];
  const el = { id: "item-body" };
  const root = { querySelector: (sel) => { lookups.push(sel); return sel === "[id=\"item-body\"]" ? el : null; } };
  nav.registerViewStart(41, { path: "/items/x", search: "?id=x", signal: c.signal, root });
  const v = nav.viewScope("http://h/js/item.js?mount=41");
  assert.equal(v.path, "/items/x");
  assert.equal(v.search, "?id=x");
  assert.equal(v.signal, c.signal);
  assert.equal(v.element("item-body"), el);
  assert.equal(v.find("nope"), null);
  assert.throws(() => v.element("nope"), /Missing #nope/);
  let ticks = 0;
  let fired = 0;
  v.every(() => ticks++, 1000);
  v.after(() => fired++, 1500);
  v.after(() => fired++, 5000);
  await advance(2000);
  assert.equal(ticks, 2);
  assert.equal(fired, 1);
  const before = timers.size;
  c.abort();
  assert.equal(timers.size, before - 2, "the interval and the pending timeout are cleared");
  await advance(10_000);
  assert.equal(ticks, 2);
  assert.equal(fired, 1);
  v.every(() => ticks++, 100);
  v.after(() => fired++, 100);
  await advance(1000);
  assert.equal(ticks, 2, "nothing starts after the abort");
  assert.equal(fired, 1);
});

test("viewScope without the shell: the page's location and pageSignal", () => {
  const v = nav.viewScope("http://h/js/item.js");
  assert.equal(v.path, "/elsewhere");
  assert.equal(v.search, "?other=1");
  assert.equal(v.signal, nav.pageSignal());
  assert.equal(v.find("x"), null);
});
