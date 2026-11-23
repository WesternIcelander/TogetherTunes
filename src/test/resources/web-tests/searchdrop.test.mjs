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

test("a row's double-click adds it where the lists' double-click would", () => {
  const room = (you) => ({ you: { canRequest: true, canYoutube: true, canRequestLibrary: true, ...you } });
  // library rows: a request in the room open here (alone too), not without a room or the right
  assert.equal(S.rowDoubleClickAdds("library", room({})), true);
  assert.equal(S.rowDoubleClickAdds("library", room({ canRequestLibrary: undefined })), true);
  assert.equal(S.rowDoubleClickAdds("library", undefined), false);
  assert.equal(S.rowDoubleClickAdds("library", room({ canRequest: false })), false);
  assert.equal(S.rowDoubleClickAdds("library", room({ canRequestLibrary: false })), false);
  assert.equal(S.rowDoubleClickAdds("library", room({ restriction: { kind: "timeout", until: 5 } })), false);
  // YouTube rows: only where Add to Queue is shown
  assert.equal(S.rowDoubleClickAdds("youtube", room({})), true);
  assert.equal(S.rowDoubleClickAdds("youtube", room({ canRequestLibrary: false })), true);
  assert.equal(S.rowDoubleClickAdds("youtube", room({ canYoutube: false })), false);
  assert.equal(S.rowDoubleClickAdds("youtube", room({ restriction: { kind: "ban" } })), false);
  assert.equal(S.rowDoubleClickAdds("youtube", undefined), false);
});

test("a click opens the row after the lists' 300 ms wait, a double-click cancels it", () => {
  const clock = fakeTimers();
  const o = new S.ClickOpener(clock.timers);
  let opened = 0;
  const open = () => { opened++; };
  // a double-click would add the row: the click waits
  o.click(1, true, open);
  assert.equal(o.pending(), true);
  clock.advance(299);
  assert.equal(opened, 0);
  clock.advance(1);
  assert.equal(opened, 1);
  assert.equal(o.pending(), false);
  // the second click of a double-click cancels it, and so does the dblclick (cancel)
  o.click(1, true, open);
  clock.advance(150);
  o.click(2, true, open);
  assert.equal(o.pending(), false);
  clock.advance(1000);
  assert.equal(opened, 1);
  o.click(1, true, open);
  o.cancel();
  clock.advance(1000);
  assert.equal(opened, 1);
  // nothing to add (no room): opens at once, a double-click opens it too
  o.click(1, false, open);
  assert.equal(opened, 2);
  o.click(2, false, open);
  assert.equal(opened, 3);
  assert.equal(clock.count(), 0);
});
