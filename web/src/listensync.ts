// Listen-mode decisions (docs/rooms.md#listen-mode), kept apart from the player (listen.ts) so
// they can be tested outside a browser: which file to play for an item, what the sync loop does
// about a given drift, when to preload the next entry, when an original that keeps needing hard
// seeks is given up for `audio.m4a`, and when a video ↔ audio-only switch may crossfade.
//
// This module touches no DOM and imports only types.
import type { RoomItem, RoomState, WaitingEntry } from "./roomclient.js";

/** Drift (ms) above which the element is moved to the target position (hard seek). */
export const HARD_SEEK_MS = 750;
/** Drift (ms) at or below which the element plays at normal speed. */
export const STEADY_MS = 40;
/** The rate nudge: `1 − drift / RATE_SPAN_MS`, clamped to [MIN_RATE, MAX_RATE]. */
export const RATE_SPAN_MS = 2000;
export const MIN_RATE = 0.95;
export const MAX_RATE = 1.05;
/** The next entry is preloaded this long (ms) before the current one ends. */
export const PRELOAD_BEFORE_END_MS = 10_000;
/** An original that needs a third hard seek within FALLBACK_WINDOW_MS of two others is given up. */
export const FALLBACK_SEEKS = 2;
export const FALLBACK_WINDOW_MS = 10_000;
/** Video ↔ audio-only switch: the incoming element must be steady (drift ≤ STEADY_MS) for this many ticks… */
export const SWITCH_STEADY_TICKS = 3;
/** …at least this far apart (ms; the loop also ticks on state and load events, which come in bursts)… */
export const SWITCH_TICK_SPACING_MS = 200;
/** …or have been playing when this much time (ms) has passed since the switch began. */
export const SWITCH_TIMEOUT_MS = 3000;
/** The crossfade between the two elements (ms). */
export const CROSSFADE_MS = 150;

export type SyncAction = { kind: "seek" } | { kind: "rate"; rate: number };

/**
 * What the sync loop does about `driftMs` (element position − timeline position):
 * above 750 ms a hard seek, above 40 ms a rate nudge, else normal speed.
 */
export function syncAction(driftMs: number): SyncAction {
  const abs = Math.abs(driftMs);
  if (abs > HARD_SEEK_MS) return { kind: "seek" };
  if (abs > STEADY_MS) return { kind: "rate", rate: Math.min(MAX_RATE, Math.max(MIN_RATE, 1 - driftMs / RATE_SPAN_MS)) };
  return { kind: "rate", rate: 1 };
}

/** A gain in dB as a linear factor; no (or a non-finite) gain is 1. */
export function dbToGain(db: number | undefined | null): number {
  if (db === undefined || db === null || !Number.isFinite(db)) return 1;
  return Math.pow(10, db / 20);
}

/** The file the client plays for an item. */
export interface Source {
  url: string;
  /** Gain to apply in dB (`originalGainDb` for an original; `audio.m4a` and `mezz.mp4` are already normalised). */
  gainDb: number;
  /** Played in a `<video>` element (`mezz.mp4`); otherwise in an `<audio>` element. */
  video: boolean;
  /** The item's original (an audio upload's kept file), which may still fall back to `audio.m4a`. */
  original: boolean;
}

/** The fields of an item that decide its source. */
export type SourceItem = Pick<RoomItem, "kind" | "presentAsAudio" | "audioUrl" | "videoUrl" | "originalUrl"
  | "originalGainDb" | "originalType">;

/**
 * The file to play for an item in listen mode, or undefined if it has none (not ready).
 *
 * - Audio items: the original when the server offers one (web-playable upload), `canPlay` agrees
 *   with its type and it wasn't given up this session (`avoidOriginal`); else `audio.m4a`.
 * - Video items shown as audio (`presentAsAudio`): `audio.m4a`.
 * - Other video items: `mezz.mp4`, or `audio.m4a` in audio-only mode (then the video is never
 *   requested).
 */
export function chooseSource(item: SourceItem, canPlay: (type: string) => boolean, avoidOriginal: boolean,
    audioOnly = false): Source | undefined {
  if (item.kind === "video" && item.presentAsAudio !== true && item.videoUrl !== undefined && !audioOnly) {
    return { url: item.videoUrl, gainDb: 0, video: true, original: false };
  }
  if (item.kind === "audio" && !avoidOriginal && item.originalUrl !== undefined
      && (item.originalType === undefined || canPlay(item.originalType))) {
    return { url: item.originalUrl, gainDb: item.originalGainDb ?? 0, video: false, original: true };
  }
  if (item.audioUrl !== undefined) return { url: item.audioUrl, gainDb: 0, video: false, original: false };
  return undefined;
}

/** The entry that plays after the current one: the first ready entry of the queue (play order). */
export function nextToPreload(state: Pick<RoomState, "queue" | "current">): WaitingEntry | undefined {
  for (const e of state.queue) {
    if (e.status === "ready" && e.item.status === "ready" && e.entryId !== state.current?.entryId) return e;
  }
  return undefined;
}

/** Whether to preload the next entry, `remainingMs` before the current one ends. */
export function shouldPreload(remainingMs: number): boolean {
  return remainingMs <= PRELOAD_BEFORE_END_MS;
}

/**
 * Counts the hard seeks an original needed. `hardSeek(now)` answers whether to seek once more
 * (true) or give the original up (false: two hard seeks within the last 10 s didn't hold it on the
 * timeline, typical for VBR MP3s without a seek table). `reset()` on every re-anchor and every
 * (re)load, since those seeks are the timeline's, not the file's fault.
 */
export class SeekGuard {
  readonly #max: number;
  readonly #windowMs: number;
  #seeks: number[] = [];

  constructor(max = FALLBACK_SEEKS, windowMs = FALLBACK_WINDOW_MS) {
    this.#max = max;
    this.#windowMs = windowMs;
  }

  reset(): void {
    this.#seeks = [];
  }

  hardSeek(now: number): boolean {
    this.#seeks = this.#seeks.filter((t) => now - t < this.#windowMs);
    if (this.#seeks.length >= this.#max) return false;
    this.#seeks.push(now);
    return true;
  }
}

/**
 * Decides when a video ↔ audio-only switch crossfades (docs/rooms.md#audio-only-switching): once the
 * incoming element's drift has stayed within 40 ms for 3 consecutive ticks (counted at most every
 * 200 ms), or, if it is playing at all, 3 s after the switch began. Until then the outgoing element
 * stays audible, so a slow or failing load never leaves a gap.
 */
export class SwitchGate {
  readonly #since: number;
  #steady = 0;
  #lastSteady = Number.NEGATIVE_INFINITY;

  constructor(now: number) {
    this.#since = now;
  }

  /**
   * One tick. `driftMs` is the incoming element's drift, or undefined while it isn't playing with
   * data (loading, seeking, buffering, paused). True: crossfade now.
   */
  sample(now: number, driftMs: number | undefined): boolean {
    if (driftMs === undefined) {
      this.#steady = 0;
      this.#lastSteady = Number.NEGATIVE_INFINITY;
      return false;
    }
    if (Math.abs(driftMs) <= STEADY_MS) {
      if (now - this.#lastSteady >= SWITCH_TICK_SPACING_MS) {
        this.#steady++;
        this.#lastSteady = now;
      }
    } else {
      this.#steady = 0;
      this.#lastSteady = Number.NEGATIVE_INFINITY;
    }
    return this.#steady >= SWITCH_STEADY_TICKS || now - this.#since >= SWITCH_TIMEOUT_MS;
  }
}
