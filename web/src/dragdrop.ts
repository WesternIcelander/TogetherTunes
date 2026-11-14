// Drag and drop rules of the new UI (docs/ui.md#library-rules, "Drag and drop" and "Reordering by
// dragging"): what a drag carries, what a drop target accepts, where in a list a drop lands, and
// what the drop does (move playlist entries, add items to a playlist, reorder the user's playlists,
// move queue entries between positions and sections, add items to the queue, upload files, open
// Add Link for a link). Pure logic (no DOM), run under Node in the tests
// (src/test/resources/web-tests/dragdrop.test.mjs); dnd.ts, libview.ts and frame.ts wire it to the
// page. The server enforces every rule (queue.move's control mode and rights, playlist ownership).
import type { RoomLike } from "./panels.js";

/** The data type of a drag that starts on this page (also put on the drag as JSON). */
export const DRAG_TYPE = "application/x-togethertunes";

/** Where a dragged song comes from. */
export type DragSource = "library" | "playlist" | "liked" | "othersPlaylist" | "search" | "queue" | "recents";

/** Songs being dragged (one or several: multi-select drags all selected rows, in list order). */
export interface ItemsDrag {
  kind: "items";
  source: DragSource;
  itemIds: string[];
  /** The playlist they are entries of (playlist, Liked Songs), with `entryIds` in the same order. */
  playlistId?: string;
  /** Playlist entry ids (with `playlistId`) or queue entry ids (source `queue`). */
  entryIds?: string[];
}

/** One of the user's playlists being dragged in the sidebar (to reorder them). */
export interface PlaylistDrag {
  kind: "playlist";
  playlistId: string;
}

export type DragPayload = ItemsDrag | PlaylistDrag;

const SOURCES: readonly string[] = ["library", "playlist", "liked", "othersPlaylist", "search", "queue", "recents"];

function strings(v: unknown): string[] | undefined {
  if (!Array.isArray(v) || v.length === 0) return undefined;
  for (const s of v) if (typeof s !== "string" || s === "") return undefined;
  return v as string[];
}

/** The payload as the drag's data. */
export function encodePayload(p: DragPayload): string {
  return JSON.stringify(p);
}

/** A drag's data read back; undefined when it isn't one of ours (or is malformed). */
export function decodePayload(s: string | null | undefined): DragPayload | undefined {
  if (s === null || s === undefined || s === "") return undefined;
  let v: unknown;
  try {
    v = JSON.parse(s);
  } catch {
    return undefined;
  }
  if (typeof v !== "object" || v === null) return undefined;
  const o = v as Record<string, unknown>;
  if (o["kind"] === "playlist") {
    return typeof o["playlistId"] === "string" && o["playlistId"] !== ""
      ? { kind: "playlist", playlistId: o["playlistId"] } : undefined;
  }
  if (o["kind"] !== "items" || typeof o["source"] !== "string" || !SOURCES.includes(o["source"])) return undefined;
  const itemIds = strings(o["itemIds"]);
  if (itemIds === undefined) return undefined;
  const out: ItemsDrag = { kind: "items", source: o["source"] as DragSource, itemIds };
  if (o["entryIds"] !== undefined) {
    const entryIds = strings(o["entryIds"]);
    if (entryIds === undefined || entryIds.length !== itemIds.length) return undefined;
    out.entryIds = entryIds;
  }
  if (typeof o["playlistId"] === "string" && o["playlistId"] !== "") out.playlistId = o["playlistId"];
  return out;
}

/** What is being dragged over a target, from the drag's data types (all that is readable before the drop). */
export type DropContent = "internal" | "files" | "link" | "none";

export function dropContent(types: readonly string[]): DropContent {
  if (types.includes(DRAG_TYPE)) return "internal";
  if (types.includes("Files")) return "files";
  if (types.includes("text/uri-list") || types.includes("text/plain")) return "link";
  return "none";
}

/** The first http(s) link of a dropped `text/uri-list` (comments start with #) or plain text. */
export function linkFromDrop(uriList: string, text: string): string | undefined {
  const lines = (uriList + "\n" + text).split(/\r?\n/);
  for (const raw of lines) {
    const line = raw.trim();
    if (line === "" || line.startsWith("#")) continue;
    if (/^https?:\/\/\S+$/i.test(line)) return line;
  }
  return undefined;
}

/**
 * Where a drop over a list lands: before the row under the pointer (its upper half) or before the
 * row after it (lower half); `null` = at the end. `over` undefined (below the rows): the end.
 */
export function dropBefore(keys: readonly string[], over: string | undefined, lowerHalf: boolean): string | null {
  if (over === undefined) return null;
  const i = keys.indexOf(over);
  if (i < 0) return null;
  if (!lowerHalf) return over;
  return keys[i + 1] ?? null;
}

/**
 * The rows a drag starting on `key` carries: every selected row (in list order) when `key` is one
 * of them, else just `key`.
 */
export function draggedKeys(selected: (key: string) => boolean, rows: readonly string[], key: string): string[] {
  if (!selected(key)) return [key];
  const out = rows.filter((k) => selected(k));
  return out.length > 0 ? out : [key];
}

/**
 * Whether a move is a no-op: the moved ids already sit, in that order, right before `before`
 * (`null`: at the end) in `order`.
 */
export function moveIsNoop(order: readonly string[], moved: readonly string[], before: string | null): boolean {
  if (moved.length === 0) return true;
  const first = order.indexOf(moved[0] as string);
  if (first < 0) return false;
  for (let i = 0; i < moved.length; i++) if (order[first + i] !== moved[i]) return false;
  const next = order[first + moved.length];
  return before === null ? next === undefined : next === before || moved.includes(before);
}

/** `PUT /api/playlists/order` for dropping `moved` before `before` (`null`: to the end). */
export function playlistOrderBody(moved: string, before: string | null): { playlistIds: string[]; beforePlaylistId: string | null } {
  return { playlistIds: [moved], beforePlaylistId: before === moved ? null : before };
}

// ------------------------------------------------------------------ playlists as drop targets

/** A playlist as a drop target: its id, and where the drop lands (the sidebar: at the end). */
export interface PlaylistTarget {
  playlistId: string;
  /** The drop is on the open playlist's rows (a position), not on its name in the sidebar. */
  inView: boolean;
}

export type PlaylistDrop =
  | { kind: "move"; entryIds: string[] }
  | { kind: "add"; itemIds: string[] }
  | { kind: "none" };

/**
 * What dropping songs on one of the user's playlists does: entries of the same playlist dropped
 * on its open view move there (`POST …/entries/move`); songs from anywhere else (another playlist,
 * the queue, Recents, search results, the library) are added (`POST …/entries`, each, in order).
 * The same playlist's entries dropped on its name in the sidebar change nothing.
 */
export function playlistDrop(t: PlaylistTarget, p: DragPayload | undefined): PlaylistDrop {
  if (p === undefined || p.kind !== "items") return { kind: "none" };
  const same = p.playlistId === t.playlistId && p.entryIds !== undefined && p.source !== "queue";
  if (same) return t.inView ? { kind: "move", entryIds: p.entryIds as string[] } : { kind: "none" };
  return { kind: "add", itemIds: p.itemIds };
}

// ------------------------------------------------------------------ the queue

/** The room state parts the queue rules read (the alone room: `room.alone`). */
export interface QueueRoomLike extends RoomLike {
  room: RoomLike["room"] & { alone?: boolean };
}

/**
 * Whether the viewer may reorder the queue and move entries between its sections
 * (docs/rooms.md#queue-editing): never in vote mode (votes order the requests); in owner mode the
 * owner and moderators; in open mode anyone with request rights; alone always (the user owns the
 * alone room). Banned or timed-out people can't.
 */
export function canMoveQueue(s: QueueRoomLike): boolean {
  const you = s.room.you;
  if (you.restriction !== undefined) return false;
  if (s.room.alone === true) return true;
  switch (s.room.controlMode) {
    case "owner": return you.canModerate;
    case "open": return you.canModerate || (you.canRequest && you.identified);
    default: return false;
  }
}

/** Why the queue can't be reordered here (for a notice when someone tries). */
export function queueMoveRefusal(s: QueueRoomLike): string {
  if (s.room.you.restriction !== undefined) return "You can't change this room's queue right now.";
  if (s.room.controlMode === "vote" && s.room.alone !== true) {
    return "The queue is ordered by votes in this room, so it can't be reordered by dragging.";
  }
  return "Only the room's owner and moderators can reorder its queue.";
}

export type QueueSection = "manual" | "autoplay";

export type QueueDrop =
  | { kind: "move"; message: Record<string, unknown> }
  | { kind: "add"; itemIds: string[] }
  | { kind: "refused"; reason: string }
  | { kind: "none" };

/**
 * What dropping on a queue section (Requests = `manual`, From Playlist = `autoplay`) at `before`
 * (an entry id of that section, `null`: its end) does: queue entries move (`queue.move`, which may
 * change their section; only with the rights, `canMoveQueue`); songs from elsewhere are requested
 * (`queue.add`, each, in order; the server's request rules apply).
 */
export function queueDrop(s: QueueRoomLike, p: DragPayload | undefined, section: QueueSection, before: string | null):
    QueueDrop {
  if (p === undefined || p.kind !== "items") return { kind: "none" };
  if (p.source === "queue") {
    // Now Playing and suggestions drag out only (to playlists).
    if (p.entryIds === undefined) return { kind: "none" };
    if (!canMoveQueue(s)) return { kind: "refused", reason: queueMoveRefusal(s) };
    const msg: Record<string, unknown> = { type: "queue.move", entryIds: p.entryIds, beforeEntryId: before, section };
    return { kind: "move", message: msg };
  }
  return { kind: "add", itemIds: p.itemIds };
}

/** Whether a drag over a queue section shows a position marker (a move it may make there). */
export function queueShowsPosition(s: QueueRoomLike, p: DragPayload | undefined): boolean {
  return p !== undefined && p.kind === "items" && p.source === "queue" && canMoveQueue(s);
}

// ------------------------------------------------------------------ dropped files

const VIDEO_EXTENSIONS = ["mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts",
  "mts", "3gp", "ogv", "vob"];

/** Whether a dropped file looks like a video (its type, else its extension): only videos get a type. */
export function isVideoFile(f: { name: string; type: string }): boolean {
  if (f.type.startsWith("video/")) return true;
  if (f.type !== "" && f.type !== "application/octet-stream") return false;
  const dot = f.name.lastIndexOf(".");
  return dot >= 0 && VIDEO_EXTENSIONS.includes(f.name.substring(dot + 1).toLowerCase());
}

/** The type question for dropped files: none (no videos), for one video, or one type for several. */
export function fileDropQuestion(files: readonly { name: string; type: string }[]):
    { videos: number; ask: "none" | "one" | "several"; name?: string } {
  const videos = files.filter(isVideoFile);
  if (videos.length === 0) return { videos: 0, ask: "none" };
  if (videos.length === 1) return { videos: 1, ask: "one", name: (videos[0] as { name: string }).name };
  return { videos: videos.length, ask: "several" };
}

/** One dropped file's line in the upload box. */
export function uploadLine(state: string, sent: number, size: number, checked?: number): string {
  switch (state) {
    case "checking": return "Checking" + (checked !== undefined ? " " + Math.floor(checked * 100) + "%" : "") + "…";
    case "starting": return "Starting…";
    case "uploading": return "Uploading " + (size > 0 ? Math.floor((sent / size) * 100) : 0) + "%";
    case "paused": return "Paused";
    case "retrying": return "Retrying…";
    case "processing": return "Finishing…";
    case "done": return "Uploaded";
    case "failed": return "Failed";
    default: return state;
  }
}
