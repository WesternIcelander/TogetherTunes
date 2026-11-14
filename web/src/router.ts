// The single-page app's routes (docs/ui.md#single-page-app): which view a URL shows, and how the
// shell (shell.ts) handles a navigation. Pure logic (no DOM), so it runs under Node in the tests
// (src/test/resources/web-tests/router.test.mjs).
//
// The server sends the same document (index.html) for every UI path (StaticFiles.isAppPath, which
// follows the same rules as `isAppPath` here); the shell routes it to a view. Paths with their own
// server routes (the API, media, streams, OIDC, the room short link, the WebSocket, the test pages
// and the static files) are never views.
//
// While a room plays in the background (listen mode, another view shown), a link to another room
// switches rooms in place, and everything else is shown in place too, the login, register, password
// and setup views included (a login re-identifies the page without a reload: nav.ts identityChanged).

/** The views of the app. Each has a `<template id="view-…">` in index.html and (most) a module. */
export type ViewName = "home" | "login" | "register" | "password" | "setup" | "rooms" | "room" | "roomManage"
  | "encodes" | "account" | "admin" | "item" | "list" | "notFound";

/**
 * The desktop layout's middle lists (docs/ui.md#layout-desktop), all shown by the `list` view:
 * All My Songs, Movies/Shows, Liked Songs, Uncategorized Videos, search results and one playlist.
 */
export type ListName = "songs" | "shows" | "liked" | "uncategorized" | "search" | "playlist";

/** A URL that shows a view: which one, its path parameters, and the path to show for it. */
export interface ViewRoute {
  kind: "view";
  view: ViewName;
  /** Path parameters (`ref` for rooms, `id` for items and playlists), decoded. */
  params: Record<string, string>;
  /** The view's own spelling of the path (aliases such as `/login.html` or `/library/` map to it). */
  path: string;
}

/** What a path on this site is: a view, or something the server answers itself. */
export type Route = ViewRoute | { kind: "other" };

/** What the shell knows about each view. */
export interface ViewInfo {
  /** The `<template>` in index.html holding the view's markup. */
  template: string;
  /** The module (under /js/) that runs the view, if any. */
  module?: string;
}

export const VIEWS: Readonly<Record<ViewName, ViewInfo>> = {
  home: { template: "view-home", module: "main" },
  login: { template: "view-login", module: "login" },
  register: { template: "view-register", module: "register" },
  password: { template: "view-password", module: "password" },
  setup: { template: "view-setup", module: "setup" },
  rooms: { template: "view-rooms", module: "rooms" },
  room: { template: "view-room", module: "room" },
  roomManage: { template: "view-room-manage", module: "roommanage" },
  encodes: { template: "view-encodes", module: "encodes" },
  account: { template: "view-account", module: "account" },
  admin: { template: "view-admin", module: "admin" },
  item: { template: "view-item", module: "item" },
  list: { template: "view-list", module: "libview" },
  notFound: { template: "view-not-found" },
};

/**
 * First path segments the server answers itself (API, media, streams, OIDC, short links, the
 * WebSocket, test pages, static files): never a view, and never sent index.html.
 */
export const SERVER_PREFIXES: readonly string[] = ["api", "media", "live", "auth", "r", "ws", "test", "js", "css"];

/** Fixed paths → view and canonical path. Without the trailing slash and the old `.html` names too. */
const FIXED: Record<string, [ViewName, string]> = {
  "/": ["home", "/"],
  "/index.html": ["home", "/"],
  "/login": ["login", "/login"],
  "/login.html": ["login", "/login"],
  "/register": ["register", "/register"],
  "/register.html": ["register", "/register"],
  "/password": ["password", "/password"],
  "/password.html": ["password", "/password"],
  "/setup": ["setup", "/setup"],
  "/setup.html": ["setup", "/setup"],
  "/rooms": ["rooms", "/rooms/"],
  "/rooms/": ["rooms", "/rooms/"],
  "/encodes": ["encodes", "/encodes/"],
  "/encodes/": ["encodes", "/encodes/"],
  "/account": ["account", "/account/"],
  "/account/": ["account", "/account/"],
  "/admin": ["admin", "/admin/"],
  "/admin/": ["admin", "/admin/"],
};

/**
 * The `list` view's fixed paths (a playlist is `/playlists/{id}`). The provisional UI's library page
 * (`/library/`) and playlist list (`/playlists/`) are gone: old links show All My Songs.
 */
const LISTS: Record<string, [ListName, string]> = {
  "/library": ["songs", "/songs"],
  "/library/": ["songs", "/songs"],
  "/playlists": ["songs", "/songs"],
  "/playlists/": ["songs", "/songs"],
  "/songs": ["songs", "/songs"],
  "/songs/": ["songs", "/songs"],
  "/shows": ["shows", "/shows"],
  "/shows/": ["shows", "/shows"],
  "/liked": ["liked", "/liked"],
  "/liked/": ["liked", "/liked"],
  "/uncategorized": ["uncategorized", "/uncategorized"],
  "/uncategorized/": ["uncategorized", "/uncategorized"],
  "/search": ["search", "/search"],
  "/search/": ["search", "/search"],
};

function decodeSegment(s: string): string | undefined {
  try {
    return decodeURIComponent(s);
  } catch {
    return undefined;
  }
}

/**
 * Whether the server sends index.html for this path when no file is there (StaticFiles.isAppPath):
 * not under one of {@link SERVER_PREFIXES}, no empty, dot or dot-file segments (but a trailing
 * slash), no backslash or NUL, and not file-like (a last segment with a dot, other than the old
 * `.html` page names).
 */
export function isAppPath(pathname: string): boolean {
  if (!pathname.startsWith("/") || pathname.indexOf("\\") >= 0 || pathname.indexOf("\0") >= 0) return false;
  const segments = pathname.slice(1).split("/");
  const first = segments[0] ?? "";
  if (SERVER_PREFIXES.indexOf(first) >= 0) return false;
  for (let i = 0; i < segments.length; i++) {
    const segment = segments[i] ?? "";
    // Only the last segment may be empty (a trailing slash); no dot segments or dot-files.
    if (segment === "" ? i < segments.length - 1 : segment.startsWith(".")) return false;
  }
  const last = segments[segments.length - 1] ?? "";
  return last.indexOf(".") < 0 || last.endsWith(".html");
}

/** The route of a path on this site (`location.pathname`, still percent-encoded). */
export function routeOf(pathname: string): Route {
  if (!isAppPath(pathname)) return { kind: "other" };
  const fixed = FIXED[pathname];
  if (fixed !== undefined) return { kind: "view", view: fixed[0], params: {}, path: fixed[1] };
  const list = LISTS[pathname];
  if (list !== undefined) return { kind: "view", view: "list", params: { list: list[0] }, path: list[1] };
  let m = /^\/rooms\/([^/]+)\/?$/.exec(pathname);
  if (m !== null && m[1] !== undefined) {
    const ref = decodeSegment(m[1]);
    if (ref !== undefined && ref !== "") return { kind: "view", view: "room", params: { ref }, path: "/rooms/" + m[1] };
  }
  m = /^\/rooms\/([^/]+)\/manage\/?$/.exec(pathname);
  if (m !== null && m[1] !== undefined) {
    const ref = decodeSegment(m[1]);
    if (ref !== undefined && ref !== "") {
      return { kind: "view", view: "roomManage", params: { ref }, path: "/rooms/" + m[1] + "/manage" };
    }
  }
  m = /^\/items\/([^/]+)\/?$/.exec(pathname);
  if (m !== null && m[1] !== undefined) {
    const id = decodeSegment(m[1]);
    if (id !== undefined && id !== "") return { kind: "view", view: "item", params: { id }, path: "/items/" + m[1] };
  }
  m = /^\/playlists\/([^/]+)\/?$/.exec(pathname);
  if (m !== null && m[1] !== undefined) {
    const id = decodeSegment(m[1]);
    if (id !== undefined && id !== "") {
      return { kind: "view", view: "list", params: { list: "playlist", id }, path: "/playlists/" + m[1] };
    }
  }
  return { kind: "view", view: "notFound", params: {}, path: pathname };
}

/** The path of a playlist's view (the `list` view). */
export function playlistPath(id: string): string {
  return "/playlists/" + encodeURIComponent(id);
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
 * - "browser": leave it to the browser (a new document, a new tab, another site, ...);
 * - "hash": same view, only the fragment differs: the browser scrolls, nothing is loaded;
 * - "room": the room playing in the background: show its view again;
 * - "view": show the URL's view in place.
 */
export type Decision = "browser" | "hash" | "room" | "view";

/**
 * Decides a navigation from `current` (an absolute URL) to `href` (absolute, or relative to
 * `current`). `playingRoom` is set while a room plays in the background (kept while other views
 * are shown) and says whether a room reference (id or slug) is that room. `click` is the link click
 * that started it, if any (a script's own navigation has none).
 */
export function decide(href: string, current: string, playingRoom: ((ref: string) => boolean) | undefined,
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
  if (route.kind !== "view") return "browser";
  if (playingRoom !== undefined) {
    // Another room: switched in place (the shell takes the playing one away and the new view joins).
    if (route.view === "room") return playingRoom(route.params["ref"] ?? "") ? "room" : "view";
  }
  return "view";
}

/** The part of a URL that picks the view (path and query, not the fragment). */
export function pageKey(url: string): string {
  try {
    const u = new URL(url);
    return u.pathname + u.search;
  } catch {
    return url;
  }
}
