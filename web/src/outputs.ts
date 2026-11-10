// The room's program outputs as the room page shows them (docs/rooms.md#outputs): the decisions
// behind the outputs panel (outputspanel.ts), kept free of the DOM so Node can test them
// (src/test/resources/web-tests/outputs.test.mjs).
//
// Two sources: the room state's `outputs` (every viewer: the active outputs with their status and
// estimatedDelayMs, kept current by `output.status` messages) and `GET /api/rooms/{id}/outputs`
// (owner and moderators: the stream URLs, the RTMP target and the key's last 4 characters).
import type { OutputView } from "./remote.js";

export type OutputKind = "rtmp" | "ts" | "hls" | "mp3";

export const OUTPUT_KINDS: OutputKind[] = ["rtmp", "ts", "hls", "mp3"];

export const OUTPUT_LABELS: Record<string, string> = { rtmp: "RTMP push", ts: "MPEG-TS (.ts)", hls: "HLS", mp3: "MP3 audio" };

/** What an output kind is for, shown under its title (none for kinds that need no explanation). */
export const OUTPUT_DESCRIPTIONS: Record<string, string> = {
  mp3: "MP3 audio stream for internet-radio players. Audio only; the station title shows what is playing.",
};

/** A push's status as `GET …/outputs` has it (RTMP, while a push runs). */
export interface PushStatus {
  state: "connecting" | "connected" | "retrying" | "error" | string;
  detail?: string;
  failures: number;
  since: number;
  retryAt?: number;
}

/** One output from `GET /api/rooms/{id}/outputs` (docs/api.md#rooms). */
export interface OutputRow {
  id: string;
  kind: OutputKind | string;
  enabled: boolean;
  serverEnabled: boolean;
  active: boolean;
  createdAt: number;
  /** `.ts` / HLS / MP3: the stream URL (by the room's slug, or by token in a private room). */
  url?: string;
  /** `.ts` / HLS / MP3: `slug` (public and unlisted rooms) or `token` (private rooms: the URL can be rotated). */
  urlKind?: "slug" | "token" | string;
  /**
   * `.ts` / HLS / MP3 of a public or unlisted room, when the server has a plain-HTTP address:
   * the same stream as `http://`, for players that can't use HTTPS. Never for private rooms.
   */
  insecureUrl?: string;
  /** RTMP, the room's owner only: the ingest URL, whether there is a key, and the key. */
  rtmpUrl?: string;
  hasRtmpKey?: boolean;
  rtmpKey?: string;
  playbackUrl?: string;
  rtmpAvailable?: boolean;
  status?: PushStatus;
  /**
   * The owner only: why the server's auto turn-off turned it off (`idle`: the queue was empty too
   * long; `no_listeners`: nobody listened too long).
   */
  stopped?: { reason: string; at: number; idleMinutes: number };
}

/** The line for an output the server's auto turn-off turned off, or undefined. */
export function autoOffLine(row: OutputRow): string | undefined {
  if (row.enabled || row.stopped === undefined) return undefined;
  const n = row.stopped.idleMinutes;
  const minutes = n + (n === 1 ? " minute" : " minutes");
  switch (row.stopped.reason) {
    case "idle": return "Off: queue empty for " + minutes + ". Turn on to start again.";
    case "no_listeners": return "Off: no listeners for " + minutes + ". Turn on to start again.";
    default: return undefined;
  }
}

/** Whether the output's URL has a token the owner can rotate (only private rooms' URLs do). */
export function canRotate(row: OutputRow): boolean {
  return row.urlKind === "token";
}

/** The stream URLs depend on the room's slug and visibility: the panel reloads them when either changes. */
export function urlKey(room: { slug?: string; visibility?: string } | undefined): string {
  return room === undefined ? "" : (room.visibility ?? "") + "/" + (room.slug ?? "");
}

/**
 * The state's outputs after an `output.status` message: the output of that kind is replaced
 * (keeping its `estimatedDelayMs` if the message has none), added, or removed when it is `off`.
 * The result keeps the order RTMP, `.ts`, HLS, MP3. Malformed messages change nothing.
 */
export function applyOutputStatus(outputs: OutputView[] | undefined, msg: Record<string, unknown>): OutputView[] {
  const list = (outputs ?? []).slice();
  const kind = msg["kind"];
  const status = msg["status"];
  if (typeof kind !== "string" || typeof status !== "string") return list;
  const i = list.findIndex((o) => o.kind === kind);
  if (status === "off") {
    if (i >= 0) list.splice(i, 1);
    return list;
  }
  const next: OutputView = { kind, status };
  const delay = msg["estimatedDelayMs"];
  if (typeof delay === "number") next.estimatedDelayMs = delay;
  else if (i >= 0 && list[i]?.estimatedDelayMs !== undefined) next.estimatedDelayMs = list[i]?.estimatedDelayMs;
  const detail = msg["detail"];
  if (typeof detail === "string") next.detail = detail;
  if (i >= 0) list[i] = next;
  else list.push(next);
  return list.sort((a, b) => rank(a.kind) - rank(b.kind));
}

function rank(kind: string): number {
  const i = OUTPUT_KINDS.indexOf(kind as OutputKind);
  return i < 0 ? OUTPUT_KINDS.length : i;
}

/** Which outputs are active, as one string: the panel reloads the stream URLs when it changes. */
export function activeKey(outputs: OutputView[] | undefined): string {
  return (outputs ?? []).map((o) => o.kind).sort().join(",");
}

/** "Connected", "Connecting…", "Retrying", "Error", "Ready" (unknown states as they are). */
export function statusLabel(status: string | undefined): string {
  switch (status) {
    case "connected": return "Connected";
    case "connecting": return "Connecting…";
    case "retrying": return "Retrying";
    case "error": return "Error";
    case "ready": return "Ready";
    case "off": case undefined: return "Off";
    default: return status;
  }
}

/**
 * The RTMP push line for the owner and moderators: the live status from the room state (the
 * freshest) or else the API's, the number of failures in a row and, when a retry is pending, in
 * how many seconds.
 */
export function pushLine(live: OutputView | undefined, row: OutputRow, nowMs: number): string {
  if (!row.active) {
    if (!row.serverEnabled) return "Off (disabled by the server admin)";
    return autoOffLine(row) ?? "Off";
  }
  if (row.rtmpAvailable === false) return "Error: this server can't push RTMP (ffmpeg isn't configured)";
  const state = live?.status ?? row.status?.state ?? "connecting";
  let text = statusLabel(state);
  const st = row.status;
  if (st !== undefined && st.state === state && (state === "retrying" || state === "error")) {
    if (st.failures > 0) text += " (" + st.failures + (st.failures === 1 ? " failure" : " failures") + " in a row)";
    if (st.retryAt !== undefined && st.retryAt > nowMs) {
      text += ", next try in " + Math.ceil((st.retryAt - nowMs) / 1000) + " s";
    }
  }
  return text;
}

/** The last ffmpeg message of the push (stream key masked by the server), if any. */
export function pushDetail(live: OutputView | undefined, row: OutputRow): string | undefined {
  if (!row.active) return undefined;
  return live?.detail ?? row.status?.detail;
}

/**
 * The URL viewers paste into VRChat for the RTMP output: the room setting
 * `outputs.rtmp.playbackUrl`, shown while the RTMP output is active.
 */
export function rtmpPlaybackUrl(outputs: OutputView[] | undefined, settings: Record<string, unknown> | undefined):
  string | undefined {
  if (!(outputs ?? []).some((o) => o.kind === "rtmp")) return undefined;
  const group = settings?.["outputs"] as Record<string, unknown> | undefined;
  const rtmp = group?.["rtmp"] as Record<string, unknown> | undefined;
  const url = rtmp?.["playbackUrl"];
  return typeof url === "string" && url.trim() !== "" ? url.trim() : undefined;
}

export const ALLOWLIST_NOTE = "Some players only play URLs from an allowlist. For these self-hosted URLs, "
  + "viewers may need to allow untrusted URLs in their player's settings, or the place they watch in may need "
  + "to allow this server's domain.";

export const HLS_DESYNC_NOTE = "HLS players start a few segments behind the live edge, and not all at the same "
  + "segment, so viewers on HLS can be a few seconds apart from each other. Use the .ts URL (or RTMP) "
  + "when everyone should hear the same moment.";

/** The notes under the outputs: the player allowlist (any self-hosted URL) and HLS desync (HLS on). */
export function outputNotes(rows: OutputRow[]): string[] {
  const notes: string[] = [];
  if (rows.some((r) => r.kind === "ts" || r.kind === "hls")) notes.push(ALLOWLIST_NOTE);
  if (rows.some((r) => r.kind === "hls" && r.active)) notes.push(HLS_DESYNC_NOTE);
  return notes;
}

/** What the owner may do with an output kind the room doesn't have yet. */
export function canCreate(kind: string, rows: OutputRow[], serverEnabled: boolean): boolean {
  return serverEnabled && !rows.some((r) => r.kind === kind);
}

/** An RTMP ingest URL the server will take: rtmp:// or rtmps:// with a host (the server checks again). */
export function validRtmpUrl(url: string): boolean {
  return /^rtmps?:\/\/[^/\s]+/i.test(url.trim()) && url.trim().length <= 2048;
}
