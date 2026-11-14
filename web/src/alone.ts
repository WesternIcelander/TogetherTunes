// Alone and switching rooms (docs/ui.md#alone-and-in-rooms, docs/rooms.md#alone), the DOM-free rules.
// A signed-in user is always somewhere: in a room, or alone in their alone room (a private
// pseudo-room the same room view and player play). The play session (`GET /api/me/play-session`)
// says which; the shell plays that room in the background when no room view is open. Opening another
// room joins it (`POST …/join`, which saves the alone state); New Room moves the alone state into the
// new room (`moveAloneSession`); leaving a room, or the room being deleted, asks fork or restore
// (`POST …/leave`). Recents alone show the user's own play history, with the room for songs that
// played in a room.

/** `GET /api/me/play-session`. */
export interface PlaySession {
  aloneRoomId: string;
  /** The room the user is in (absent: alone). */
  roomId?: string;
  /** A room deleted while the user was in it: fork or restore is still to be chosen. */
  deletedRoom?: { name: string };
}

export type LeaveChoice = "fork" | "restore";

/** Who is here, as far as these rules care (`GET /api/me`). */
export interface MeLike {
  kind: string;
  isGuest?: boolean;
}

/** Whether `me` has an alone state (users only: guests exist only inside rooms). */
export function canBeAlone(me: MeLike | undefined): boolean {
  return me !== undefined && me.kind === "user" && me.isGuest !== true;
}

/** The room view's path for a room id or slug. */
export function roomPath(ref: string): string {
  return "/rooms/" + encodeURIComponent(ref);
}

/** The room to play when no room view is open: the room the user is in, otherwise their alone room. */
export function sessionRoom(s: PlaySession): string {
  return s.roomId !== undefined && s.roomId !== "" ? s.roomId : s.aloneRoomId;
}

/** Whether a deleted room still waits for the fork / restore choice. */
export function needsLeaveChoice(s: PlaySession): boolean {
  return s.deletedRoom !== undefined;
}

/**
 * Whether opening a room's view should tell the server the user joined it: users only, never
 * their alone room, and not the room they're already in.
 */
export function shouldJoin(me: MeLike | undefined, room: { id: string; alone?: boolean }, s?: PlaySession): boolean {
  if (!canBeAlone(me) || room.alone === true) return false;
  return s === undefined || s.roomId !== room.id;
}

/**
 * What Leave does: users in a room ask fork or restore ("ask"); in the alone room there is nothing
 * to leave ("none"); guests and anonymous visitors just stop the room here ("stop").
 */
export function leaveKind(me: MeLike | undefined, alone: boolean): "ask" | "none" | "stop" {
  if (!canBeAlone(me)) return "stop";
  return alone ? "none" : "ask";
}

/** The texts of the fork / restore question. */
export interface LeaveOffer {
  question: string;
  fork: string;
  restore: string;
  /** Whether the user may cancel (not when the room is gone). */
  cancellable: boolean;
}

/** The fork / restore question when leaving `roomName` (or after it was deleted). */
export function leaveOffer(roomName: string, deleted: boolean): LeaveOffer {
  const name = roomName.trim() === "" ? "the room" : "“" + roomName + "”";
  return {
    question: deleted
      ? "The room " + name + " was deleted. What do you want to play now?"
      : "Leave " + name + " and play on your own. What do you want to play?",
    fork: deleted ? "Keep playing its song and queue" : "Take its song and queue with me",
    restore: "Go back to what I was playing before",
    cancellable: !deleted,
  };
}

/** A row of `GET /api/me/history`. */
export interface PlayHistoryRow {
  id: string;
  playedAt: number;
  item: { id: string; title?: string; artist?: string };
  room?: { id?: string; name: string };
}

/** Where a song of the user's play history played: "" alone, otherwise "in <room>". */
export function historyPlace(r: PlayHistoryRow): string {
  if (r.room === undefined) return "";
  return "in " + (r.room.name === "" ? "a room" : r.room.name) + (r.room.id === undefined ? " (deleted)" : "");
}

/** The link to the room a history row played in, if it still exists. */
export function historyRoomPath(r: PlayHistoryRow): string | undefined {
  return r.room?.id !== undefined ? roomPath(r.room.id) : undefined;
}

/**
 * Whether the Recents tab should show the user's own play history: in their alone room (in a room
 * it shows the room's history).
 */
export function recentsFromHistory(state: { room: { alone?: boolean } } | undefined): boolean {
  return state?.room.alone === true;
}

/** The body of `POST /api/rooms` for a user: the alone state moves into the new room. */
export function createRoomBody<T extends object>(body: T, me: MeLike | undefined): T & { moveAloneSession?: true } {
  return canBeAlone(me) ? { ...body, moveAloneSession: true } : body;
}
