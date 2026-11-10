// The video trim timeline's logic (docs/library-and-ingest.md#trim), without the DOM so Node tests
// can import it: time parsing and formatting (m:ss.mmm), the handles (clamping, snapping to the
// current encode's 2 s keyframe grid), and which kind of re-encode a choice needs. The server
// decides for real (TrimPlan.java); this mirrors it for the page's hint.

/** Frames per second of every encode. */
export const FPS = 30;
/** GOP length: the current encode has a keyframe every 2 s from its start. */
export const GOP_MS = 2000;
export const GOP_FRAMES = 60;
/** The shortest trim the server accepts. */
export const MIN_TRIM_MS = 1000;

/** The item page's `trim`: the current trim on the original's timeline. */
export interface TrimView { videoStartMs: number; videoEndMs?: number; originalMs: number }

/** A selection on the original's timeline (end exclusive). */
export interface Range { startMs: number; endMs: number }

/** "1:05.250", "0:00.000", "61:01.001" (minutes aren't wrapped into hours). */
export function formatTime(ms: number): string {
  const v = Math.max(0, Math.round(ms));
  const m = Math.floor(v / 60000);
  const s = Math.floor((v % 60000) / 1000);
  const rest = v % 1000;
  return m + ":" + String(s).padStart(2, "0") + "." + String(rest).padStart(3, "0");
}

/**
 * Parses "m:ss.mmm", "m:ss", "ss.mmm", "ss", "h:mm:ss.mmm" (fraction 1 to 3 digits; seconds and
 * minutes after the first field below 60). Returns ms, or undefined if it isn't a time.
 */
export function parseTime(text: string): number | undefined {
  const t = text.trim();
  const m = /^(?:(?:(\d+):)?(\d+):)?(\d+)(?:\.(\d{1,3}))?$/.exec(t);
  if (m === null) return undefined;
  const h = m[1] !== undefined ? Number(m[1]) : 0;
  const min = m[2] !== undefined ? Number(m[2]) : 0;
  const s = Number(m[3]);
  if (m[2] !== undefined && s >= 60) return undefined;
  if (m[1] !== undefined && min >= 60) return undefined;
  const frac = m[4] !== undefined ? Number(m[4].padEnd(3, "0")) : 0;
  return ((h * 60 + min) * 60 + s) * 1000 + frac;
}

/** The current selection of a trim view (an absent end is the original's end). */
export function currentRange(view: TrimView): Range {
  return { startMs: view.videoStartMs, endMs: view.videoEndMs ?? view.originalMs };
}

/** `ms` snapped to the nearest keyframe of the current encode (`curStart + 2 s × k`), within [0, max]. */
export function snapToGrid(ms: number, curStartMs: number, maxMs: number): number {
  const k = Math.round((ms - curStartMs) / GOP_MS);
  let v = curStartMs + k * GOP_MS;
  while (v < 0) v += GOP_MS;
  while (v > maxMs && v - GOP_MS >= 0) v -= GOP_MS;
  return v;
}

/** Whether `ms` is on the current encode's keyframe grid. */
export function onGrid(ms: number, curStartMs: number): boolean {
  return (ms - curStartMs) % GOP_MS === 0;
}

/**
 * Moves the start handle to `ms`: whole milliseconds, within [0, end − 1 s]; with `snap`, onto the
 * current encode's grid (the nearest keyframe that still leaves 1 s).
 */
export function moveStart(range: Range, ms: number, view: TrimView, snap: boolean): Range {
  const max = range.endMs - MIN_TRIM_MS;
  let v = Math.max(0, Math.min(max, Math.round(ms)));
  if (snap) v = snapToGrid(v, view.videoStartMs, Math.max(0, max));
  return { startMs: Math.max(0, v), endMs: range.endMs };
}

/** Moves the end handle to `ms`: whole milliseconds, within [start + 1 s, original's end]. */
export function moveEnd(range: Range, ms: number, view: TrimView): Range {
  const v = Math.max(range.startMs + MIN_TRIM_MS, Math.min(view.originalMs, Math.round(ms)));
  return { startMs: range.startMs, endMs: v };
}

/** Frames of an encode `ms` long (those with k / 30 s < length). */
export function frames(ms: number): number {
  return Math.max(0, Math.ceil((ms * FPS) / 1000 - 1e-9));
}

/** What saving the selection does. */
export type TrimKind = "none" | "cut" | "smart" | "full";

export interface TrimEstimate {
  kind: TrimKind;
  /** Seconds of video encoded from the original (0 for "none" and "cut"). */
  encodeSeconds: number;
}

/**
 * Which kind of re-encode `range` needs from the current encode (`view`), like the server's plan:
 * "none" (unchanged), "cut" (only GOPs dropped or frames cut at the end), "smart" (some GOPs
 * encoded again), "full" (the start is off the current grid, or nothing can be kept). The audio is
 * always encoded again, which is quick and not counted.
 */
export function estimate(view: TrimView, range: Range): TrimEstimate {
  const cur = currentRange(view);
  const endSame = range.endMs === cur.endMs || (range.endMs >= view.originalMs && cur.endMs >= view.originalMs);
  if (range.startMs === cur.startMs && endSame) return { kind: "none", encodeSeconds: 0 };
  const n = frames(range.endMs - range.startMs);
  const full: TrimEstimate = { kind: "full", encodeSeconds: n / FPS };
  const delta = range.startMs - cur.startMs;
  if (delta % GOP_MS !== 0) return full;
  const curFrames = frames(cur.endMs - cur.startMs);
  if (curFrames <= 0) return full;
  const d = (delta / GOP_MS) * GOP_FRAMES;
  const end = d + n;
  const lastGopStart = Math.floor((curFrames - 1) / GOP_FRAMES) * GOP_FRAMES;
  const reuseEnd = end <= curFrames ? end : lastGopStart;
  const midFrom = Math.max(d, 0);
  const midTo = Math.min(end, reuseEnd);
  if (midTo <= midFrom) return full;
  const encoded = (d < 0 ? -d : 0) + (end > midTo ? end - midTo : 0);
  return encoded === 0 ? { kind: "cut", encodeSeconds: 0 } : { kind: "smart", encodeSeconds: encoded / FPS };
}

/** The page's line about what Save does. */
export function estimateText(e: TrimEstimate): string {
  const secs = Math.max(1, Math.round(e.encodeSeconds));
  switch (e.kind) {
    case "none": return "No change.";
    case "cut": return "No video re-encode: whole keyframe groups are dropped or the end is cut.";
    case "smart": return "Smart re-encode: about " + secs + " s of video is encoded again, the rest is kept.";
    case "full": return "Full re-encode: about " + secs + " s of video (the start isn't on a keyframe of the current encode).";
  }
}

/** The body of `POST /api/items/{id}/trim` (an end at the original's end is sent as null: to the end). */
export function trimBody(view: TrimView, range: Range): { videoStartMs: number; videoEndMs: number | null } {
  return {
    videoStartMs: Math.round(range.startMs),
    videoEndMs: range.endMs >= view.originalMs ? null : Math.round(range.endMs),
  };
}

/** A position on the timeline (0..1 of its width) as ms on the original's timeline, and back. */
export function fractionToMs(fraction: number, view: TrimView): number {
  return Math.round(Math.max(0, Math.min(1, fraction)) * view.originalMs);
}

export function msToFraction(ms: number, view: TrimView): number {
  return view.originalMs <= 0 ? 0 : Math.max(0, Math.min(1, ms / view.originalMs));
}

/** The player's position (seconds into the current version) on the original's timeline, in ms. */
export function playheadMs(view: TrimView, currentTimeS: number): number {
  return view.videoStartMs + Math.max(0, currentTimeS) * 1000;
}

/**
 * What Preview plays from the current version (no encode): the part of the selection the current
 * encode has, in seconds of the current version, or undefined if it has none of it.
 */
export function previewSpan(view: TrimView, range: Range): { fromS: number; toS: number } | undefined {
  const cur = currentRange(view);
  const from = Math.max(range.startMs, cur.startMs);
  const to = Math.min(range.endMs, cur.endMs);
  if (to <= from) return undefined;
  return { fromS: (from - cur.startMs) / 1000, toS: (to - cur.startMs) / 1000 };
}
