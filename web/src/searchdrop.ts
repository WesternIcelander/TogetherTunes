// The header search's dropdown (docs/ui.md#header-search): when it searches (1 s after the last
// keystroke, 2 or more characters after trimming), which answer counts (only the newest), what it
// says while loading or when empty, the keyboard (↑/↓ through the rows, Enter on a row opens it,
// else the full search page) and the paths it asks. Pure logic (no DOM), run under Node in the tests
// (src/test/resources/web-tests/searchdrop.test.mjs); searchbox.ts draws the dropdown.

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
