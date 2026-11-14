// Remote mode (docs/rooms.md#remote-mode), shown to users as **requests-only mode**
// (docs/ui.md#requests-only-mode; the wire name stays "remote"): the decisions behind the room page's
// remote view, kept free of the DOM so Node can test them (src/test/resources/web-tests/remote.test.mjs).
//
// A remote user hears the room through one of its program outputs (in VRChat), which runs a few
// seconds behind the timeline. The page therefore loads no media and shows the timeline as it was
// `estimatedDelayMs` ago (the active output's delay, plus the user's own nudge), so the progress bar
// and the "now playing" entry match what the user hears.

/** A connection's mode (docs/rooms.md#web-and-app-playback). */
export type RoomMode = "listen" | "remote";

/**
 * One output in the room state's `outputs`: the room's active outputs, kept current by
 * `output.status` (outputs.ts `applyOutputStatus`).
 */
export interface OutputView {
  kind: "rtmp" | "ts" | "hls" | "mp3" | string;
  /** RTMP: connecting | connected | retrying | error; `.ts`/HLS/MP3: ready (MP3 without ffmpeg: error). */
  status?: string;
  estimatedDelayMs?: number;
  /** RTMP, owner and moderators only: the push's last ffmpeg message. */
  detail?: string;
}

/** The output kinds, most preferred first, when the room has more than one. */
export const OUTPUT_PREFERENCE = ["rtmp", "ts", "hls", "mp3"];

/** The defaults of `estimatedDelayMs.*` (docs/rooms.md#room-settings-settings_json). */
export const DEFAULT_DELAYS_MS: Record<string, number> = { rtmp: 5000, ts: 2000, hls: 8000, mp3: 3000 };

/** The settings' upper bound for a delay, also the bound of delay + nudge. */
export const MAX_DELAY_MS = 120000;

/** One nudge step. */
export const NUDGE_STEP_MS = 500;

/** Output states in which the output isn't carrying the room (so its delay doesn't apply). */
const INACTIVE = ["error", "disabled", "stopped", "off"];

/**
 * The mode to open the page in: the URL's `?mode=` (the QR short link asks for remote), else the
 * mode remembered for this room, else listen.
 */
export function chooseMode(fromUrl: string | null | undefined, stored: string | null | undefined): RoomMode {
  if (fromUrl === "remote" || fromUrl === "listen") return fromUrl;
  if (stored === "remote" || stored === "listen") return stored;
  return "listen";
}

/** Whether the room's owner forces requests-only mode (its `requestsOnly` setting). */
export function forcedRequestsOnly(settings: Record<string, unknown> | undefined): boolean {
  return settings?.["requestsOnly"] === true;
}

/**
 * The mode the page actually runs in: remote (requests-only) while the room forces it, else the
 * mode the user chose. The user's choice is kept, so it comes back when the room stops forcing.
 */
export function effectiveMode(chosen: RoomMode, forced: boolean): RoomMode {
  return forced ? "remote" : chosen;
}

/** The name a mode is shown under (the wire name `remote` is "Requests-only mode" to users). */
export function modeLabel(m: RoomMode): string {
  return m === "remote" ? "Requests-only mode" : "Listen mode";
}

/** localStorage key of the mode remembered for a room. */
export function modeKey(roomId: string): string {
  return "togethertunes.mode." + roomId;
}

/** localStorage key of the user's delay nudge for a room. */
export function nudgeKey(roomId: string): string {
  return "togethertunes.remoteNudge." + roomId;
}

function validDelay(v: unknown): v is number {
  return typeof v === "number" && isFinite(v) && v >= 0 && v <= MAX_DELAY_MS;
}

/**
 * The active output and its estimated delay. The active output is the most preferred one
 * (RTMP, `.ts`, HLS, MP3) that isn't in an error or off state; its delay is the one the server sent with
 * it, else the room setting `estimatedDelayMs.<kind>`, else the default. A room without any
 * active output is treated as RTMP, the primary output.
 */
export function outputDelay(outputs: OutputView[] | undefined, settings: Record<string, unknown> | undefined):
  { kind: string; delayMs: number } {
  const list = (outputs ?? []).filter((o) => o !== null && typeof o === "object" && typeof o.kind === "string");
  const live = list.filter((o) => o.status === undefined || INACTIVE.indexOf(o.status) < 0);
  const rank = (k: string): number => {
    const i = OUTPUT_PREFERENCE.indexOf(k);
    return i < 0 ? OUTPUT_PREFERENCE.length : i;
  };
  const pick = (live.length > 0 ? live : list).slice().sort((a, b) => rank(a.kind) - rank(b.kind))[0];
  const kind = pick !== undefined ? pick.kind : "rtmp";
  if (pick !== undefined && validDelay(pick.estimatedDelayMs)) return { kind, delayMs: pick.estimatedDelayMs };
  const fromSettings = (settings?.["estimatedDelayMs"] as Record<string, unknown> | undefined)?.[kind];
  if (validDelay(fromSettings)) return { kind, delayMs: fromSettings };
  return { kind, delayMs: DEFAULT_DELAYS_MS[kind] ?? DEFAULT_DELAYS_MS["rtmp"] ?? 5000 };
}

/** A stored nudge, read back (0 for anything that isn't a whole number of steps within bounds). */
export function parseNudge(stored: string | null | undefined): number {
  if (stored === null || stored === undefined || stored === "") return 0;
  const n = Number(stored);
  if (!isFinite(n)) return 0;
  return clampNudge(n);
}

/** Rounds a nudge to whole steps and keeps it within ±MAX_DELAY_MS. */
export function clampNudge(n: number): number {
  const steps = Math.round(n / NUDGE_STEP_MS);
  return Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, steps * NUDGE_STEP_MS));
}

/**
 * The nudge after one step (`direction` +1 = later, −1 = earlier), kept so that the total delay
 * stays within 0..MAX_DELAY_MS.
 */
export function stepNudge(nudge: number, direction: 1 | -1, outputDelayMs: number): number {
  const next = clampNudge(nudge + direction * NUDGE_STEP_MS);
  const total = outputDelayMs + next;
  if (total < 0 || total > MAX_DELAY_MS) return nudge;
  return next;
}

/** The delay the page shows the timeline with: the output's plus the nudge, within 0..MAX_DELAY_MS. */
export function effectiveDelay(outputDelayMs: number, nudge: number): number {
  return Math.max(0, Math.min(MAX_DELAY_MS, outputDelayMs + nudge));
}

/** "5.0 s", "+0.5 s", "−1.0 s". */
export function formatSeconds(ms: number, signed = false): string {
  const s = (Math.abs(ms) / 1000).toFixed(1) + " s";
  if (!signed) return (ms < 0 ? "−" : "") + s;
  return (ms < 0 ? "−" : "+") + s;
}

/** The part of the timeline a delayed view follows (roomclient's Timeline, structurally). */
export interface TimelineLike {
  state: string;
  entryId?: string;
  anchorServerMs: number;
  anchorPositionMs: number;
}

/** How far back snapshots are kept beyond the largest possible delay. */
const KEEP_EXTRA_MS = 30000;

/** How far before its arrival a change's anchor time is still trusted as the time it happened. */
const MAX_ANCHOR_LAG_MS = 10000;

/**
 * The room's timeline as it was some time ago. Every change of the timeline (another entry,
 * another anchor or state) is recorded with the server time it happened at; `atTime(t)` returns what
 * was in effect at server time `t`. A snapshot recorded first (the state received on joining)
 * counts as having always been in effect, since the page doesn't know what came before it.
 *
 * `T` is what goes with the timeline (the current entry); a newer `T` for an unchanged timeline
 * (e.g. the item became ready) replaces the stored one without counting as a change.
 */
export class DelayedTimeline<T> {
  #snaps: { at: number; timeline: TimelineLike; value: T }[] = [];

  /**
   * Records the timeline as received at server time `receivedAt`. The change is dated at its
   * `anchorServerMs` when that is plausible (not in the future, at most 10 s old, not before the
   * previous change), else at `receivedAt`.
   */
  push(timeline: TimelineLike, value: T, receivedAt: number): void {
    const last = this.#snaps[this.#snaps.length - 1];
    if (last !== undefined && sameTimeline(last.timeline, timeline)) {
      last.value = value;
      return;
    }
    let at = receivedAt;
    const a = timeline.anchorServerMs;
    if (typeof a === "number" && isFinite(a) && a <= receivedAt && a >= receivedAt - MAX_ANCHOR_LAG_MS) at = a;
    if (last === undefined) at = -Infinity;
    else if (at < last.at) at = last.at;
    this.#snaps.push({ at, timeline: { ...timeline }, value });
    this.#prune(receivedAt);
  }

  /** What was in effect at server time `t` (undefined before anything was pushed). */
  atTime(t: number): { timeline: TimelineLike; value: T } | undefined {
    let found = this.#snaps[0];
    for (const s of this.#snaps) {
      if (s.at > t) break;
      found = s;
    }
    return found !== undefined ? { timeline: found.timeline, value: found.value } : undefined;
  }

  /** Forgets everything (the page left the room). */
  clear(): void {
    this.#snaps = [];
  }

  /** The number of snapshots kept (for tests). */
  get size(): number {
    return this.#snaps.length;
  }

  #prune(now: number): void {
    const horizon = now - MAX_DELAY_MS - KEEP_EXTRA_MS;
    // Drop a snapshot once the one after it was already in effect at the horizon.
    while (this.#snaps.length > 1 && (this.#snaps[1]?.at ?? Infinity) <= horizon) this.#snaps.shift();
  }
}

function sameTimeline(a: TimelineLike, b: TimelineLike): boolean {
  return a.state === b.state && a.entryId === b.entryId && a.anchorServerMs === b.anchorServerMs
    && a.anchorPositionMs === b.anchorPositionMs;
}
