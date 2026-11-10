// Unit tests for web/src/contentid.ts (compiled), run with Node's test runner by ContentIdJsTest.
// The module path comes in CONTENTID_JS. The expected IDs are the ones ContentIdTest checks the
// Java side against, so browser and server agree.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { webcrypto } from "node:crypto";

const C = await import(pathToFileURL(process.env.CONTENTID_JS).href);
const subtle = webcrypto.subtle;
const PIECE = 8 * 1024 * 1024;

function fixture(size) {
  const b = new Uint8Array(size);
  for (let i = 0; i < size; i++) b[i] = (i * 31 + 7) & 0xff;
  return b;
}

test("two full pieces and a short last one: the same ID as the server", async () => {
  const blob = new Blob([fixture(2 * PIECE + 12345)]);
  const seen = [];
  const id = await C.contentId(blob, subtle, (d) => seen.push(d));
  assert.equal(id, "sha256c8m:16789561:b9d119c9f7078bd2a9ea80d853d0914f1946eabaa46a77c6657f01015932528e");
  assert.deepEqual(seen, [1 / 3, 2 / 3, 1]);
});

test("small and exactly-one-piece files", async () => {
  assert.equal(await C.contentId(new Blob([new TextEncoder().encode("abc")]), subtle),
    "sha256c8m:3:4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358");
  assert.equal(await C.contentId(new Blob([fixture(PIECE)]), subtle),
    "sha256c8m:8388608:b179ca4324dda9576602c8bb5c78905e25575c145a67013b2468ccecf91f7832");
  await assert.rejects(C.contentId(new Blob([]), subtle));
});

test("reads the file piece by piece and stops when asked", async () => {
  const blob = new Blob([fixture(2 * PIECE + 1)]);
  const slices = [];
  const spy = { size: blob.size, slice: (a, b) => { slices.push([a, b]); return blob.slice(a, b); } };
  await C.contentId(spy, subtle);
  assert.deepEqual(slices, [[0, PIECE], [PIECE, 2 * PIECE], [2 * PIECE, 2 * PIECE + 1]]);
  let calls = 0;
  await assert.rejects(C.contentId(blob, subtle, undefined, () => ++calls > 1), { name: "AbortError" });
});

test("the pre-check needs an https page in a secure context with crypto.subtle", () => {
  assert.equal(C.precheckAvailable({ protocol: "https:", isSecureContext: true, subtle }), true);
  assert.equal(C.precheckAvailable({ protocol: "http:", isSecureContext: true, subtle }), false, "http://localhost");
  assert.equal(C.precheckAvailable({ protocol: "http:", isSecureContext: false, subtle: undefined }), false);
  assert.equal(C.precheckAvailable({ protocol: "https:", isSecureContext: false, subtle }), false);
  assert.equal(C.precheckAvailable({ protocol: "https:", isSecureContext: true, subtle: undefined }), false);
  // In Node (no page): no location, so off.
  assert.equal(C.precheckAvailable(), false);
});
