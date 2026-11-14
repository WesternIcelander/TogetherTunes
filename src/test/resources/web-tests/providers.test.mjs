// Unit tests for web/src/providers.ts (compiled): provider URLs and the popup login's messages
// (docs/ui.md#single-page-app). Run with Node's test runner by LoginPopupJsTest; the module path
// comes in PROVIDERS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const P = await import(pathToFileURL(process.env.PROVIDERS_JS).href);
const ORIGIN = "https://tt.example";

test("start URLs: next, link and popup", () => {
  assert.equal(P.providerStartUrl("/auth/oidc/a/start", "/"), "/auth/oidc/a/start");
  assert.equal(P.providerStartUrl("/auth/oidc/a/start", "/rooms/x"), "/auth/oidc/a/start?next=%2Frooms%2Fx");
  assert.equal(P.providerPopupUrl("/auth/oidc/a/start"), "/auth/oidc/a/start?popup=1");
  assert.equal(P.providerPopupUrl("/auth/oidc/a/start?x=1"), "/auth/oidc/a/start?x=1&popup=1");
  assert.equal(P.providerLinkUrl("/auth/oidc/a/start"), "/auth/oidc/a/start?intent=link");
});

test("the popup-done page reads its result from the query", () => {
  assert.deepEqual(P.popupResult(""), { ok: true });
  assert.deepEqual(P.popupResult("?error=signup_disabled"), { ok: false, error: "signup_disabled" });
  assert.deepEqual(P.popupResult("?error="), { ok: false, error: "provider_error" });
  assert.deepEqual(P.loginMessage({ ok: true }, "t1"), { type: P.LOGIN_MESSAGE_TYPE, id: "t1", ok: true });
  assert.deepEqual(P.loginMessage({ ok: false, error: "login_expired" }, "t2"),
    { type: P.LOGIN_MESSAGE_TYPE, id: "t2", ok: false, error: "login_expired" });
});

test("only same-origin login messages count", () => {
  const ok = P.loginMessage({ ok: true }, "a1");
  assert.deepEqual(P.parseLoginMessage(ok, ORIGIN, ORIGIN), { ok: true, id: "a1" });
  assert.deepEqual(P.parseLoginMessage(ok, undefined, ORIGIN), { ok: true, id: "a1" }, "a BroadcastChannel is same-origin");
  assert.equal(P.parseLoginMessage(ok, "https://evil.example", ORIGIN), undefined);
  assert.equal(P.parseLoginMessage(ok, "null", ORIGIN), undefined);
  for (const junk of [null, undefined, "x", 1, {}, { type: "other", ok: true }]) {
    assert.equal(P.parseLoginMessage(junk, ORIGIN, ORIGIN), undefined, JSON.stringify(junk));
  }
  const failed = P.loginMessage({ ok: false, error: "provider_denied" }, "a2");
  assert.deepEqual(P.parseLoginMessage(failed, ORIGIN, ORIGIN), { ok: false, error: "provider_denied", id: "a2" });
  // a code that isn't one becomes the generic failure (shown as text, but keep it tidy)
  assert.deepEqual(P.parseLoginMessage({ type: P.LOGIN_MESSAGE_TYPE, ok: false, error: "<b>x</b>" }, ORIGIN, ORIGIN),
    { ok: false, error: "provider_error", id: "" });
  assert.deepEqual(P.parseLoginMessage({ type: P.LOGIN_MESSAGE_TYPE, ok: "yes" }, ORIGIN, ORIGIN),
    { ok: false, error: "provider_error", id: "" });
  assert.match(P.providerErrorMessage("provider_denied"), /cancelled/);
});
