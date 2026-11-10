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
// - `WebAudioCheck`: whether the Web Audio path works at all: the audio clock runs at the rate the
//   context reports (iOS can render at 44.1 kHz while the hardware pulls 48 kHz, report uhmxlu), and
//   the output has no steady dropouts. The player recreates the context at the implied rate, or
//   falls back to the direct path (Auto).
// - `webAudioPlan`: the per-device "Web Audio" setting (Auto / On / Off), `?webaudio=0/1` and
//   what Auto learnt on this browser, in that order of precedence.
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

// --- Is the Web Audio path healthy? (iOS sample-rate mismatch, report uhmxlu) ---
//
// On the iPod touch 7 (iOS 15.1) the AudioContext reported 44100 Hz while its clock ran 1086–1091
// ms per wall second (= 48000 / 44100): the hardware pulled 48 kHz from a graph rendering 44.1 kHz,
// and the output had 5–11 dropouts per second. `WebAudioCheck` sees this from the health samples
// (no UA sniffing); the player then recreates the context at the rate the clock implies, and if
// that doesn't help, plays straight from the elements.

/** The audio clock counts as off when it runs more than this many ms per 1000 ms fast or slow (2 %). */
export const CLOCK_TOLERANCE = 20;
/** ...in this many health samples in a row (one a second). */
export const CLOCK_SAMPLES = 3;
/**
 * A clock sample outside [CLOCK_MIN, CLOCK_MAX] isn't a rate: the context stalled (interrupted,
 * throttled) or caught up; it neither counts nor resets.
 */
export const CLOCK_MIN = 500;
export const CLOCK_MAX = 2000;
/** A second with at least this many gaps (dropouts) counts toward a failing path... */
export const GAP_RATE = 3;
/** ...for this many seconds in a row. */
export const GAP_SECONDS = 3;
/**
 * ...but only when at most this share of its windows were exact silence throughout: sparse
 * material (a click track: a 20 ms click a second, exact zeros between) has "gaps" next to every
 * click but is mostly silent windows, while dropouts in music leave next to no silent window.
 */
export const GAP_MAX_SILENT_SHARE = 0.2;
/** Clean clock samples in a row after which the path counts as fine (until something trips). */
export const OK_SAMPLES = 5;

/** The sample rates an audio device runs at; an implied rate snaps to the nearest one. */
export const STANDARD_RATES = [8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000];

/** `hz` snapped to the nearest standard rate within 1.5 %, else rounded. */
export function snapRate(hz: number): number {
  let best = Math.round(hz);
  let bestErr = Infinity;
  for (const r of STANDARD_RATES) {
    const err = Math.abs(hz - r) / r;
    if (err < bestErr) {
      bestErr = err;
      if (err <= 0.015) best = r;
    }
  }
  return best;
}

/** One second of the Web Audio path, as WebAudioCheck sees it. */
export interface PathSample {
  /** `ClockWatch.sample` (audio-clock ms per 1000 ms of wall clock), undefined if none. */
  ck: number | undefined;
  /** The context ran during that second. */
  running: boolean;
  /** The element should be heard (see MuteSample); gaps only count then. */
  shouldSound: boolean;
  /** The playing element's meter over that second (undefined: not read). */
  level?: Level;
}

export type PathVerdict =
  | { kind: "pending" }
  | { kind: "ok" }
  /** The audio clock runs at `ratio` × real time; the hardware runs at about `impliedRate`. */
  | { kind: "clock"; ratio: number; sampleRate: number; impliedRate: number }
  /** Steady dropouts with the clock fine. */
  | { kind: "gaps"; gapsPerSecond: number };

/**
 * Decides whether the Web Audio path works: "clock" when the audio clock stays more than
 * CLOCK_TOLERANCE off for CLOCK_SAMPLES samples in a row while the context runs, "gaps" when the
 * playing element has GAP_RATE+ dropouts a second for GAP_SECONDS seconds in a row while it should
 * sound (and isn't sparse material), "ok" after OK_SAMPLES clean clock samples. A failing verdict
 * sticks until `reset()`; "ok" can still turn into a failing one later.
 */
export class WebAudioCheck {
  readonly #sampleRate: number;
  #clockRun: number[] = [];
  #okRun = 0;
  #gapRun: number[] = [];
  #verdict: PathVerdict = { kind: "pending" };

  /** @param sampleRate what the context reports (`AudioContext.sampleRate`) */
  constructor(sampleRate: number) {
    this.#sampleRate = sampleRate;
  }

  get verdict(): PathVerdict {
    return this.#verdict;
  }

  /** Whether the verdict is a failing one (clock or gaps). */
  get failed(): boolean {
    return this.#verdict.kind === "clock" || this.#verdict.kind === "gaps";
  }

  sample(s: PathSample): PathVerdict {
    if (this.failed) return this.#verdict;
    const ck = s.ck;
    if (s.running && ck !== undefined && ck >= CLOCK_MIN && ck <= CLOCK_MAX) {
      if (Math.abs(ck - 1000) > CLOCK_TOLERANCE) {
        this.#okRun = 0;
        this.#clockRun.push(ck);
        if (this.#clockRun.length >= CLOCK_SAMPLES) {
          const sorted = this.#clockRun.slice().sort((a, b) => a - b);
          const ratio = (sorted[Math.floor(sorted.length / 2)] ?? 1000) / 1000;
          this.#verdict = { kind: "clock", ratio, sampleRate: this.#sampleRate,
            impliedRate: snapRate(this.#sampleRate * ratio) };
          return this.#verdict;
        }
      } else {
        this.#clockRun = [];
        this.#okRun++;
      }
    }
    const l = s.level;
    if (s.running && s.shouldSound && l !== undefined && l.windows >= MIN_WINDOWS_PER_SECOND) {
      if (l.gaps >= GAP_RATE && l.silent <= l.windows * GAP_MAX_SILENT_SHARE) {
        this.#gapRun.push(l.gaps);
        if (this.#gapRun.length > GAP_SECONDS) this.#gapRun.shift();
        // Only "gaps with the clock fine": while the clock is off (or not yet measured), its own
        // verdict (and fix) comes first.
        if (this.#gapRun.length >= GAP_SECONDS && this.#clockRun.length === 0 && this.#okRun > 0) {
          const sum = this.#gapRun.reduce((a, b) => a + b, 0);
          this.#verdict = { kind: "gaps", gapsPerSecond: Math.round(sum / this.#gapRun.length * 10) / 10 };
          return this.#verdict;
        }
      } else {
        this.#gapRun = [];
      }
    }
    if (this.#verdict.kind === "pending" && this.#okRun >= OK_SAMPLES && this.#gapRun.length === 0) {
      this.#verdict = { kind: "ok" };
    }
    return this.#verdict;
  }

  reset(): void {
    this.#clockRun = [];
    this.#okRun = 0;
    this.#gapRun = [];
    this.#verdict = { kind: "pending" };
  }
}

// --- The per-device "Web Audio" setting ---

/** The listen-mode setting, per browser: auto (default), on, off. */
export type WebAudioSetting = "auto" | "on" | "off";

/** A stored setting, "auto" for anything else (nothing stored, blocked storage). */
export function parseWebAudioSetting(s: string | null | undefined): WebAudioSetting {
  return s === "on" || s === "off" ? s : "auto";
}

/**
 * The `?webaudio=` URL override: false for 0/off/false/no, true for 1/on/true/yes, undefined when
 * absent or anything else (the setting decides).
 */
export function webAudioOverride(param: string | null | undefined): boolean | undefined {
  if (param === null || param === undefined) return undefined;
  const v = param.trim().toLowerCase();
  if (["0", "off", "false", "no"].includes(v)) return false;
  if (["1", "on", "true", "yes"].includes(v)) return true;
  return undefined;
}

/** What Auto learnt on this browser: the rate that fixed the clock, or that only the direct path works. */
export interface WebAudioMemory {
  /** The sample rate to create the context at. */
  rate?: number;
  /** Web Audio failed here even after the rate fix: start on the direct path. */
  direct?: boolean;
  /** Why (for diagnostics): "clock", "gaps", "mute", "rate-fix". */
  reason?: string;
  /** When it was learnt (epoch ms). */
  at: number;
}

/** Auto forgets what it learnt after this long (an OS update may have fixed it). */
export const MEMORY_MAX_AGE_MS = 30 * 24 * 3600 * 1000;

/** The stored memory, or undefined when absent, malformed or older than MEMORY_MAX_AGE_MS. */
export function parseWebAudioMemory(json: string | null | undefined, nowMs: number): WebAudioMemory | undefined {
  if (json === null || json === undefined || json === "") return undefined;
  let v: unknown;
  try {
    v = JSON.parse(json);
  } catch {
    return undefined;
  }
  if (typeof v !== "object" || v === null) return undefined;
  const o = v as Record<string, unknown>;
  const at = o["at"];
  if (typeof at !== "number" || !Number.isFinite(at) || nowMs - at > MEMORY_MAX_AGE_MS || at > nowMs + 60_000) return undefined;
  const m: WebAudioMemory = { at };
  const rate = o["rate"];
  if (typeof rate === "number" && Number.isFinite(rate) && rate >= 8000 && rate <= 192000) m.rate = Math.round(rate);
  if (o["direct"] === true) m.direct = true;
  if (typeof o["reason"] === "string") m.reason = o["reason"];
  if (m.rate === undefined && m.direct !== true) return undefined;
  return m;
}

/** How the player starts and what it may do on its own. */
export interface WebAudioPlan {
  /** Route the elements through Web Audio. */
  webAudio: boolean;
  /** Create the context at this rate (from the memory), else the browser's default. */
  sampleRate?: number;
  /** May switch to the direct path by itself when the check fails (Auto only). */
  autoFallback: boolean;
  /** What decided: the URL override, the setting, Auto's memory, or the default. */
  why: "url" | "setting" | "remembered" | "default";
}

/**
 * Precedence: `?webaudio=0/1` (testing) over the setting over Auto's memory. On and `?webaudio=1`
 * use Web Audio (with the rate fix) but never fall back by themselves; Off and `?webaudio=0` play
 * straight from the elements; Auto uses Web Audio unless it learnt that only the direct path works
 * here, and falls back by itself.
 */
export function webAudioPlan(override: boolean | undefined, setting: WebAudioSetting,
    memory: WebAudioMemory | undefined): WebAudioPlan {
  const rate = memory?.rate !== undefined ? { sampleRate: memory.rate } : {};
  if (override === false) return { webAudio: false, autoFallback: false, why: "url" };
  if (override === true) return { webAudio: true, ...rate, autoFallback: false, why: "url" };
  if (setting === "off") return { webAudio: false, autoFallback: false, why: "setting" };
  if (setting === "on") return { webAudio: true, ...rate, autoFallback: false, why: "setting" };
  if (memory?.direct === true) return { webAudio: false, autoFallback: false, why: "remembered" };
  return { webAudio: true, ...rate, autoFallback: true, why: memory?.rate !== undefined ? "remembered" : "default" };
}
