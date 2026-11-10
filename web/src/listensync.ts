// Listen-mode decisions (docs/rooms.md#listen-mode), kept apart from the player (listen.ts) so
// they can be tested outside a browser: which file to play for an item, what the sync loop does
// about a given drift (a rate nudge, or in WebKit only latency-compensated seeks), how far ahead a
// seek aims, when to preload the next entry, when an original that keeps needing hard seeks is
// given up for `audio.m4a`, and when a video ↔ audio-only switch may crossfade.
//
// This module touches no DOM and imports only types.
import type { RoomItem, RoomState, WaitingEntry } from "./roomclient.js";

/** Drift (ms) above which the element is moved to the target position (hard seek). */
export const HARD_SEEK_MS = 750;
/** Drift (ms) at or below which the element plays at normal speed (rate mode starts a nudge above it). */
export const STEADY_MS = 40;
/**
 * Rate mode: a nudge plays at 1 ± RATE_STEP (± RATE_STEP_FAST when the drift was above
 * RATE_FAST_ABOVE_MS when it began) and keeps that rate until the drift is within RATE_STOP_MS
 * or changed sign. So a correction changes `playbackRate` twice, not on every tick: Safari stalls
 * the element briefly on every change, and a rate that wobbles from tick to tick is heard.
 */
export const RATE_STEP = 0.03;
export const RATE_STEP_FAST = 0.05;
export const RATE_FAST_ABOVE_MS = 300;
export const RATE_STOP_MS = 10;
/**
 * Seek mode (WebKit, where the pitch rises with the rate because `preservesPitch` isn't applied to
 * an element routed through Web Audio): the rate stays 1 and a drift above SEEK_MODE_MS is
 * corrected with a (latency-compensated) seek, at most once per cooldown. The cooldown starts at
 * SEEK_MODE_COOLDOWN_MS and doubles (up to SEEK_MODE_MAX_COOLDOWN_MS) while seeks land outside the
 * band, so a browser that can't seek precisely isn't made to stutter all the time.
 */
export const SEEK_MODE_MS = 60;
export const SEEK_MODE_COOLDOWN_MS = 2500;
export const SEEK_MODE_MAX_COOLDOWN_MS = 30_000;
/**
 * A seek that landed more than RESEEK_MS off (its latency wasn't known yet, or changed) is followed
 * at once by another one aimed with the corrected lead, instead of a long nudge or a cooldown; at
 * most MAX_RESEEKS in a row.
 */
export const RESEEK_MS = 150;
export const MAX_RESEEKS = 2;
/** Seek lead: limits (ms) and the largest landing error that still counts as a measurement. */
export const MIN_LEAD_MS = -200;
export const MAX_LEAD_MS = 2000;
export const MAX_LANDING_SAMPLE_MS = 1500;

/** How the sync loop corrects small drift: by nudging the rate, or (WebKit) only by seeking. */
export type SyncMode = "rate" | "seek";

/**
 * What the page can see of the browser engine without trusting the user agent (some browsers
 * disguise it: Reynard, Gecko on iOS, says it is Android).
 */
export interface EngineHints {
  /** `"preservesPitch" in HTMLMediaElement.prototype` (Chromium, Gecko, newer Safari). */
  preservesPitch: boolean;
  /** `"webkitPreservesPitch" in HTMLMediaElement.prototype` (WebKit; older Chromium). */
  webkitPreservesPitch: boolean;
  /** `"mozPreservesPitch" in HTMLMediaElement.prototype` (Gecko). */
  mozPreservesPitch: boolean;
  /** `"WebKitPlaybackTargetAvailabilityEvent" in window` (AirPlay: WebKit's own media stack only). */
  webkitPlaybackTarget: boolean;
}

/**
 * The sync mode for this engine: "seek" for WebKit's media stack (Safari on the Mac, Safari and
 * every WebKit browser on iOS/iPadOS), where the pitch rises with the rate for an element routed
 * through Web Audio and every rate change stalls the video; "rate" for everything else (Chromium,
 * Gecko, also Gecko on iOS). Decided by features, not the user agent: WebKit exposes the AirPlay
 * event, or only the prefixed `webkitPreservesPitch` (Safari 15). `override` ("rate"/"seek", from
 * `?sync=` on the room page) wins, for testing.
 */
export function syncModeFor(hints: EngineHints, override?: string | null): SyncMode {
  if (override === "rate" || override === "seek") return override;
  const webkit = hints.webkitPlaybackTarget
    || (hints.webkitPreservesPitch && !hints.preservesPitch && !hints.mozPreservesPitch);
  return webkit ? "seek" : "rate";
}

export type SyncAction = { kind: "seek" } | { kind: "rate"; rate: number };

/**
 * Decides, per element, what the sync loop does about the drift (element position − timeline
 * position) on a tick where the element is playing with data. Hard seek above 750 ms in both
 * modes; below that, rate mode nudges with a few fixed rates and hysteresis, seek mode seeks above
 * 60 ms with a cooldown. `reset()` on every (re)load and re-anchor.
 */
export class DriftController {
  readonly mode: SyncMode;
  #rate = 1;
  #lastSeekAt = Number.NEGATIVE_INFINITY;
  #cooldownMs = SEEK_MODE_COOLDOWN_MS;
  #reseek = false;
  #reseeks = 0;

  constructor(mode: SyncMode = "rate") {
    this.mode = mode;
  }

  /** The rate the controller wants now. */
  get rate(): number {
    return this.#rate;
  }

  /** The current seek-mode cooldown (ms). */
  get cooldownMs(): number {
    return this.#cooldownMs;
  }

  reset(): void {
    this.#rate = 1;
    this.#lastSeekAt = Number.NEGATIVE_INFINITY;
    this.#cooldownMs = SEEK_MODE_COOLDOWN_MS;
    this.#reseek = false;
    this.#reseeks = 0;
  }

  decide(now: number, driftMs: number): SyncAction {
    const abs = Math.abs(driftMs);
    if (!Number.isFinite(driftMs)) return { kind: "rate", rate: this.#rate };
    const reseek = this.#reseek;
    this.#reseek = false;
    if (abs > HARD_SEEK_MS) return this.#seek(now);
    if (reseek && abs > RESEEK_MS) {
      this.#reseeks++;
      return this.#seek(now);
    }
    if (this.mode === "seek") {
      this.#rate = 1;
      if (abs > SEEK_MODE_MS && now - this.#lastSeekAt >= this.#cooldownMs) return this.#seek(now);
      return { kind: "rate", rate: 1 };
    }
    if (this.#rate === 1) {
      if (abs > STEADY_MS) {
        const step = abs > RATE_FAST_ABOVE_MS ? RATE_STEP_FAST : RATE_STEP;
        this.#rate = driftMs > 0 ? 1 - step : 1 + step; // ahead: slow down; behind: speed up
      }
    } else if (this.#rate < 1 ? driftMs <= RATE_STOP_MS : driftMs >= -RATE_STOP_MS) {
      this.#rate = 1;
    }
    return { kind: "rate", rate: this.#rate };
  }

  /**
   * The drift on the first settled tick after a seek. A landing more than RESEEK_MS off asks for
   * one more seek at once (at most MAX_RESEEKS in a row). In seek mode a landing inside the band
   * resets the cooldown and a miss doubles it.
   */
  landed(driftMs: number): void {
    if (!Number.isFinite(driftMs)) return;
    if (Math.abs(driftMs) > RESEEK_MS) {
      this.#reseek = this.#reseeks < MAX_RESEEKS;
    } else {
      this.#reseeks = 0;
    }
    if (this.mode !== "seek") return;
    this.#cooldownMs = Math.abs(driftMs) <= SEEK_MODE_MS ? SEEK_MODE_COOLDOWN_MS
      : Math.min(SEEK_MODE_MAX_COOLDOWN_MS, this.#cooldownMs * 2);
  }

  #seek(now: number): SyncAction {
    this.#rate = 1;
    this.#lastSeekAt = now;
    return { kind: "seek" };
  }
}

/**
 * How far ahead of the target a playing element is moved, so it lands on the timeline although
 * the seek itself takes time (in Safari hundreds of ms: it decodes from the GOP's keyframe).
 * Learnt per element from where its seeks actually landed: the first measurement as is, then
 * halfway towards each new one.
 */
export class SeekLead {
  #ms: number;
  #samples = 0;

  constructor(initialMs = 0) {
    this.#ms = initialMs;
  }

  get ms(): number {
    return this.#ms;
  }

  get samples(): number {
    return this.#samples;
  }

  /**
   * A seek to `target + ms` landed `driftMs` off (measured on the first settled tick after it).
   * False (ignored) for a landing too far off to be a latency (the seek was lost or reset).
   */
  landed(driftMs: number): boolean {
    if (!Number.isFinite(driftMs) || Math.abs(driftMs) > MAX_LANDING_SAMPLE_MS) return false;
    const weight = this.#samples === 0 ? 1 : 0.5;
    this.#ms = Math.min(MAX_LEAD_MS, Math.max(MIN_LEAD_MS, this.#ms - weight * driftMs));
    this.#samples++;
    return true;
  }
}

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
  readonly #steadyMs: number;
  #steady = 0;
  #lastSteady = Number.NEGATIVE_INFINITY;

  /** `steadyMs`: the band that counts as in sync (STEADY_MS; SEEK_MODE_MS in seek mode). */
  constructor(now: number, steadyMs = STEADY_MS) {
    this.#since = now;
    this.#steadyMs = steadyMs;
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
    if (Math.abs(driftMs) <= this.#steadyMs) {
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
