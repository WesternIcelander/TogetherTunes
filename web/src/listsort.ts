// Sorting the song-style lists by column (docs/ui.md#library-rules, "Sorting"): the sort state, the
// next state on a click on a heading, whether a playlist may be reordered by dragging, the API
// parameters of the paged lists (the server sorts those: GET /api/library, its views and
// GET /api/search take sort= and order=), sorting a whole loaded list (playlists) with the
// server's rules, and the remembered sort per list (localStorage, read and written by the page).
// No DOM here, so Node tests can import it.

/** A sortable column: `num` (#) is the list's own order. */
export type SortColumn = "num" | "title" | "artist" | "album" | "added" | "duration";

/** The sort of a list: a column, ascending or descending. */
export interface SortState {
  column: SortColumn;
  desc: boolean;
}

/** The default: # ascending, the list's own order (playlists: theirs; libraries: newest added first; search: relevance). */
export const DEFAULT_SORT: SortState = { column: "num", desc: false };

/** Every column, in the table's order. */
export const ALL_COLUMNS: readonly SortColumn[] = ["num", "title", "artist", "album", "added", "duration"];

/** What a column heading says. */
export const COLUMN_LABELS: Record<SortColumn, string> = {
  num: "#", title: "Title", artist: "Artist", album: "Album", added: "Date added", duration: "Time",
};

/**
 * The columns a list can be sorted by: search results have no date added (they aren't anyone's
 * list), the others every column.
 */
export function sortableColumns(list: string): readonly SortColumn[] {
  return list === "search" ? ALL_COLUMNS.filter((c) => c !== "added") : ALL_COLUMNS;
}

/** The sort after a click on `column`'s heading: the same column flips its order, another one starts ascending. */
export function nextSort(s: SortState, column: SortColumn): SortState {
  return s.column === column ? { column, desc: !s.desc } : { column, desc: false };
}

/** Whether rows may be reordered by dragging (and dropped at a position): only on # ascending. */
export function canReorder(s: SortState): boolean {
  return s.column === "num" && !s.desc;
}

/** Whether `s` is the default sort. */
export function isDefaultSort(s: SortState): boolean {
  return s.column === "num" && !s.desc;
}

/** The `aria-sort` of a heading showing `columns` (one or more). */
export function ariaSort(s: SortState, columns: readonly SortColumn[]): "ascending" | "descending" | "none" {
  if (columns.indexOf(s.column) < 0) return "none";
  return s.desc ? "descending" : "ascending";
}

/** The arrow shown on the sorted heading. */
export function sortArrow(s: SortState, column: SortColumn): string {
  if (s.column !== column) return "";
  return s.desc ? "▼" : "▲";
}

/** The query parameters of a paged list for `s` (none for the default; # descending: `order=desc`). */
export function sortParams(s: SortState): Record<string, string> {
  if (s.column === "num") return s.desc ? { order: "desc" } : {};
  return { sort: s.column, order: s.desc ? "desc" : "asc" };
}

/** The localStorage key of a list's remembered sort (`list`: the view; `id`: a playlist's id). */
export function sortStorageKey(list: string, id?: string): string {
  return "tt.listSort." + list + (id !== undefined && id !== "" ? ":" + id : "");
}

/** A remembered sort as stored (null: the default, nothing to keep). */
export function serializeSort(s: SortState): string | null {
  return isDefaultSort(s) ? null : s.column + (s.desc ? ":desc" : ":asc");
}

/** A stored sort for a list with `columns`; anything missing, broken or not sortable there is the default. */
export function parseSort(text: string | null | undefined, columns: readonly SortColumn[] = ALL_COLUMNS): SortState {
  if (typeof text !== "string") return DEFAULT_SORT;
  const m = /^([a-z]+):(asc|desc)$/.exec(text);
  if (m === null) return DEFAULT_SORT;
  const column = m[1] as SortColumn;
  if (columns.indexOf(column) < 0) return DEFAULT_SORT;
  return { column, desc: m[2] === "desc" };
}

/** What a row is sorted by. */
export interface SortFields {
  title: string;
  artist?: string | null;
  album?: string | null;
  durationMs?: number | null;
  addedAt?: number | null;
}

type Key = string | number | undefined;

function key(f: SortFields, column: SortColumn): Key {
  switch (column) {
    case "title":
    case "artist":
    case "album": {
      const v = f[column];
      return typeof v === "string" && v !== "" ? v.toLowerCase() : undefined;
    }
    case "duration": return typeof f.durationMs === "number" ? f.durationMs : undefined;
    case "added": return typeof f.addedAt === "number" ? f.addedAt : undefined;
    default: return undefined;
  }
}

/**
 * A whole loaded list (`rows` in the list's own order) sorted by `s` with the server's rules: text
 * case-insensitively (by code point), missing values (none or empty) last in both directions, ties
 * in the list's own order; # descending is the own order reversed. Returns a new array.
 */
export function sortRows<T>(rows: readonly T[], s: SortState, fields: (row: T) => SortFields): T[] {
  const indexed = rows.map((row, i) => ({ row, i, k: key(fields(row), s.column) }));
  if (s.column === "num") {
    const out = rows.slice();
    if (s.desc) out.reverse();
    return out;
  }
  indexed.sort((a, b) => {
    if (a.k === undefined || b.k === undefined) {
      if (a.k === undefined && b.k !== undefined) return 1;
      if (b.k === undefined && a.k !== undefined) return -1;
      return a.i - b.i;
    }
    if (a.k !== b.k) {
      const less = a.k < b.k;
      return (less ? -1 : 1) * (s.desc ? -1 : 1);
    }
    return a.i - b.i;
  });
  return indexed.map((x) => x.row);
}
