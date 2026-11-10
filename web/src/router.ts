// The rules of the room page's shell (shell.ts): which navigations it handles itself, so that
// listen-mode playback survives them, and which it leaves to the browser. Pure logic (no DOM), so
// it runs under Node in the tests (src/test/resources/web-tests/router.test.mjs).
//
// While a room plays in listen mode, links to the site's own pages (front page, rooms list,
// library, playlists, encodes, account, administration, item pages, room management) are fetched and
// swapped into the page; a link back to the playing room shows its view again. Everything else
// goes to the browser: another room (leave this one, join that one), the login / register /
// password / setup pages (they change who is listening), the API, media and downloads, test pages,
// other sites, and clicks meant for a new tab or window.

/** What a URL on this site is, for the shell: a page it can show, a room, or neither. */
export type Route = { kind: "page" } | { kind: "room"; ref: string } | { kind: "other" };

/** The site pages the shell may show in place (each is a static HTML page with one module). */
const PAGE_PATHS = new Set(["/", "/index.html", "/rooms/", "/library/", "/playlists/", "/encodes/",
  "/account/", "/admin/"]);

function decodeSegment(s: string): string | undefined {
  try {
    return decodeURIComponent(s);
  } catch {
    return undefined;
  }
}

/** The route of a path on this site. */
export function routeOf(pathname: string): Route {
  if (PAGE_PATHS.has(pathname)) return { kind: "page" };
  // Item pages (/items/{id}) and room management (/rooms/{ref}/manage) are pages too.
  if (/^\/items\/[^/]+\/?$/.test(pathname)) return { kind: "page" };
  if (/^\/rooms\/[^/]+\/manage\/?$/.test(pathname)) return { kind: "page" };
  const m = /^\/rooms\/([^/]+)\/?$/.exec(pathname);
  if (m !== null && m[1] !== undefined) {
    const ref = decodeSegment(m[1]);
    if (ref !== undefined && ref !== "") return { kind: "room", ref };
  }
  return { kind: "other" };
}

/** A link click, as the shell sees it (from the event and the `<a>`). */
export interface ClickInfo {
  /** `MouseEvent.button` (0 = main). */
  button: number;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  /** Someone (the page's own handler) already handled it. */
  defaultPrevented: boolean;
  /** The link's `target` attribute ("" when it has none). */
  target: string;
  /** The link has a `download` attribute. */
  download: boolean;
}

/**
 * What to do with a navigation to `href`:
 * - "browser": leave it to the browser (a full page load, a new tab, another site, ...);
 * - "hash": same page, only the fragment differs: the browser scrolls, nothing is loaded;
 * - "room": the playing room's own page: show its view again;
 * - "page": a site page: fetch it and show it in place.
 */
export type Decision = "browser" | "hash" | "room" | "page";

/**
 * Decides a navigation from the page at `current` (an absolute URL) to `href` (absolute, or
 * relative to `current`). `isCurrentRoom` says whether a room reference (id or slug) is the
 * playing room. `click` is the link click that started it, if any (a script's own navigation
 * has none).
 */
export function decide(href: string, current: string, isCurrentRoom: (ref: string) => boolean,
                       click?: ClickInfo): Decision {
  if (click !== undefined) {
    if (click.defaultPrevented || click.button !== 0) return "browser";
    if (click.ctrlKey || click.metaKey || click.shiftKey || click.altKey) return "browser";
    const target = click.target.trim().toLowerCase();
    if (target !== "" && target !== "_self") return "browser";
    if (click.download) return "browser";
  }
  let to: URL;
  let from: URL;
  try {
    from = new URL(current);
    to = new URL(href, from);
  } catch {
    return "browser";
  }
  if (to.protocol !== "http:" && to.protocol !== "https:") return "browser";
  if (to.origin !== from.origin) return "browser";
  if (to.pathname === from.pathname && to.search === from.search && to.hash !== "") return "hash";
  const route = routeOf(to.pathname);
  if (route.kind === "room") return isCurrentRoom(route.ref) ? "room" : "browser";
  return route.kind === "page" ? "page" : "browser";
}

/** The part of a URL that picks the page (path and query, not the fragment). */
export function pageKey(url: string): string {
  try {
    const u = new URL(url);
    return u.pathname + u.search;
  } catch {
    return url;
  }
}
