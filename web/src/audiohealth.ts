// Listen mode: is the sound actually coming out? (docs/rooms.md#listen-mode)
//
// WebKit's `MediaElementAudioSourceNode` path is known to stutter and finally go silent on some
// devices (bugs.webkit.org 221334, "Audio passed through WebAudio is delayed and glitchy on
// Safari": on iOS the sound stutters, the gaps widen and then it goes completely silent; reported
// on the iPhone 7/8, whose A10/A11 is the iPod touch 7's class; unfixed). The element keeps
// advancing, so the sync loop can't see it. This module turns what the player measures into
// numbers for the diagnostics ring and decides when the Web Audio path has gone silent:
//
// - `windowStats` / `LevelMeter`: the output level of one element after its GainNode, from an
//   AnalyserNode's time-domain window (read every ~40 ms, so the windows cover the whole signal):
//   peak, RMS, and "gaps" (runs of exact zeros inside a window that also has signal, i.e. a
//   dropout; digital silence in the file looks the same, so it is a diagnostic, not a trigger).
// - `ClockWatch`: how far the AudioContext's clock moved per second of wall clock (a stalled or
//   slow audio clock means the output itself glitches).
// - `MuteDetector`: the Web Audio path has gone silent: the element plays and advances, its gain
//   is up and the context runs, but the meter saw nothing but exact zeros for MUTE_SECONDS in a
//   row, after the path had been heard. The player then offers to switch to playing straight from
//   the elements (that needs a tap on iOS).
// - `webAudioWanted`: the `?webaudio=0` A/B switch on the room URL.
//
// No DOM access and no imports, so it can be tested outside a browser.

/** How often (ms) the player reads each audible element's analyser. */
export const METER_INTERVAL_MS = 40;
/**
 * The analyser's window (samples): 2048 at 44.1 or 48 kHz is 43–46 ms, longer than
 * METER_INTERVAL_MS, so consecutive reads overlap and a short click or dropout isn't missed.
 */
export const METER_FFT_SIZE = 2048;
/** A run of at least this many exact zeros inside a window with signal counts as a gap (~3 ms). */
export const GAP_MIN_SAMPLES = 128;
/** Seconds of exact silence (while it should sound) after which the Web Audio path counts as mute. */
export const MUTE_SECONDS = 10;
/**
 * A second counts toward MUTE_SECONDS only if the meter read at least this many windows in it
 * (with METER_INTERVAL_MS 40 there are ~25; a throttled timer can't prove silence).
 */
export const MIN_WINDOWS_PER_SECOND = 15;

/** One analyser window. */
export interface WindowStats {
  /** Largest absolute sample. */
  peak: number;
  /** Sum of squares (for RMS over several windows). */
  sumSq: number;
  /** Samples in the window. */
  n: number;
  /** Runs of ≥ minGap exact zeros, counted only when the window also has signal. */
  gaps: number;
}

/** Peak, energy and dropouts of one time-domain window. */
export function windowStats(data: ArrayLike<number>, minGap = GAP_MIN_SAMPLES): WindowStats {
  let peak = 0;
  let sumSq = 0;
  let gaps = 0;
  let run = 0;
  const n = data.length;
  for (let i = 0; i < n; i++) {
    const v = data[i] ?? 0;
    if (v === 0) {
      run++;
      continue;
    }
    if (run >= minGap) gaps++;
    run = 0;
    const a = v < 0 ? -v : v;
    if (a > peak) peak = a;
    sumSq += v * v;
  }
  if (run >= minGap) gaps++;
  return { peak, sumSq, n, gaps: peak > 0 ? gaps : 0 };
}

/** What one element's output did over a period (about a second). */
export interface Level {
  /** Windows read. */
  windows: number;
  /** Largest absolute sample (0: nothing but exact zeros, or no windows). */
  peak: number;
  /** RMS over all windows read. */
  rms: number;
  /** Gaps (dropouts) seen, see WindowStats. */
  gaps: number;
  /** Windows that were exact zeros throughout. */
  silent: number;
}

/** Sums analyser windows until `take()`. */
export class LevelMeter {
  #windows = 0;
  #peak = 0;
  #sumSq = 0;
  #n = 0;
  #gaps = 0;
  #silent = 0;

  add(w: WindowStats): void {
    this.#windows++;
    if (w.peak > this.#peak) this.#peak = w.peak;
    this.#sumSq += w.sumSq;
    this.#n += w.n;
    this.#gaps += w.gaps;
    if (w.peak === 0) this.#silent++;
  }

  /** What was added since the last take, then starts over. */
  take(): Level {
    const r: Level = { windows: this.#windows, peak: this.#peak, rms: this.#n > 0 ? Math.sqrt(this.#sumSq / this.#n) : 0,
      gaps: this.#gaps, silent: this.#silent };
    this.reset();
    return r;
  }

  reset(): void {
    this.#windows = 0;
    this.#peak = 0;
    this.#sumSq = 0;
    this.#n = 0;
    this.#gaps = 0;
    this.#silent = 0;
  }
}

/** A level in dBFS, rounded to 0.1 dB; −Infinity (exact silence) as null, for JSON. */
export function toDb(linear: number): number | null {
  if (!(linear > 0)) return null;
  return Math.round(200 * Math.log10(linear)) / 10;
}

/**
 * The AudioContext's clock against the wall clock: `sample` returns how many ms the audio clock
 * moved per 1000 ms of wall clock since the last sample (1000 when it runs normally, 0 when it
 * stalls), or undefined for the first sample or a wall interval under 200 ms.
 */
export class ClockWatch {
  #wallMs: number | undefined;
  #ctxSec = 0;

  sample(wallMs: number, ctxSec: number): number | undefined {
    const prevWall = this.#wallMs;
    const prevCtx = this.#ctxSec;
    if (prevWall !== undefined && wallMs - prevWall < 200) return undefined;
    this.#wallMs = wallMs;
    this.#ctxSec = ctxSec;
    if (prevWall === undefined) return undefined;
    return Math.round(((ctxSec - prevCtx) * 1000 / (wallMs - prevWall)) * 1000);
  }

  reset(): void {
    this.#wallMs = undefined;
    this.#ctxSec = 0;
  }
}

/** One second of one element, as the mute check sees it. */
export interface MuteSample {
  /**
   * It should be heard: the element plays and advances, it isn't muted, its gain is above 0 and
   * the context runs.
   */
  shouldSound: boolean;
  /** The meter over that second. */
  level: Level;
  /**
   * Bytes of audio the element decoded in that second (`webkitAudioDecodedByteCount` delta), where
   * the engine has it; 0 means the element itself isn't decoding, so its silence isn't Web Audio's.
   */
  decodedBytes?: number;
}

/**
 * Decides that the Web Audio path has gone silent: MUTE_SECONDS seconds in a row in which the
 * element should sound and the meter, reading often enough, saw only exact zeros, after the path
 * has been heard at least once (an item that starts with silence, or a path that never worked on
 * a page whose first item is silent, doesn't count). A second in which it shouldn't sound, or the
 * meter read too few windows, neither counts nor resets; one with signal resets.
 */
export class MuteDetector {
  #heard = false;
  #silentSeconds = 0;
  #tripped = false;

  sample(s: MuteSample): boolean {
    if (this.#tripped) return true;
    if (!s.shouldSound || s.level.windows < MIN_WINDOWS_PER_SECOND) return false;
    if (s.level.peak > 0) {
      this.#heard = true;
      this.#silentSeconds = 0;
      return false;
    }
    if (!this.#heard) return false;
    if (s.decodedBytes !== undefined && s.decodedBytes <= 0) return false;
    this.#silentSeconds++;
    if (this.#silentSeconds >= MUTE_SECONDS) this.#tripped = true;
    return this.#tripped;
  }

  /** Seconds of silence counted so far. */
  get silentSeconds(): number {
    return this.#silentSeconds;
  }

  get heard(): boolean {
    return this.#heard;
  }

  get tripped(): boolean {
    return this.#tripped;
  }
}

/**
 * Whether the player routes its elements through Web Audio: yes unless the room URL says
 * `?webaudio=0` (also `off`, `false`, `no`), the A/B switch for the iOS stutter.
 */
export function webAudioWanted(param: string | null | undefined): boolean {
  if (param === null || param === undefined) return true;
  return !["0", "off", "false", "no"].includes(param.trim().toLowerCase());
}

/** A counter's change since the previous value; undefined when either is missing or it went back. */
export function delta(now: number | undefined, before: number | undefined): number | undefined {
  if (now === undefined || before === undefined || !Number.isFinite(now) || !Number.isFinite(before)) return undefined;
  return now >= before ? now - before : undefined;
}
