// The mobile layout's rules (docs/ui.md#mobile-narrow-windows): which bottom tab a page belongs to,
// where the top bar's back button goes, what a tab does when tapped, long-press thresholds and the
// Select mode's selection. Pure logic (no DOM), run under Node in the tests
// (src/test/resources/web-tests/mobile.test.mjs); frame.ts and libview.ts draw it.

/** Below this width (CSS px) the mobile layout is used; an iPad in landscape gets the desktop one. */
export const MOBILE_MAX_WIDTH = 800;

/** The media query for the mobile layout (main.css uses the same width). */
export const MOBILE_QUERY = "(max-width: 799.98px)";

/** The bottom tabs. */
export type MobileTab = "library" | "now" | "room" | "search" | "account";

export const MOBILE_TABS: readonly { tab: MobileTab; label: string }[] = [
  { tab: "library", label: "Library" },
  { tab: "now", label: "Now Playing" },
  { tab: "room", label: "Room" },
  { tab: "search", label: "Search" },
  { tab: "account", label: "Account" },
];

/**
 * A screen the frame shows instead of the view (the view stays underneath, hidden): the library's
 * list (the desktop sidebar's Library tab), Now Playing (video, Up Next | Recents), the room list
 * (Room tab → Rooms) and the account menu. "" = the view itself.
 */
export type MobilePane = "" | "library" | "now" | "rooms" | "account";

export const PANE_TITLES: Readonly<Record<Exclude<MobilePane, "">, string>> = {
  library: "Library",
  now: "Now Playing",
  rooms: "Room",
  account: "Account",
};

/** Whether a value (from history.state) is a pane name. */
export function isPane(v: unknown): v is MobilePane {
  return v === "" || v === "library" || v === "now" || v === "rooms" || v === "account";
}

/** Where the user is: the pane shown over the view (or ""), the view's name and, for the `list` view, which list. */
export interface MobilePlace {
  pane: MobilePane;
  /** router.ts ViewName (a string here, so this module stays free of the router). */
  view: string;
  list?: string;
}

/** The bottom tab a place belongs to (highlighted). */
export function mobileTab(p: MobilePlace): MobileTab {
  switch (p.pane) {
    case "library": return "library";
    case "now": return "now";
    case "rooms": return "room";
    case "account": return "account";
    default: break;
  }
  switch (p.view) {
    case "list": return p.list === "search" ? "search" : "library";
    case "rooms":
    case "room":
    case "roomManage":
      return "room";
    case "account":
    case "admin":
    case "encodes":
    case "library":
    case "playlists":
    case "login":
    case "register":
    case "password":
    case "setup":
      return "account";
    default:
      return "library";
  }
}

/** Where the top bar's back button goes: a pane, or back in the browser history. */
export interface BackTarget {
  label: string;
  pane?: Exclude<MobilePane, "">;
  history?: true;
}

/**
 * The top bar's back button for a place, or undefined (a tab's own first screen has none):
 * a list → "‹ Library", the room view → "‹ Rooms", the pages of the account menu → "‹ Account",
 * an item page or Manage Room → "‹ Back" (browser history).
 */
export function mobileBack(p: MobilePlace): BackTarget | undefined {
  if (p.pane !== "") return undefined;
  switch (p.view) {
    case "list":
      return p.list === "search" ? undefined : { label: "Library", pane: "library" };
    case "home":
      return { label: "Library", pane: "library" };
    case "room":
    case "rooms":
      return { label: "Rooms", pane: "rooms" };
    case "account":
    case "admin":
    case "encodes":
    case "library":
    case "playlists":
      return { label: "Account", pane: "account" };
    case "item":
    case "roomManage":
    case "notFound":
      return { label: "Back", history: true };
    default:
      return undefined;
  }
}

/** What tapping a bottom tab does: show a pane, or navigate to a path. */
export interface TabAction {
  pane?: Exclude<MobilePane, "">;
  path?: string;
}

/**
 * Tapping a tab: Library → the library's list; Now Playing → its pane; Room → the room view of the
 * room open here ("This Room"), or the room list without one (or when already on the room view);
 * Search → the search view; Account → the account menu.
 * `roomPath` is the room open here, if any; `place` where the user is now.
 */
export function tabAction(tab: MobileTab, place: MobilePlace, roomPath: string | undefined): TabAction {
  switch (tab) {
    case "library": return { pane: "library" };
    case "now": return { pane: "now" };
    case "search": return { path: "/search" };
    case "account": return { pane: "account" };
    case "room":
      if (roomPath !== undefined && !(place.pane === "" && place.view === "room")) return { path: roomPath };
      return { pane: "rooms" };
  }
}

/** The Room tab's sub-tab shown: Rooms (the list) or This Room (the room view). */
export function roomSubTab(p: MobilePlace): "rooms" | "this" | undefined {
  if (mobileTab(p) !== "room") return undefined;
  return p.pane === "" && (p.view === "room" || p.view === "roomManage") ? "this" : "rooms";
}

/** How long a touch has to stay down to count as a long press (instead of right-click). */
export const LONG_PRESS_MS = 500;

/** How far (CSS px) a touch may move and still be a long press (more is a scroll). */
export const LONG_PRESS_SLOP_PX = 10;

/** Whether a touch moved too far from where it started to be a long press. */
export function movedTooFar(dx: number, dy: number): boolean {
  return dx * dx + dy * dy > LONG_PRESS_SLOP_PX * LONG_PRESS_SLOP_PX;
}

/**
 * The Select mode's selection (multi-select on touch): row keys, toggled one at a time. `inOrder`
 * gives them in the list's order (Add to Queue plays them in that order), whatever order they were
 * picked in.
 */
export class Selection {
  readonly #keys: string[] = [];

  get size(): number {
    return this.#keys.length;
  }

  has(key: string): boolean {
    return this.#keys.indexOf(key) >= 0;
  }

  /** Selects or deselects `key`; returns whether it is selected now. */
  toggle(key: string): boolean {
    const i = this.#keys.indexOf(key);
    if (i >= 0) {
      this.#keys.splice(i, 1);
      return false;
    }
    this.#keys.push(key);
    return true;
  }

  clear(): void {
    this.#keys.length = 0;
  }

  /** The selected keys in the order of `rows` (keys not in `rows` are left out). */
  inOrder(rows: readonly string[]): string[] {
    return rows.filter((k) => this.has(k));
  }
}

/** The Select mode's bar text. */
export function selectedText(n: number): string {
  return n === 0 ? "Tap songs to select them" : n + " selected";
}

/**
 * The top bar's title shows once the page's own large title has scrolled under the bar: its
 * bottom edge (viewport px) at or above the bar's bottom edge. Without a large title it always shows.
 */
export function showBarTitle(largeTitleBottom: number | undefined, barBottom: number): boolean {
  return largeTitleBottom === undefined || largeTitleBottom <= barBottom;
}
