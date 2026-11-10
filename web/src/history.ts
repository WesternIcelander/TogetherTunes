// The room's history (docs/rooms.md#history), DOM-free: what each row offers the viewer and how
// pages of `GET /api/rooms/{room}/history` are merged. The room page renders it.
//
// - "Add to Library" on every row whose item isn't in the viewer's library (signed-in users only;
//   guests have no library); "In your library" on rows that are. A purged or failed upload can't
//   be restored, so it gets neither button; a purged YouTube item is fetched again.
// - "Add to Queue": the owner and moderators on every row; others only where they may request
//   from the library, disabled for an item played within the room's last `noRepeatWindow` entries
//   (the server refuses it the same way: 409 recently_played).
import type { ActorRef } from "./actor.js";
import type { ItemSummary } from "./media.js";

/** One row of `GET /api/rooms/{room}/history`. */
export interface HistoryRow {
  entryId: string;
  state: "played" | "skipped" | string;
  playedAt: number;
  item: ItemSummary;
  addedBy?: ActorRef;
  via?: "library" | "upload" | "youtube" | "playlist" | "history" | string;
  /** Users only: the item is in the viewer's library. */
  inYourLibrary?: boolean;
  /** Within the room's last `noRepeatWindow` entries. */
  recentlyPlayed: boolean;
}

export interface HistoryPage {
  items: HistoryRow[];
  nextCursor?: string;
  noRepeatWindow: number;
}

/** Who is looking, from the room state's `you` and the session. */
export interface HistoryViewer {
  /** A signed-in user (has a library). */
  user: boolean;
  /** Owner or moderator (exempt from the request rules). */
  moderates: boolean;
  /** May request items by id here (`you.canRequestLibrary`). */
  canRequestLibrary: boolean;
}

export type LibraryAction = "add" | "in-library" | "none";

/** Whether the item's media is gone for good (a purged or failed upload). */
function lost(item: ItemSummary): boolean {
  return (item.status === "purged" || item.status === "failed") && (item.youtubeId === undefined || item.youtubeId === "");
}

export function libraryAction(row: HistoryRow, viewer: HistoryViewer): LibraryAction {
  if (!viewer.user) return "none";
  if (row.inYourLibrary === true) return "in-library";
  return lost(row.item) ? "none" : "add";
}

export interface QueueAction {
  shown: boolean;
  disabled: boolean;
  reason?: string;
}

export function queueAction(row: HistoryRow, viewer: HistoryViewer, noRepeatWindow: number): QueueAction {
  if (!viewer.moderates && !viewer.canRequestLibrary) return { shown: false, disabled: true };
  if (lost(row.item)) return { shown: true, disabled: true, reason: "This item's media has expired" };
  if (!viewer.moderates && noRepeatWindow > 0 && row.recentlyPlayed) {
    return { shown: true, disabled: true,
      reason: "Played within the last " + noRepeatWindow + (noRepeatWindow === 1 ? " song" : " songs") };
  }
  return { shown: true, disabled: false };
}

const VIA_TEXT: Record<string, string> = {
  library: "from a library", upload: "by upload", youtube: "by YouTube link", playlist: "from a playlist",
  history: "from the history",
};

/** How the entry was added, as words ("by upload"), or "" when unknown. */
export function viaText(via: string | undefined): string {
  return via === undefined ? "" : VIA_TEXT[via] ?? via;
}

/**
 * `shown` with a freshly loaded first page merged in: rows of the page replace the shown rows with
 * the same entry id, rows newer than everything shown come first, and older shown rows (loaded by
 * "Show more") stay. Newest first, by `playedAt` then entry id, like the server.
 */
export function mergeFirstPage(shown: HistoryRow[], page: HistoryRow[]): HistoryRow[] {
  const byId = new Map<string, HistoryRow>();
  for (const r of shown) byId.set(r.entryId, r);
  for (const r of page) byId.set(r.entryId, r);
  // Shown rows newer than the page's oldest that the page doesn't have any more were trimmed or
  // replayed ("previous"): drop them.
  const oldest = page.length > 0 ? page[page.length - 1] : undefined;
  const inPage = new Set(page.map((r) => r.entryId));
  const out = Array.from(byId.values()).filter((r) => inPage.has(r.entryId) || oldest === undefined
    || compare(r, oldest) > 0);
  out.sort(compare);
  return out;
}

/** Sort order: newest first. Positive if `a` comes after `b`. */
function compare(a: HistoryRow, b: HistoryRow): number {
  if (a.playedAt !== b.playedAt) return b.playedAt - a.playedAt;
  return a.entryId < b.entryId ? 1 : a.entryId > b.entryId ? -1 : 0;
}

/** Marks every row of `itemId` as in the viewer's library (after Add to Library). */
export function markInLibrary(rows: HistoryRow[], itemId: string): void {
  for (const r of rows) {
    if (r.item.id === itemId) r.inYourLibrary = true;
  }
}
