// The interaction rules of the new UI (docs/ui.md#interaction-rules-all-layouts and
// #autoplay-from-playlists): what double-click on a song does (request it in a room; alone, what the
// user's setting says), what a click, Ctrl/Cmd-click or double-click on a playlist does to its
// autoplay selection, desktop multi-select (Shift / Ctrl / Cmd-click), and which entries the
// right-click menus (and their ⋯ equivalents) have for songs, playlists and queue entries.
// Pure logic (no DOM), run under Node in the tests (src/test/resources/web-tests/interactions.test.mjs);
// frame.ts and libview.ts draw it. The server enforces every rule.
import type { Selection } from "./mobile.js";
import { canRemove, canRetry, type ActorLike, type EntryLike, type RoomLike } from "./panels.js";

// ------------------------------------------------------------------ double-click on a song

/** The user setting `doubleClickAlone` (`GET /api/me/settings`). */
export type DoubleClickAlone = "play" | "queue";

/** What double-clicking a song does: request it (in a room), or alone play it now / queue it. */
export type DoubleClickAction = "request" | "playNow" | "queue";

/**
 * In a room double-click requests the song; alone (the user's alone room) it follows the user's
 * setting: Play immediately (the default, also for an unknown value) or Queue it.
 */
export function doubleClickAction(alone: boolean, setting: string | undefined): DoubleClickAction {
  if (!alone) return "request";
  return setting === "queue" ? "queue" : "playNow";
}

/**
 * "Play immediately" alone: after `queue.add` (which puts the song at the end of the manually added
 * section) the new entry moves before the first manually added entry that was waiting before the add
 * (`queue` = the queue before the add, in play order), then `next` starts it. Undefined: no move is
 * needed (nothing manual was waiting, so the new entry is already first: the alone room plays
 * requests first).
 */
export function playNowBefore(queueBefore: readonly EntryLike[]): string | undefined {
  for (const e of queueBefore) {
    if (e.section !== "autoplay") return e.entryId;
  }
  return undefined;
}

// ------------------------------------------------------------------ playlists and autoplay

/** The modifier keys of a click. */
export interface Mods {
  shiftKey: boolean;
  ctrlKey: boolean;
  metaKey: boolean;
}

/** The "toggle" modifier: Ctrl, or Cmd on macOS (both are accepted everywhere). */
export function toggleKey(m: Mods): boolean {
  return m.ctrlKey || m.metaKey;
}

/**
 * A click on a playlist in the sidebar (with a room open): a plain click opens it, Ctrl-click
 * (Cmd-click) toggles it for autoplay; double-click makes it the only one (`"only"`, from the
 * dblclick event). Without a room every click opens it.
 */
export function playlistClick(m: Mods, roomOpen: boolean): "open" | "toggle" {
  return roomOpen && toggleKey(m) ? "toggle" : "open";
}

export type PlaylistAction = "use" | "stopUsing" | "only" | "open" | "rename" | "delete";

export const PLAYLIST_ACTION_LABELS: Readonly<Record<PlaylistAction, string>> = {
  use: "Use for autoplay",
  stopUsing: "Stop using for autoplay",
  only: "Use only this one for autoplay",
  open: "Open",
  rename: "Rename…",
  delete: "Delete…",
};

/**
 * The playlist's right-click menu (⋯ / long press on touch): the autoplay choices while a room is
 * open, Open, and Rename / Delete for the user's own playlists (never for Liked Songs).
 */
export function playlistMenu(o: { roomOpen: boolean; selected: boolean; builtin: boolean }): PlaylistAction[] {
  const out: PlaylistAction[] = [];
  if (o.roomOpen) out.push(o.selected ? "stopUsing" : "use", "only");
  out.push("open");
  if (!o.builtin) out.push("rename", "delete");
  return out;
}

/** A playlist name typed in the Rename / New Playlist prompt: trimmed, or undefined when blank or unchanged. */
export function playlistNameInput(input: string | null, old?: string): string | undefined {
  if (input === null) return undefined;
  const name = input.trim();
  if (name === "" || name === old) return undefined;
  return name;
}

// ------------------------------------------------------------------ multi-select (desktop)

/**
 * A click on a list row with the desktop's multi-select: Shift-click selects the range from the
 * anchor (added to the selection with Ctrl/Cmd too), Ctrl/Cmd-click toggles the row, a plain click
 * selects only that row. `rows` are the row keys in list order. Returns the new anchor.
 */
export function clickSelect(sel: Selection, rows: readonly string[], key: string, m: Mods,
    anchor: string | undefined): string {
  if (m.shiftKey && anchor !== undefined && rows.indexOf(anchor) >= 0) {
    const a = rows.indexOf(anchor);
    const b = rows.indexOf(key);
    if (b >= 0) {
      if (!toggleKey(m)) sel.clear();
      const [from, to] = a <= b ? [a, b] : [b, a];
      for (let i = from; i <= to; i++) {
        const k = rows[i];
        if (k !== undefined && !sel.has(k)) sel.toggle(k);
      }
      return anchor;
    }
  }
  if (toggleKey(m)) {
    sel.toggle(key);
    return key;
  }
  sel.clear();
  sel.toggle(key);
  return key;
}

/** The selection bar's text on the desktop. */
export function selectionText(n: number): string {
  return n === 1 ? "1 song selected" : n + " songs selected";
}

// ------------------------------------------------------------------ the song menu

export type SongAction = "request" | "playNow" | "queue" | "open" | "copyLink" | "removeFromPlaylist"
  | "unlike" | "removeFromLibrary" | "select";

export const SONG_ACTION_LABELS: Readonly<Record<SongAction, string>> = {
  request: "Add to Queue",
  playNow: "Play now",
  queue: "Add to Queue",
  open: "Open",
  copyLink: "Copy link",
  removeFromPlaylist: "Remove from playlist",
  unlike: "Remove from Liked Songs",
  removeFromLibrary: "Remove from library…",
  select: "Select",
};

/** Which list the song is in (libview.ts's lists). */
export type SongList = "library" | "playlist" | "liked" | "othersPlaylist" | "search";

/**
 * A song row's right-click menu (⋯ / long press): Add to Queue in a room (alone: Play now and Add
 * to Queue), Open, Copy link, removing it from the list it is in where the user may (their own
 * playlist, Liked Songs, their library), and Select.
 */
export function songMenu(o: { room: boolean; alone: boolean; list: SongList }): SongAction[] {
  const out: SongAction[] = [];
  if (o.room) out.push(...(o.alone ? ["playNow", "queue"] as SongAction[] : ["request"] as SongAction[]));
  out.push("open", "copyLink");
  if (o.list === "playlist") out.push("removeFromPlaylist");
  if (o.list === "liked") out.push("unlike");
  if (o.list === "library") out.push("removeFromLibrary");
  out.push("select");
  return out;
}

// ------------------------------------------------------------------ queue entry menus

export type EntryAction = "voteUp" | "unvote" | "pin" | "unpin" | "request" | "remove" | "withdraw"
  | "accept" | "reject" | "retry" | "skip" | "open";

export const ENTRY_ACTION_LABELS: Readonly<Record<EntryAction, string>> = {
  voteUp: "Vote up",
  unvote: "Take back your vote",
  pin: "Pin to top",
  unpin: "Unpin",
  request: "Request it",
  remove: "Remove from the queue",
  withdraw: "Withdraw",
  accept: "Accept",
  reject: "Reject",
  retry: "Retry",
  skip: "Skip",
  open: "Open",
};

/** The parts of a waiting entry the menu reads. */
export interface MenuEntryLike extends EntryLike {
  pinned?: boolean;
  yourVote?: number;
}

/**
 * The right-click menu of a waiting entry (⋯ / long press): what its buttons do plus **Pin to top**
 * / Unpin (vote mode, owner and moderators, requests only: autoplay entries can't be pinned) and
 * Open. Suggestions: Accept / Reject (owner and moderators) or Withdraw (the one who suggested it).
 */
export function entryMenu(s: RoomLike, e: MenuEntryLike, actor: ActorLike | undefined, suggestion: boolean):
    EntryAction[] {
  const you = s.room.you;
  const readOnly = you.restriction !== undefined;
  const out: EntryAction[] = [];
  if (suggestion) {
    if (you.canModerate && !readOnly) out.push("accept", "reject");
    else if (canRemove(s, e, actor)) out.push("withdraw");
    out.push("open");
    return out;
  }
  const autoplay = e.section === "autoplay";
  if (!autoplay && s.room.controlMode === "vote" && you.canVote && !readOnly) {
    out.push(e.yourVote === 1 ? "unvote" : "voteUp");
  }
  if (!autoplay && s.room.controlMode === "vote" && you.canModerate && !readOnly) {
    out.push(e.pinned === true ? "unpin" : "pin");
  }
  if (autoplay && you.canRequest && you.canRequestLibrary !== false && !readOnly) out.push("request");
  if (canRetry(s, e, actor)) out.push("retry");
  if (canRemove(s, e, actor)) out.push("remove");
  out.push("open");
  return out;
}

/** The room socket message for an entry action (undefined for Open, which navigates). */
export function entryMessage(a: EntryAction, entryId: string): Record<string, unknown> | undefined {
  switch (a) {
    case "voteUp": return { type: "vote", entryId, value: 1 };
    case "unvote": return { type: "vote", entryId, value: 0 };
    case "pin": return { type: "queue.pin", entryId, pinned: true };
    case "unpin": return { type: "queue.pin", entryId, pinned: false };
    case "request": return { type: "queue.request", entryId };
    case "remove":
    case "withdraw": return { type: "queue.remove", entryId };
    case "accept": return { type: "suggest.accept", entryId };
    case "reject": return { type: "suggest.reject", entryId };
    case "retry": return { type: "queue.retry", entryId };
    case "skip": return { type: "next" };
    case "open": return undefined;
  }
}
