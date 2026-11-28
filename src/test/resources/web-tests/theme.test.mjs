// Unit tests for web/src/theme.ts (compiled), run with Node's test runner by SettingsJsTest.
// The module path comes in THEME_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const T = await import(pathToFileURL(process.env.THEME_JS).href);

function memory(initial) {
  const m = new Map(initial === undefined ? [] : [["tt.theme", initial]]);
  return { m, getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => { m.set(k, String(v)); } };
}

test("value → attribute and scheme", () => {
  assert.equal(T.themeAttribute("system"), undefined);
  assert.equal(T.themeAttribute("light"), "light");
  assert.equal(T.themeAttribute("dark"), "dark");
  assert.equal(T.effectiveScheme("system", true), "dark");
  assert.equal(T.effectiveScheme("system", false), "light");
  assert.equal(T.effectiveScheme("light", true), "light");
  assert.equal(T.effectiveScheme("dark", false), "dark");
  assert.equal(T.parseTheme("dark"), "dark");
  assert.equal(T.parseTheme("sepia"), "system");
  assert.equal(T.parseTheme(null), "system");
});

test("the attribute is set, replaced and removed", () => {
  const attrs = new Map();
  const root = { setAttribute: (k, v) => attrs.set(k, v), removeAttribute: (k) => attrs.delete(k) };
  T.setThemeAttribute(root, "dark");
  assert.equal(attrs.get("data-theme"), "dark");
  T.setThemeAttribute(root, "light");
  assert.equal(attrs.get("data-theme"), "light");
  T.setThemeAttribute(root, "system");
  assert.equal(attrs.has("data-theme"), false);
});

test("the mirror round trips under tt.theme", () => {
  assert.equal(T.THEME_STORAGE_KEY, "tt.theme");
  const s = memory();
  assert.equal(T.readThemeMirror(() => s), "system");
  assert.equal(T.writeThemeMirror(() => s, "dark"), true);
  assert.equal(s.m.get("tt.theme"), "dark");
  assert.equal(T.readThemeMirror(() => s), "dark");
  T.writeThemeMirror(() => s, "system");
  assert.equal(T.readThemeMirror(() => s), "system");
  assert.equal(T.readThemeMirror(() => memory("weird")), "system");
});

test("storage errors are tolerated", () => {
  const throwing = () => { throw new Error("SecurityError"); };
  assert.equal(T.readThemeMirror(throwing), "system");
  assert.equal(T.writeThemeMirror(throwing, "dark"), false);
  const broken = { getItem: () => { throw new Error("x"); }, setItem: () => { throw new Error("quota"); } };
  assert.equal(T.readThemeMirror(() => broken), "system");
  assert.equal(T.writeThemeMirror(() => broken, "light"), false);
  assert.equal(T.readThemeMirror(() => null), "system");
  assert.equal(T.writeThemeMirror(() => undefined, "light"), false);
});
