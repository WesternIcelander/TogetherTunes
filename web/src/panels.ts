// The desktop layout's rules (docs/ui.md#layout-desktop), DOM-free so they run under Node in the
// tests (src/test/resources/web-tests/panels.test.mjs): what the right panel's Queue tab shows and
// who gets which button, what the player bar offers by rights, "mute until end of song", the
// sidebar's lists, and small formatting. The page code (frame.ts, libview.ts) only draws what these
// return; every rule is enforced by the server as well.
import { canRetryEntry } from "./jobtext.js";

/** The parts of a queued entry these rules read (roomclient.ts `WaitingEntry`). */
export interface EntryLike {
  entryId: string;
  status: string;
  /** `manual` (requests) or `autoplay` (From Playlist); absent from older servers: manual. */
  section?: string;
  addedBy?: { kind: string; id: string };
  item: { source?: string; durationMs?: number };
}

/** The parts of `room.you` these rules read. */
export interface YouLike {
  identified: boolean;
  canVote: boolean;
  canRequest: boolean;
  canYoutube: boolean;
  canSkip: boolean;
  canModerate: boolean;
  canRequestLibrary?: boolean;
  restriction?: { kind: string; until?: number };
}

/** The parts of the room state these rules read. */
export interface RoomLike<E extends EntryLike = EntryLike> {
  room: { controlMode: string; you: YouLike; settings: Record<string, unknown> };
  queue: E[];
  suggestions: E[];
}

/** Who is looking: the socket's actor (undefined: anonymous). */
export interface ActorLike { kind: string; id: string }

/** The Queue tab's two lists: Requests (manually added) and From Playlist (autoplay). */
export function queueSections<E extends EntryLike>(queue: readonly E[]): { requests: E[]; fromPlaylist: E[] } {
  const requests: E[] = [];
  const fromPlaylist: E[] = [];
  for (const e of queue) (e.section === "autoplay" ? fromPlaylist : requests).push(e);
  return { requests, fromPlaylist };
}

/**
 * Where pending suggestions go (docs/ui.md#existing-features-in-the-new-ui): above the queue for
 * people who may edit it (they accept or reject them), below it for everyone else; nowhere when
 * there are none.
 */
export function suggestionsPlace(s: RoomLike): "above" | "below" | "none" {
  if (s.suggestions.length === 0) return "none";
  return s.room.you.canModerate ? "above" : "below";
}

/** Whether `e` was added by `actor` (for an autoplay entry: the person whose playlist it came from). */
export function addedBy(e: EntryLike, actor: ActorLike | undefined): boolean {
  return actor !== undefined && e.addedBy !== undefined && e.addedBy.kind === actor.kind && e.addedBy.id === actor.id;
}

/** What a queued entry's row offers. */
export interface EntryButtons {
  /** The ↑ vote button (requests in vote mode); disabled unless `canVote`. */
  vote: boolean;
  canVote: boolean;
  /** ↑ on a From Playlist entry: make it the caller's request (`queue.request`). */
  request: boolean;
  /** × (`queue.remove`). */
  remove: boolean;
  /** Retry on a failed YouTube entry (`queue.retry`). */
  retry: boolean;
}

/** Whether the viewer may remove the entry: the owner, a moderator, its adder, or anyone requesting in open mode. */
export function canRemove(s: RoomLike, e: EntryLike, actor: ActorLike | undefined): boolean {
  const you = s.room.you;
  if (you.restriction !== undefined) return false;
  return you.canModerate || addedBy(e, actor) || (s.room.controlMode === "open" && you.canRequest && you.identified);
}

/** Whether a failed entry may be fetched again (jobtext.ts `canRetryEntry`, same rule). */
export function canRetry(s: RoomLike, e: EntryLike, actor: ActorLike | undefined): boolean {
  const you = s.room.you;
  return canRetryEntry(e.status, e.item.source, you.canModerate, addedBy(e, actor), you.canYoutube);
}

export function entryButtons(s: RoomLike, e: EntryLike, actor: ActorLike | undefined): EntryButtons {
  const you = s.room.you;
  const autoplay = e.section === "autoplay";
  const readOnly = you.restriction !== undefined;
  return {
    vote: !autoplay && s.room.controlMode === "vote",
    canVote: you.canVote && !readOnly,
    request: autoplay && you.canRequest && you.canRequestLibrary !== false && !readOnly,
    remove: canRemove(s, e, actor),
    retry: canRetry(s, e, actor),
  };
}

/**
 * The player bar's middle (docs/ui.md#layout-desktop): previous / play-pause / next and the seek bar
 * for people who may control the room, otherwise a mute button and a heart (the heart only for
 * users: guests can't like songs). Volume and the menu are always there.
 */
export function playerControls(you: YouLike | undefined): "controls" | "listener" {
  return you !== undefined && you.canSkip && you.restriction === undefined ? "controls" : "listener";
}

/** Whether the viewer can heart songs (users only; guests and anonymous visitors can't). */
export function canHeart(meKind: string): boolean {
  return meKind === "user";
}

/** The Now Playing × (a skip, by its rules): only for people who may skip. */
export function canSkipNow(you: YouLike | undefined): boolean {
  return you !== undefined && you.canSkip && you.restriction === undefined;
}

/**
 * The vote-to-skip button: in vote mode while vote-skip is on and something plays, for people who
 * may vote; `votes`/`needed` for its label.
 */
export function voteSkip(s: RoomLike & { skipVotes?: { votes: number; needed: number } }, playing: boolean):
    { show: boolean; enabled: boolean; label: string } {
  const sv = s.skipVotes;
  if (s.room.controlMode !== "vote" || sv === undefined || !playing) return { show: false, enabled: false, label: "" };
  const you = s.room.you;
  return { show: true, enabled: you.canVote && you.restriction === undefined,
    label: "Vote to skip (" + sv.votes + "/" + sv.needed + ")" };
}

/**
 * The player's mute (docs/ui.md#layout-desktop): "Mute player" until unmuted, or "Mute only until
 * end of song", which unmutes by itself as soon as another entry (or nothing) is current.
 */
export class MuteState {
  #muted = false;
  /** Muted until this entry stops being current; undefined: muted until unmuted (or not muted). */
  #untilEntry: string | undefined;

  get muted(): boolean {
    return this.#muted;
  }

  /** Whether the mute ends with the current song. */
  get untilEndOfSong(): boolean {
    return this.#muted && this.#untilEntry !== undefined;
  }

  mute(): void {
    this.#muted = true;
    this.#untilEntry = undefined;
  }

  /** Mutes until `entryId` (the current entry) ends; with nothing current there is nothing to mute. */
  muteUntilEndOf(entryId: string | undefined): void {
    if (entryId === undefined) return;
    this.#muted = true;
    this.#untilEntry = entryId;
  }

  unmute(): void {
    this.#muted = false;
    this.#untilEntry = undefined;
  }

  toggle(): void {
    if (this.#muted) this.unmute();
    else this.mute();
  }

  /** The current entry changed (or not): returns whether the player is muted now. */
  current(entryId: string | undefined): boolean {
    if (this.#muted && this.#untilEntry !== undefined && entryId !== this.#untilEntry) this.unmute();
    return this.#muted;
  }
}

/** The volume slider (0..100) as a linear gain: squared, so the slider feels even to the ear. */
export function volumeGain(slider: number): number {
  if (!Number.isFinite(slider)) return 1;
  const v = Math.max(0, Math.min(100, slider)) / 100;
  return v * v;
}

/** A remembered volume (`localStorage`) as a slider value: 0..100, 100 when missing or broken. */
export function parseVolume(stored: string | null | undefined): number {
  if (stored === null || stored === undefined || stored.trim() === "") return 100;
  const n = Number(stored);
  if (!Number.isFinite(n)) return 100;
  return Math.max(0, Math.min(100, Math.round(n)));
}

/** A playlist's total time as the playlist header shows it: "4h 20m", "23m", "45s" (under a minute), "0m". */
export function formatTotal(ms: number): string {
  const total = Number.isFinite(ms) ? Math.max(0, ms) : 0;
  if (total > 0 && total < 59_500) return Math.round(total / 1000) + "s";
  const minutes = Math.round(total / 60000);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? h + "h " + m + "m" : m + "m";
}

/** The sum of the known durations. */
export function totalDuration(items: readonly { durationMs?: number }[]): number {
  let total = 0;
  for (const i of items) if (typeof i.durationMs === "number" && i.durationMs > 0) total += i.durationMs;
  return total;
}

/** "1 song" / "69 songs". */
export function songCount(n: number): string {
  return n + (n === 1 ? " song" : " songs");
}

/** `GET /api/rooms/{id}/autoplay`'s selections (only what these rules read). */
export interface AutoplaySelectionLike {
  user: { kind: string; id: string; name: string };
  playlists: { id: string; name: string; entryCount: number; builtin?: string }[];
}

/**
 * The Library tab's bottom section: the playlists other people selected for autoplay in the
 * current room, each with whose it is (the caller's own selections show as dots on their playlists).
 */
export function othersAutoplay(selections: readonly AutoplaySelectionLike[], myUserId: string | undefined):
    { owner: string; ownerId: string; id: string; name: string }[] {
  const out: { owner: string; ownerId: string; id: string; name: string }[] = [];
  for (const sel of selections) {
    if (sel.user.kind === "user" && sel.user.id === myUserId) continue;
    for (const p of sel.playlists) out.push({ owner: sel.user.name, ownerId: sel.user.id, id: p.id, name: p.name });
  }
  return out;
}

/** The ids of the caller's own playlists selected for autoplay in the current room. */
export function mySelectedPlaylists(selections: readonly AutoplaySelectionLike[], myUserId: string | undefined):
    Set<string> {
  const out = new Set<string>();
  for (const sel of selections) {
    if (sel.user.kind !== "user" || sel.user.id !== myUserId) continue;
    for (const p of sel.playlists) out.add(p.id);
  }
  return out;
}

/** The middle views the sidebar links to, by path (docs/ui.md#layout-desktop; router.ts `list` view). */
export const LIBRARY_LINKS: readonly { path: string; label: string }[] = [
  { path: "/songs", label: "All My Songs" },
  { path: "/shows", label: "Movies/Shows" },
  { path: "/liked", label: "Liked Songs" },
];

/** The search results view for a query. */
export function searchPath(q: string): string {
  return "/search?q=" + encodeURIComponent(q.trim());
}

/** Shuffles a copy (Fisher–Yates) with `random` (0 ≤ r < 1). */
export function shuffled<T>(list: readonly T[], random: () => number = Math.random): T[] {
  const out = list.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    const t = out[i] as T;
    out[i] = out[j] as T;
    out[j] = t;
  }
  return out;
}

/** A person in the room (`presence.people`). */
export interface PersonLike {
  actor: { kind: string; id: string; name: string; isGuest: boolean };
  mode: string;
}

/** The Room tab's line under a person: how they follow the room. */
export function personModeText(p: PersonLike): string {
  return p.mode === "remote" ? "Requests-only" : "Listening";
}

/** The Room tab's summary: "3 listening · 5 requests-only · +2 anonymous". */
export function presenceText(p: { listening: number; remote: number; anonymous: number }): string {
  let text = p.listening + " listening · " + p.remote + " requests-only";
  if (p.anonymous > 0) text += " · +" + p.anonymous + " anonymous";
  return text;
}

/** What a person's ⋯ menu (the moderation menu, docs/ui.md#moderation-menu-right-click-a-person-in-the-room-tab) offers. */
export type PersonAction = "addModerator" | "removeModerator" | "timeout" | "ban" | "banFromServer";

/** Who opens the menu: their rights in the room, their own identity and whether they hold `admin.users`. */
export interface PersonViewer {
  you: YouLike & { role?: string };
  actor: ActorLike | undefined;
  adminUsers: boolean;
}

/**
 * The moderation menu's items for `person` (whose stored room role, `owner`/`moderator`/`member`/
 * `banned`, is `targetRole` when known; guests have none). The server's rules: only the owner names
 * or removes moderators; the owner and moderators time out and ban, but never the owner, a
 * moderator only by the owner (a ban; a moderator can't be timed out) and never themselves; Ban from
 * server (disables the account) for holders of `admin.users`, users only.
 */
export function personActions(v: PersonViewer, person: PersonLike, targetRole?: string): PersonAction[] {
  const a = person.actor;
  if (v.actor !== undefined && v.actor.kind === a.kind && v.actor.id === a.id) return [];
  const out: PersonAction[] = [];
  const user = a.kind === "user";
  const owner = v.you.role === "owner";
  const free = v.you.restriction === undefined;
  if (free && v.you.canModerate && targetRole !== "owner") {
    if (owner && user && targetRole !== undefined && targetRole !== "banned") {
      out.push(targetRole === "moderator" ? "removeModerator" : "addModerator");
    }
    if (targetRole !== "moderator") out.push("timeout");
    if (targetRole !== "moderator" || owner) out.push("ban");
  }
  if (v.adminUsers && user) out.push("banFromServer");
  return out;
}

/** The menu text of each action. */
export const PERSON_ACTION_LABELS: Record<PersonAction, string> = {
  addModerator: "Add Moderator",
  removeModerator: "Remove Moderator",
  timeout: "Timeout…",
  ban: "Ban",
  banFromServer: "Ban from server",
};

/** A timeout's shortest and longest length (the server's limits). */
export const MIN_TIMEOUT_MS = 1000;
export const MAX_TIMEOUT_MS = 315_360_000_000;

const DURATION_UNITS: Record<string, number> = {
  s: 1000, sec: 1000, secs: 1000, second: 1000, seconds: 1000,
  m: 60_000, min: 60_000, mins: 60_000, minute: 60_000, minutes: 60_000,
  h: 3_600_000, hr: 3_600_000, hrs: 3_600_000, hour: 3_600_000, hours: 3_600_000,
  d: 86_400_000, day: 86_400_000, days: 86_400_000,
  w: 604_800_000, week: 604_800_000, weeks: 604_800_000,
};

/**
 * A timeout length as a moderator types it: "10" (minutes), "30s", "10m", "2h", "1d", "1w", or
 * combined ("1h 30m", "1d12h"); decimals allowed ("1.5h"). Undefined when it doesn't parse or is
 * outside the server's limits (1 s to 10 years).
 */
export function parseDurationInput(text: string): number | undefined {
  const t = text.trim().toLowerCase();
  if (t === "") return undefined;
  let ms: number;
  if (/^\d+(\.\d+)?$/.test(t)) {
    ms = Number(t) * 60_000;
  } else {
    const re = /(\d+(?:\.\d+)?)\s*([a-z]+)\s*/y;
    ms = 0;
    let pos = 0;
    while (pos < t.length) {
      re.lastIndex = pos;
      const m = re.exec(t);
      if (m === null) return undefined;
      const unit = DURATION_UNITS[m[2] ?? ""];
      if (unit === undefined) return undefined;
      ms += Number(m[1]) * unit;
      pos = re.lastIndex;
    }
  }
  ms = Math.round(ms);
  if (!Number.isFinite(ms) || ms < MIN_TIMEOUT_MS || ms > MAX_TIMEOUT_MS) return undefined;
  return ms;
}
