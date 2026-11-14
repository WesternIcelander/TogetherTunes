// Unit tests for web/src/guestmerge.ts (compiled), run with Node's test runner by LoginPopupJsTest.
// The module path comes in GUESTMERGE_JS. fetch is stubbed: the module only calls the JSON API.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const G = await import(pathToFileURL(process.env.GUESTMERGE_JS).href);

const guest = { kind: "guest", id: "g1", name: "Blue Fox", isGuest: true };

function stubFetch(status, body) {
  const calls = [];
  globalThis.fetch = async (path, init) => {
    calls.push({ path, method: init.method });
    return { ok: status >= 200 && status < 300, status, statusText: "x",
      text: async () => body === undefined ? "" : JSON.stringify(body) };
  };
  return calls;
}

test("the question and the summary name the guest and count with the right plural", () => {
  assert.equal(G.mergeQuestion(guest),
    "This browser was the guest \"Blue Fox\". Move that guest's songs and votes in rooms to your account?");
  assert.equal(G.mergedText({ guest, moved: { entries: 1, votes: 1 } }),
    "Moved 1 song and 1 vote from the guest \"Blue Fox\" to your account.");
  assert.equal(G.mergedText({ guest, moved: { entries: 0, votes: 3 } }),
    "Moved 0 songs and 3 votes from the guest \"Blue Fox\" to your account.");
});

test("mergeableGuest asks the server and is undefined when there is no guest", async () => {
  let calls = stubFetch(200, { guest });
  assert.deepEqual(await G.mergeableGuest(), guest);
  assert.deepEqual(calls, [{ path: "/api/me/merge-guest", method: "GET" }]);
  stubFetch(200, {});
  assert.equal(await G.mergeableGuest(), undefined);
});

test("mergeGuest posts and passes the server's refusal on", async () => {
  const result = { guest, moved: { entries: 2, votes: 0 } };
  const calls = stubFetch(200, result);
  assert.deepEqual(await G.mergeGuest(), result);
  assert.deepEqual(calls, [{ path: "/api/me/merge-guest", method: "POST" }]);
  stubFetch(409, { error: { code: "no_guest", message: "No guest to merge" } });
  await assert.rejects(G.mergeGuest(), (e) => e.status === 409 && e.code === "no_guest");
});
