// The page side of the Appearance preference (theme.ts): applies a value to this document and
// mirrors it for the next page load. (Nothing in the page reads the colour scheme today; anything
// that needs to should use theme.ts's effectiveScheme with the attribute set here.)
import { setThemeAttribute, writeThemeMirror, type ThemePref } from "./theme.js";

/**
 * Shows `theme` now. `mirror`: also remember it for the next page load (the signed-in user's own
 * value); false for "follow the system" while nobody is signed in, which keeps the browser's mirror.
 */
export function applyTheme(theme: ThemePref, mirror: boolean): void {
  setThemeAttribute(document.documentElement, theme);
  if (mirror) writeThemeMirror(() => window.localStorage, theme);
}
