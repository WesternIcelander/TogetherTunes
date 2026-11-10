// Unit tests for web/src/storage.ts and the 507 rule of web/src/uploads.ts (compiled), run with
// Node's test runner by StorageJsTest. Module paths come in STORAGE_JS, UPLOADS_JS and API_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const S = await import(pathToFileURL(process.env.STORAGE_JS).href);
const U = await import(pathToFileURL(process.env.UPLOADS_JS).href);
const A = await import(pathToFileURL(process.env.API_JS).href);
const GIB = 1024 * 1024 * 1024;

test("GiB in, bytes out, and back", () => {
  assert.equal(S.formatGib(5 * GIB), "5");
  assert.equal(S.formatGib(0), "0");
  assert.equal(S.formatGib(1.5 * GIB), "1.5");
  assert.equal(S.formatGib(123456789), "0.115");
  assert.equal(S.parseGib("5"), 5 * GIB);
  assert.equal(S.parseGib(" 0.5 "), GIB / 2);
  assert.equal(S.parseGib("0"), 0);
  assert.ok(Number.isNaN(S.parseGib("")));
  assert.ok(Number.isNaN(S.parseGib("lots")));
  assert.ok(Number.isInteger(S.parseGib("0.1")), "whole bytes");
});

test("the storage box: free space, the minimum, the warning only while low", () => {
  let t = S.storageText({ freeBytes: 8 * GIB, totalBytes: 100 * GIB, minFreeBytes: 5 * GIB, low: false });
  assert.equal(t.line, "Free space on the data volume: 8 GiB of 100 GiB. Minimum: 5 GiB.");
  assert.equal(t.warning, undefined);
  t = S.storageText({ freeBytes: 4 * GIB, totalBytes: 100 * GIB, minFreeBytes: 5 * GIB, low: true });
  assert.match(t.warning, /below the minimum/);
  assert.match(t.warning, /uploads and YouTube links are refused/);
  t = S.storageText({ freeBytes: GIB, totalBytes: 2 * GIB, minFreeBytes: 0, low: false });
  assert.match(t.line, /Minimum: off/);
  t = S.storageText({ minFreeBytes: 5 * GIB, low: false });
  assert.match(t.line, /unknown/);
});

test("uploads: a 507 stops without retrying; other server errors retry", () => {
  assert.equal(U.retryable(new A.ApiError(507, "insufficient_storage", "The server is low on disk space; adding media is paused.")), false);
  assert.equal(U.retryable(new A.ApiError(503, "x", "busy")), true);
  assert.equal(U.retryable(new A.ApiError(0, "network", "offline")), true);
  assert.equal(U.retryable(new A.ApiError(413, "payload_too_large", "big")), false);
});
