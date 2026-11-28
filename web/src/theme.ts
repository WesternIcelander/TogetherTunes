// The Appearance preference (user settings → Preferences, docs/ui.md#user-settings): Match System,
// Light Mode or Dark Mode. Pure logic (no DOM): the page passes its <html> element and storage in
// (themeclient.ts), so it runs under Node in the tests (theme.test.mjs).
//
// A forced scheme is `data-theme="light"|"dark"` on <html> (main.css follows it); Match System is no
// attribute. The last applied value is mirrored to localStorage (`tt.theme`) so index.html's tiny
// head script can set the attribute before the first paint; the server's value wins once the user's
// settings load. Storage may be missing or throw (private windows, blocked site data): reads then
// give "system" and writes do nothing.

import { parseTheme, type ThemePref } from "./prefs.js";

export { parseTheme, type ThemePref };

/** The localStorage key of the mirror (index.html's head script reads the same key). */
export const THEME_STORAGE_KEY = "tt.theme";

/** The `data-theme` attribute for a preference (undefined: no attribute, follow the system). */
export function themeAttribute(theme: ThemePref): "light" | "dark" | undefined {
  return theme === "system" ? undefined : theme;
}

/** The scheme in effect: the forced one, else the system's. */
export function effectiveScheme(theme: ThemePref, systemDark: boolean): "light" | "dark" {
  return theme === "system" ? (systemDark ? "dark" : "light") : theme;
}

/** What `getItem`/`setItem` need of a storage. */
export interface ThemeStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** The mirrored value; "system" when there is none or the storage can't be read. */
export function readThemeMirror(storage: () => ThemeStorage | null | undefined): ThemePref {
  try {
    const s = storage();
    return s ? parseTheme(s.getItem(THEME_STORAGE_KEY)) : "system";
  } catch {
    return "system";
  }
}

/** Mirrors `theme`; false when the storage can't be written (nothing else happens then). */
export function writeThemeMirror(storage: () => ThemeStorage | null | undefined, theme: ThemePref): boolean {
  try {
    const s = storage();
    if (!s) return false;
    s.setItem(THEME_STORAGE_KEY, theme);
    return true;
  } catch {
    return false;
  }
}

/** What an element needs for the attribute. */
export interface ThemeRoot {
  setAttribute(name: string, value: string): void;
  removeAttribute(name: string): void;
}

/** Sets (or removes, for Match System) `data-theme` on `root`. */
export function setThemeAttribute(root: ThemeRoot, theme: ThemePref): void {
  const attr = themeAttribute(theme);
  if (attr === undefined) root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", attr);
}
