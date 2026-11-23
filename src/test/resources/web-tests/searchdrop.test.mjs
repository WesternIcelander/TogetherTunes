// Unit tests for web/src/searchdrop.ts (compiled), run with Node's test runner by SearchDropJsTest.
// The module path comes in SEARCHDROP_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.SEARCHDROP_JS).href);

/** A clock the test runs by hand. */
function fakeTimers() {
  let now = 0;
  let nextId = 1;
  const pending = new Map();
  return {
    timers: {
      set(fn, ms) { const id = nextId++; pending.set(id, { at: now + ms, fn }); return id; },
      clear(id) { pending.delete(id); },
    },
    advance(ms) {
      now += ms;
      for (const [id, t] of [...pending]) {
        if (t.at <= now) { pending.delete(id); t.fn(); }
      }
    },
    count() { return pending.size; },
  };
}

test("searches 1 s after the last keystroke, with 2 or more characters after trimming", () => {
  const clock = fakeTimers();
  const ran = [];
  const t = new S.SearchTimer(clock.timers, (q) => ran.push(q));
  assert.equal(S.SEARCH_DELAY_MS, 1000);
  assert.equal(t.input("k"), false, "one character: nothing");
  assert.equal(t.input("  k  "), false, "one after trimming");
  assert.equal(t.input("ka"), true);
  clock.advance(999);
  assert.deepEqual(ran, [], "not before a second");
  assert.equal(t.input("kat"), true, "a keystroke restarts the wait");
  clock.advance(999);
  assert.deepEqual(ran, []);
  assert.equal(clock.count(), 1, "only one wait at a time");
  clock.advance(1);
  assert.deepEqual(ran, ["kat"]);
  assert.equal(t.pending(), false);
  t.input("  katy perry ");
  clock.advance(1000);
  assert.deepEqual(ran, ["kat", "katy perry"], "trimmed");
  // shortening below two characters cancels the wait
  t.input("katy");
  t.input("k");
  clock.advance(5000);
  assert.deepEqual(ran, ["kat", "katy perry"]);
  t.input("abc");
  t.cancel();
  clock.advance(5000);
  assert.deepEqual(ran, ["kat", "katy perry"], "cancelled (Enter, Escape)");
});

test("only the newest answer counts", () => {
  const l = new S.Latest();
  const a = l.next();
  const b = l.next();
  assert.equal(l.current(a), false, "an older answer is dropped");
  assert.equal(l.current(b), true);
  l.drop();
  assert.equal(l.current(b), false, "closing or typing drops the one on its way");
  assert.equal(l.current(l.next()), true);
});

test("the query and the paths", () => {
  assert.equal(S.dropdownQuery(" a "), undefined);
  assert.equal(S.dropdownQuery(""), undefined);
  assert.equal(S.dropdownQuery(" ab "), "ab");
  assert.equal(S.DROPDOWN_LIMIT, 8);
  assert.equal(S.libraryDropdownPath("katy & perry"), "/api/search?q=katy+%26+perry&limit=8");
  assert.equal(S.searchPagePath(" a b/c "), "/search?q=a%20b%2Fc");
});

test("arrow keys move through the rows and back to the box", () => {
  assert.equal(S.moveActive(-1, 3, 1), 0);
  assert.equal(S.moveActive(0, 3, 1), 1);
  assert.equal(S.moveActive(2, 3, 1), -1, "past the last: the box");
  assert.equal(S.moveActive(-1, 3, -1), 2, "up from the box: the last row");
  assert.equal(S.moveActive(0, 3, -1), -1);
  assert.equal(S.moveActive(1, 0, 1), -1, "no rows");
  assert.equal(S.moveActive(-1, 0, -1), -1);
});

test("Enter opens the chosen row, else the search page", () => {
  assert.equal(S.enterAction(-1, 3), "page");
  assert.equal(S.enterAction(0, 3), "row");
  assert.equal(S.enterAction(2, 3), "row");
  assert.equal(S.enterAction(3, 3), "page", "a row that's gone");
  assert.equal(S.enterAction(0, 0), "page");
});

test("status texts", () => {
  assert.equal(S.dropStatus(true, 0), "Searching…");
  assert.equal(S.dropStatus(true, 4), "Searching…");
  assert.equal(S.dropStatus(false, 0), "Nothing found.");
  assert.equal(S.dropStatus(false, 2), "");
  assert.equal(S.SEE_ALL_LABEL, "See all results");
  assert.equal(S.SEARCH_YOUTUBE_LABEL, "Search YouTube");
  assert.equal(S.LIBRARY_RESULTS_LABEL, "← Library results");
});
