// The header search's dropdown (docs/ui.md#header-search): when it searches (1 s after the last
// keystroke, 2 or more characters after trimming), which answer counts (only the newest), what it
// says while loading or when empty, the keyboard (↑/↓ through the rows, Enter on a row opens it,
// else the full search page) and the paths it asks. Pure logic (no DOM), run under Node in the tests
// (src/test/resources/web-tests/searchdrop.test.mjs); searchbox.ts draws the dropdown. Also what a
// row's double-click does (adds it to the room open here, as in the lists) and the click's wait.
import { OPEN_CLICK_DELAY_MS } from "./interactions.js";
import { youtubeActions } from "./ytsearch.js";

/** How long after the last keystroke the dropdown searches. */
export const SEARCH_DELAY_MS = 1000;
/** The fewest characters (after trimming) the dropdown searches for. */
export const MIN_QUERY_CHARS = 2;
/** Library results the dropdown shows. */
export const DROPDOWN_LIMIT = 8;

export const SEARCHING_TEXT = "Searching…";
export const NOTHING_FOUND_TEXT = "Nothing found.";
export const SEE_ALL_LABEL = "See all results";
export const SEARCH_YOUTUBE_LABEL = "Search YouTube";
export const LIBRARY_RESULTS_LABEL = "← Library results";

/** The text the dropdown searches for, or undefined when it is too short (then no dropdown). */
export function dropdownQuery(text: string): string | undefined {
  const q = text.trim();
  return q.length >= MIN_QUERY_CHARS ? q : undefined;
}

/** The library search the dropdown asks (`GET /api/search`, the whole server, the caller's library first). */
export function libraryDropdownPath(q: string): string {
  return "/api/search?" + new URLSearchParams({ q, limit: String(DROPDOWN_LIMIT) }).toString();
}

/** The full search page for `q` (Enter, **See all results**). */
export function searchPagePath(q: string): string {
  return "/search?q=" + encodeURIComponent(q.trim());
}

/** setTimeout / clearTimeout, passed in so the tests can run the clock. */
export interface Timers {
  set(fn: () => void, ms: number): number;
  clear(id: number): void;
}

/**
 * The 1 s wait: every keystroke (`input`) restarts it; when it runs out with a searchable text it
 * calls `run` with the trimmed text. A text that is too short just cancels it.
 */
export class SearchTimer {
  readonly #timers: Timers;
  readonly #run: (q: string) => void;
  readonly #delay: number;
  #id: number | undefined;

  constructor(timers: Timers, run: (q: string) => void, delay = SEARCH_DELAY_MS) {
    this.#timers = timers;
    this.#run = run;
    this.#delay = delay;
  }

  /** The box's text changed. Returns whether a search is now waiting. */
  input(text: string): boolean {
    this.cancel();
    const q = dropdownQuery(text);
    if (q === undefined) return false;
    this.#id = this.#timers.set(() => {
      this.#id = undefined;
      this.#run(q);
    }, this.#delay);
    return true;
  }

  /** Whether a search is waiting. */
  pending(): boolean {
    return this.#id !== undefined;
  }

  cancel(): void {
    if (this.#id !== undefined) this.#timers.clear(this.#id);
    this.#id = undefined;
  }
}

/**
 * Only the newest answer counts: each search takes a ticket (`next`), and its answer is shown only
 * while that ticket is still `current` (a newer search, typing or closing the dropdown drops it).
 */
export class Latest {
  #n = 0;

  next(): number {
    return ++this.#n;
  }

  current(ticket: number): boolean {
    return ticket === this.#n;
  }

  /** Drops every answer still on its way. */
  drop(): void {
    this.#n++;
  }
}

/** What the dropdown shows: the library results or the YouTube results. */
export type DropMode = "library" | "youtube";

/**
 * ↑ / ↓ in the box: the row to mark next (-1 = none, the box itself). Moving past the last row or
 * above the first comes back to none; from none, ↓ marks the first row and ↑ the last.
 */
export function moveActive(active: number, count: number, dir: 1 | -1): number {
  if (count <= 0) return -1;
  const next = active + dir;
  if (active < 0) return dir > 0 ? 0 : count - 1;
  return next < 0 || next >= count ? -1 : next;
}

/** What Enter in the box does: open the marked row, or (no row marked) the full search page. */
export function enterAction(active: number, count: number): "row" | "page" {
  return active >= 0 && active < count ? "row" : "page";
}

/** The dropdown's status line: "Searching…" while loading, "Nothing found." when empty, else nothing. */
export function dropStatus(loading: boolean, count: number): string {
  if (loading) return SEARCHING_TEXT;
  return count === 0 ? NOTHING_FOUND_TEXT : "";
}

/** The room open here, as far as a dropdown row's double-click cares (`state.room`). */
export interface DropRoomLike {
  you: { canRequest: boolean; canYoutube: boolean; canRequestLibrary?: boolean; restriction?: unknown };
}

/**
 * Whether double-clicking a row of the dropdown adds it to the room open here (as double-click does
 * in the lists, interactions.ts doubleClickAction): a library row when the person may request from
 * the library there, a YouTube row when its **Add to Queue** is shown (ytsearch.ts youtubeActions).
 * Never while banned or timed out there, and never without a room: then a double-click does nothing
 * more than a click, and a click opens the row at once (no wait for a second click).
 */
export function rowDoubleClickAdds(mode: DropMode, room: DropRoomLike | undefined): boolean {
  if (room === undefined || room.you.restriction !== undefined) return false;
  if (mode === "youtube") return youtubeActions(room).indexOf("addToQueue") >= 0;
  return room.you.canRequest && room.you.canRequestLibrary !== false;
}

/**
 * A single click on a library row opens its item page, but where a double-click adds the row it
 * waits {@link OPEN_CLICK_DELAY_MS} first (the lists' wait): a second click in that time (the start
 * of a double-click) cancels it, and so does the double-click itself or closing the dropdown.
 */
export class ClickOpener {
  readonly #timers: Timers;
  readonly #delay: number;
  #id: number | undefined;

  constructor(timers: Timers, delay = OPEN_CLICK_DELAY_MS) {
    this.#timers = timers;
    this.#delay = delay;
  }

  /**
   * A click (`detail`: the event's click count) on a row; `waits`: a double-click would add the row
   * ({@link rowDoubleClickAdds}). Calls `open` at once, later, or (a second click) not at all.
   */
  click(detail: number, waits: boolean, open: () => void): void {
    this.cancel();
    if (!waits) {
      open();
      return;
    }
    if (detail > 1) return;
    this.#id = this.#timers.set(() => {
      this.#id = undefined;
      open();
    }, this.#delay);
  }

  /** Whether an open is waiting. */
  pending(): boolean {
    return this.#id !== undefined;
  }

  cancel(): void {
    if (this.#id !== undefined) this.#timers.clear(this.#id);
    this.#id = undefined;
  }
}
