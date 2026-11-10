// The room's program outputs as the room page shows them (docs/rooms.md#outputs): the decisions
// behind the outputs panel (outputspanel.ts), kept free of the DOM so Node can test them
// (src/test/resources/web-tests/outputs.test.mjs).
//
// Two sources: the room state's `outputs` (every viewer: the active outputs with their status and
// estimatedDelayMs, kept current by `output.status` messages) and `GET /api/rooms/{id}/outputs`
// (owner and moderators: the stream URLs, the RTMP target and the key's last 4 characters).
import type { OutputView } from "./remote.js";

export type OutputKind = "rtmp" | "ts" | "hls";

export const OUTPUT_KINDS: OutputKind[] = ["rtmp", "ts", "hls"];

export const OUTPUT_LABELS: Record<string, string> = { rtmp: "RTMP push", ts: "MPEG-TS (.ts)", hls: "HLS" };

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
  /** `.ts` / HLS: the tokenised URL. */
  url?: string;
  rtmpUrl?: string;
  hasRtmpKey?: boolean;
  rtmpKeyLast4?: string;
  /** The full stream key: only for the room's owner. */
  rtmpKey?: string;
  playbackUrl?: string;
  rtmpAvailable?: boolean;
  status?: PushStatus;
}

/**
 * The state's outputs after an `output.status` message: the output of that kind is replaced
 * (keeping its `estimatedDelayMs` if the message has none), added, or removed when it is `off`.
 * The result keeps the order RTMP, `.ts`, HLS. Malformed messages change nothing.
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
  if (!row.active) return row.serverEnabled ? "Off" : "Off (disabled by the server admin)";
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

export const VRCHAT_ALLOWLIST_NOTE = "VRChat only plays URLs from its allowlist by itself. For these self-hosted "
  + "URLs, viewers must turn on \"Allow Untrusted URLs\" in VRChat's settings; in public and group-public "
  + "instances the world must also list this server's domain in its \"Video Player Allowed Domains\".";

export const HLS_DESYNC_NOTE = "HLS players start a few segments behind the live edge, and not all at the same "
  + "segment, so VRChat viewers on HLS can be a few seconds apart from each other. Use the .ts URL (or RTMP) "
  + "when everyone should hear the same moment.";

/** The notes under the outputs: the VRChat allowlist (any self-hosted URL) and HLS desync (HLS on). */
export function outputNotes(rows: OutputRow[]): string[] {
  const notes: string[] = [];
  if (rows.some((r) => r.kind === "ts" || r.kind === "hls")) notes.push(VRCHAT_ALLOWLIST_NOTE);
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
