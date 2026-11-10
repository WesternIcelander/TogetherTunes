// Listen mode (docs/rooms.md#listen-mode): the page plays the room's media itself, in sync with
// the timeline.
//
// Elements: a fixed pool of two `<audio>` and two `<video>` elements, reused for every entry (one
// plays, the other holds the preloaded next entry). Reusing them matters on iOS, where an element
// may only start playing with sound after a user gesture has touched it: the "Click to listen"
// click touches all four. Each element goes through its own Web Audio `GainNode` (created once per
// element via `MediaElementAudioSourceNode`), which applies `originalGainDb` to originals, keeps the
// preloaded element silent, and is what the video / audio-only crossfade ramps (on iOS, `volume`
// can't be set from script).
//
// Video / audio-only switch (docs/rooms.md#audio-only-switching): in audio-only mode video items
// play `audio.m4a` and `mezz.mp4` is never requested. Switching while the timeline plays keeps the
// outgoing element audible while the incoming one loads and syncs at gain 0; once it is steady
// (SwitchGate) the two gains ramp over 150 ms, then the outgoing element is emptied (which stops a
// video download). A video comes into view when its crossfade starts; in audio-only mode the cover
// art in the now-playing panel stands in for it.
//
// Sync loop (every 250 ms, on every state change and on the elements' playing/seeked events): the
// target position comes from the timeline and the server clock; drift above 750 ms → hard seek.
// Below that, in "rate" mode (Chromium, Gecko) a drift above 40 ms starts a rate nudge of ±3 %
// (±5 % above 300 ms; pitch kept) that holds until the drift is within 10 ms; in "seek" mode
// (WebKit's media stack, found by feature detection: Safari and WebKit browsers on iOS, where the
// pitch rises with the rate and every rate change stalls the video) the rate stays 1 and a drift
// above 60 ms (80 ms once a seek has landed inside the band) is corrected by a seek, at most every
// 2.5 s (backing off up to 8 s only while seeks scatter). Every seek of a playing element aims
// ahead by the time this element's seeks have been taking (SeekLead), learnt from where they
// *settled* (SeekLanding: WebKit holds the clock still for ~300 ms after `seeked`, so the drift at
// `seeked` is too optimistic); the loop makes no correction while a landing settles, except a hard
// seek. A seek the browser dropped (iOS resets a seek made before playback started) is just seen
// as drift and done again. The loop never throws: errors are
// recorded in the diagnostics ring (diagnostics.ts) and the next tick runs as usual.
//
// An original that keeps needing hard seeks, that fails to load, or whose type `canPlayType`
// rejects, is played from `audio.m4a` instead for the rest of the session.
//
// Audio health (audiohealth.ts): each element's GainNode feeds an AnalyserNode, read every 40 ms
// while it plays; once a second the output level (peak, RMS, dropouts), the AudioContext's clock
// against the wall clock and the elements' decoder counters go into the diagnostics ring. WebKit's
// Web Audio path for media elements can stutter and then go silent on older devices
// (bugs.webkit.org 221334; seen on the iPod touch 7, report dfh3l7): when an element that should
// be heard has produced only exact zeros for 10 s after having been heard, the player offers "Tap
// to restore sound", and that tap rebuilds the pool without Web Audio (fresh elements, touched in
// the gesture), playing straight from the elements for the rest of the page's life. `?webaudio=0`
// on the room URL starts that way (the A/B switch). Without Web Audio there is no gain: originals
// play without `originalGainDb`, and on iOS (read-only `volume`) the audio-only switch can't
// crossfade. A seek stuck for 5 s on an element routed through Web Audio hands the entry to the
// other element of its kind instead of reloading the stuck one (WebKit's provider for a routed
// element is suspected to break when its pipeline is restarted). The context is resumed when
// WebKit reports it `interrupted`/`suspended` and when the page becomes visible again.
import {
  ClockWatch, delta, LevelMeter, METER_FFT_SIZE, METER_INTERVAL_MS, MuteDetector, toDb, windowStats, type Level,
} from "./audiohealth.js";
import { describeError, recordDecision, recordError, recordEvent, recordHealth } from "./diagnostics.js";
import {
  chooseSource, CROSSFADE_MS, dbToGain, DriftController, HARD_SEEK_MS, inEndGuard, nextToPreload, SEEK_MODE_MS, SeekGuard,
  SeekLanding, SeekLead, shouldPreload, STEADY_MS, SwitchGate, type Source, type SyncMode,
} from "./listensync.js";
import { positionAt, type CurrentEntry, type RoomState, type Timeline } from "./roomclient.js";

const HAVE_METADATA = 1;
const HAVE_FUTURE_DATA = 3;
/** A paused element further than this (ms) from the timeline is moved there, so resuming starts in sync. */
const PAUSED_SEEK_MS = 100;
/**
 * A seek still pending after this long (ms) reloads the element (a fresh media pipeline; the loop
 * then starts it again at the timeline's position). Making the seek again didn't help: in the iPod
 * touch 7 report (f552z8, iOS 15.1) an element whose first two seeks were made before it had loaded
 * any data never finished a seek again, and three re-seeks 5 s apart each stayed pending until the
 * next item started on the other element.
 */
const SEEK_STUCK_MS = 5000;
/** While autoplay is blocked, a paused element is moved and `play()` retried at most this often (ms). */
const BLOCKED_RETRY_MS = 2000;
/** How often (ms) a health sample is recorded. */
const HEALTH_INTERVAL_MS = 1000;
/** A context that stopped running is asked to resume at most this often (ms). */
const RESUME_RETRY_MS = 5000;

/** Decoder counters an element may have (WebKit's prefixed ones, the standard playback quality). */
interface Counters {
  audioBytes?: number;
  videoBytes?: number;
  frames?: number;
  droppedFrames?: number;
  vqTotal?: number;
  vqDropped?: number;
  vqCorrupted?: number;
}

function num(x: unknown): number | undefined {
  return typeof x === "number" && Number.isFinite(x) ? x : undefined;
}

function counters(el: HTMLMediaElement): Counters {
  const w = el as HTMLMediaElement & Record<string, unknown>;
  const c: Counters = {
    audioBytes: num(w["webkitAudioDecodedByteCount"]),
    videoBytes: num(w["webkitVideoDecodedByteCount"]),
    frames: num(w["webkitDecodedFrameCount"]),
    droppedFrames: num(w["webkitDroppedFrameCount"]),
  };
  if (el instanceof HTMLVideoElement && typeof el.getVideoPlaybackQuality === "function") {
    try {
      const q = el.getVideoPlaybackQuality();
      c.vqTotal = num(q.totalVideoFrames);
      c.vqDropped = num(q.droppedVideoFrames);
      c.vqCorrupted = num((q as unknown as Record<string, unknown>)["corruptedVideoFrames"]);
    } catch {
      // not available
    }
  }
  return c;
}

interface Deck {
  readonly el: HTMLMediaElement;
  readonly video: boolean;
  /** For diagnostics: a0, a1, v0, v1. */
  readonly name: string;
  /** Decides seeks and rate nudges; reset on every load and re-anchor. */
  readonly ctl: DriftController;
  /** How far ahead this element's seeks aim (kept across loads: it is the element's latency). */
  readonly lead: SeekLead;
  /** A seek whose landing the following ticks measure. */
  landing: SeekLanding | undefined;
  /** When the last seek was made (performance.now()). */
  seekAt: number;
  /** Seeks made / seeks that didn't land (dropped or far off) / reloads after a stuck seek, for debug(). */
  seeks: number;
  lostSeeks: number;
  reloads: number;
  /** The position (ms) at the last tick, to see whether the element advances. */
  lastPosMs: number;
  /** When `play()` was last tried while autoplay was blocked. */
  lastBlockedTry: number;
  gain: GainNode | undefined;
  /** After the gain, for the output level (Web Audio only). */
  analyser: AnalyserNode | undefined;
  /** The analyser's window, reused. */
  samples: Float32Array<ArrayBuffer> | undefined;
  readonly meter: LevelMeter;
  /** Whether this element's Web Audio path has gone silent. */
  readonly mute: MuteDetector;
  /** Decoder counters at the last health sample. */
  counters: Counters;
  /** Position (ms) at the last health sample. */
  healthPosMs: number;
  entryId: string | undefined;
  itemId: string | undefined;
  src: Source | undefined;
  /** Moved to the start position after loading (that seek isn't counted by the guard). */
  started: boolean;
  /** A `play()` is in flight. */
  playPending: boolean;
  guard: SeekGuard;
  /** The timeline anchor the guard counts seeks for. */
  anchorKey: string;
  /** The gain last set or ramped to (linear). */
  level: number;
  /** Steps `volume` during a ramp when there is no Web Audio. */
  volumeTimer: number | undefined;
}

function round(x: number): number {
  return Math.round(x);
}

/**
 * Whether `sec` lies inside the element's buffered data (with 0.5 s to spare after it): a seek
 * there measures the element's own latency; one outside also waits for the download.
 */
function isBuffered(el: HTMLMediaElement, sec: number): boolean {
  try {
    const b = el.buffered;
    for (let i = 0; i < b.length; i++) {
      if (sec >= b.start(i) && sec + 0.5 <= b.end(i)) return true;
    }
  } catch {
    // no buffered ranges: not buffered
  }
  return false;
}

/** A video ↔ audio-only switch in progress. */
interface Switch {
  /** Audible until the crossfade; emptied when it ends. */
  readonly from: Deck;
  /** Loads and syncs at gain 0, then fades in. */
  readonly to: Deck;
  readonly gate: SwitchGate;
  /** When the crossfade ends (performance.now()); undefined until it starts. */
  fadeEndsAt: number | undefined;
}

type AudioContextCtor = new () => AudioContext;

function audioContextCtor(): AudioContextCtor | undefined {
  const w = window as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor };
  return w.AudioContext ?? w.webkitAudioContext;
}

function anchorKey(t: Timeline): string {
  return (t.entryId ?? "") + "|" + t.state + "|" + t.anchorServerMs + "|" + t.anchorPositionMs;
}

/** Keeps the pitch while the rate is nudged (also the prefixed names of older Safari / Firefox). */
function keepPitch(el: HTMLMediaElement): void {
  const m = el as HTMLMediaElement & { preservesPitch?: boolean; webkitPreservesPitch?: boolean; mozPreservesPitch?: boolean };
  m.preservesPitch = true;
  if ("webkitPreservesPitch" in m) m.webkitPreservesPitch = true;
  if ("mozPreservesPitch" in m) m.mozPreservesPitch = true;
}

export class ListenPlayer {
  readonly #container: HTMLElement;
  readonly #overlay: HTMLElement;
  readonly #serverNow: () => number;
  readonly #decks: Deck[] = [];
  /** Undefined without Web Audio (`?webaudio=0`, no AudioContext, or after "Tap to restore sound"). */
  #ctx: AudioContext | undefined;
  /** Web Audio was asked for but turned off: "url" (`?webaudio=0`) or "mute" (it went silent). */
  #webAudioOff: "url" | "mute" | undefined;
  /** The Web Audio path went silent; the overlay asks for a tap to rebuild without it. */
  #needsRestore = false;
  /** A deck whose seek is stuck, handed to the other element after this tick. */
  #handOffPending: Deck | undefined;
  readonly #clockWatch = new ClockWatch();
  /** The context has been running (so a later suspension is an interruption, not autoplay policy). */
  #wasRunning = false;
  #lastResumeTry = Number.NEGATIVE_INFINITY;
  /** Items whose original was given up this session (they play `audio.m4a`). */
  readonly #fallback = new Set<string>();
  #state: RoomState | undefined;
  /** `play()` was refused for want of a user gesture. */
  #blocked = false;
  /** The deck for the current entry in the current mode (incoming during a switch). */
  #active: Deck | undefined;
  #switch: Switch | undefined;
  #audioOnly: boolean;
  readonly #mode: SyncMode;

  /**
   * @param container where the elements live (the video one is shown there while it plays)
   * @param overlay the "Click to listen" overlay; shown while the browser doesn't let the page play
   * @param serverNow the server's clock (ClockSync)
   * @param audioOnly start in audio-only mode (video items play `audio.m4a`)
   * @param mode how small drift is corrected (`syncModeFor`: "seek" in WebKit, else "rate")
   * @param webAudio route the elements through Web Audio (false: `?webaudio=0`, play straight from
   *     the elements, no gain)
   */
  constructor(container: HTMLElement, overlay: HTMLElement, serverNow: () => number, audioOnly = false,
      mode: SyncMode = "rate", webAudio = true) {
    this.#audioOnly = audioOnly;
    this.#mode = mode;
    this.#container = container;
    this.#overlay = overlay;
    this.#serverNow = serverNow;
    const Ctor = webAudio ? audioContextCtor() : undefined;
    let ctx: AudioContext | undefined;
    try {
      ctx = Ctor !== undefined ? new Ctor() : undefined;
    } catch (e) {
      recordError("AudioContext", e);
      ctx = undefined;
    }
    this.#ctx = ctx;
    this.#webAudioOff = webAudio ? undefined : "url";
    ctx?.addEventListener("statechange", () => {
      if (ctx !== this.#ctx) return; // the old context, closed by "Tap to restore sound"
      const state: string = ctx.state;
      recordEvent("context", { state, audioMs: Math.round(ctx.currentTime * 1000) });
      if (state === "running") this.#wasRunning = true;
      else this.#resumeContext("statechange");
      this.#updateOverlay();
    });
    this.#buildDecks();
    overlay.addEventListener("click", () => this.#overlayClick());
    // Chrome lets a context start without a gesture when the user has interacted with the site.
    ctx?.resume().catch(() => undefined);
    recordEvent("player", { mode, webAudio: ctx !== undefined, webAudioOff: this.#webAudioOff, audioOnly,
      ...this.#contextInfo() });
    window.setInterval(() => this.#readMeters(), METER_INTERVAL_MS);
    window.setInterval(() => this.#sampleHealth(), HEALTH_INTERVAL_MS);
    // Interruptions show up here (iOS: a call, Siri, the lock screen); record them, and resume.
    document.addEventListener("visibilitychange", () => {
      recordEvent("page", { type: "visibilitychange", visibility: document.visibilityState });
      if (document.visibilityState === "visible") this.#resumeContext("visible");
    });
    for (const type of ["pagehide", "pageshow"]) {
      window.addEventListener(type, (ev) => {
        recordEvent("page", { type, persisted: (ev as PageTransitionEvent).persisted });
      });
    }
  }

  /** The audio context's rate and latencies, for the report. */
  #contextInfo(): Record<string, unknown> {
    const ctx = this.#ctx;
    if (ctx === undefined) return { context: "none" };
    const c = ctx as AudioContext & { outputLatency?: number };
    return { context: ctx.state, sampleRate: ctx.sampleRate,
      baseLatencyMs: typeof c.baseLatency === "number" ? Math.round(c.baseLatency * 10000) / 10 : undefined,
      outputLatencyMs: typeof c.outputLatency === "number" ? Math.round(c.outputLatency * 10000) / 10 : undefined };
  }

  /** The pool: two `<audio>` and two `<video>` elements (through Web Audio if there is a context). */
  #buildDecks(): void {
    let n = 0;
    for (const video of [false, false, true, true]) this.#decks.push(this.#newDeck(video, (video ? "v" : "a") + (n++ % 2)));
  }

  /** Asks a context that stopped running (interrupted, suspended) to run again, at most every 5 s. */
  #resumeContext(why: string): void {
    const ctx = this.#ctx;
    if (ctx === undefined) return;
    const before: string = ctx.state;
    if (before === "running" || before === "closed" || !this.#wasRunning || this.#blocked) return;
    const now = performance.now();
    if (now - this.#lastResumeTry < RESUME_RETRY_MS) return;
    this.#lastResumeTry = now;
    ctx.resume().then(() => {
      recordEvent("resume", { why, before, state: ctx.state });
      this.#updateOverlay();
    }, (e: unknown) => recordEvent("resume", { why, before, error: describeError(e) }));
  }

  /** How small drift is corrected: "rate" (nudge) or "seek" (WebKit). */
  get mode(): SyncMode {
    return this.#mode;
  }

  #newDeck(video: boolean, name: string): Deck {
    const el = document.createElement(video ? "video" : "audio");
    el.preload = "auto";
    if (el instanceof HTMLVideoElement) {
      el.playsInline = true;
      el.setAttribute("playsinline", "");
      el.className = "room-video";
    }
    el.hidden = true;
    keepPitch(el);
    let gain: GainNode | undefined;
    let analyser: AnalyserNode | undefined;
    if (this.#ctx !== undefined) {
      try {
        const source = this.#ctx.createMediaElementSource(el);
        gain = this.#ctx.createGain();
        gain.gain.value = 0;
        source.connect(gain);
        // In the chain (not a side branch), so every engine pulls it.
        try {
          analyser = this.#ctx.createAnalyser();
          analyser.fftSize = METER_FFT_SIZE;
          gain.connect(analyser);
          analyser.connect(this.#ctx.destination);
        } catch (e) {
          recordError("createAnalyser", e, { el: name });
          analyser = undefined;
          gain.connect(this.#ctx.destination);
        }
      } catch (e) {
        recordError("createMediaElementSource", e, { el: name });
        gain = undefined;
        analyser = undefined;
      }
    }
    const deck: Deck = { el, video, name, ctl: new DriftController(this.#mode), lead: new SeekLead(), landing: undefined,
      seekAt: 0, seeks: 0, lostSeeks: 0, reloads: 0, lastPosMs: -1, lastBlockedTry: Number.NEGATIVE_INFINITY,
      gain, analyser, samples: analyser !== undefined ? new Float32Array(analyser.fftSize) : undefined,
      meter: new LevelMeter(), mute: new MuteDetector(), counters: {}, healthPosMs: -1,
      entryId: undefined, itemId: undefined, src: undefined, started: false,
      playPending: false, guard: new SeekGuard(), anchorKey: "", level: gain !== undefined ? 0 : el.volume,
      volumeTimer: undefined };
    el.addEventListener("error", () => this.#onError(deck));
    // Each of these may change what the loop should do; a tick right away is better than up to 250 ms later.
    for (const type of ["loadedmetadata", "canplay", "playing", "seeked"]) {
      el.addEventListener(type, () => {
        if (deck.src !== undefined && type !== "canplay") this.#mediaEvent(deck, type);
        this.tick();
      });
    }
    for (const type of ["waiting", "stalled", "pause", "ended"]) {
      el.addEventListener(type, () => { if (deck.src !== undefined) this.#mediaEvent(deck, type); });
    }
    this.#container.append(el);
    return deck;
  }

  /** Whether video items play as audio only. */
  get audioOnly(): boolean {
    return this.#audioOnly;
  }

  /** Switches between video and audio-only; a playing item crossfades to the other element. */
  setAudioOnly(on: boolean): void {
    if (on === this.#audioOnly) return;
    this.#audioOnly = on;
    this.tick();
  }

  #mediaEvent(deck: Deck, type: string): void {
    const el = deck.el;
    recordEvent("media", { el: deck.name, type, posMs: round(el.currentTime * 1000), readyState: el.readyState });
  }

  /** What is playing, for checking by hand in the console (`togetherTunesPlayer.debug()`). */
  debug(): Record<string, unknown> {
    const d = this.#active;
    const t = this.#state?.timeline;
    const sw = this.#switch;
    return {
      mode: this.#mode,
      context: this.#ctx?.state ?? "none",
      webAudio: this.#ctx !== undefined,
      webAudioOff: this.#webAudioOff,
      needsRestore: this.#needsRestore,
      audio: this.#contextInfo(),
      blocked: this.#blocked,
      audioOnly: this.#audioOnly,
      switching: sw === undefined ? undefined : {
        from: sw.from.src?.url,
        to: sw.to.src?.url,
        fading: sw.fadeEndsAt !== undefined,
        toDriftMs: t !== undefined ? sw.to.el.currentTime * 1000 - positionAt(t, this.#serverNow()) : undefined,
      },
      source: d?.src?.url,
      original: d?.src?.original,
      gainDb: d?.src?.gainDb,
      rate: d?.el.playbackRate,
      driftMs: d !== undefined && t !== undefined ? d.el.currentTime * 1000 - positionAt(t, this.#serverNow()) : undefined,
      positionMs: d !== undefined ? d.el.currentTime * 1000 : undefined,
      element: d === undefined ? undefined : {
        name: d.name,
        readyState: d.el.readyState,
        seeking: d.el.seeking,
        paused: d.el.paused,
        playPending: d.playPending,
        leadMs: d.lead.ms,
        leadSamples: d.lead.samples,
        seeks: d.seeks,
        lostSeeks: d.lostSeeks,
        reloads: d.reloads,
        seekCooldownMs: d.ctl.cooldownMs,
        seekBandMs: d.ctl.seekBandMs,
        settling: d.landing !== undefined,
        preservesPitch: (d.el as HTMLMediaElement & { preservesPitch?: boolean }).preservesPitch,
        webkitPreservesPitch: (d.el as HTMLMediaElement & { webkitPreservesPitch?: boolean }).webkitPreservesPitch,
      },
      preloaded: this.#decks.filter((x) => x !== d && x !== sw?.from && x.src !== undefined).map((x) => x.src?.url),
      fallback: Array.from(this.#fallback),
    };
  }

  /** A new room state (full or delta applied). */
  update(state: RoomState | undefined): void {
    this.#state = state;
    this.tick();
  }

  /** Stops everything (leaving listen mode, kicked). */
  stop(): void {
    this.#state = undefined;
    this.#idle();
  }

  /** Nothing to play: every element stopped and emptied. */
  #idle(): void {
    for (const d of this.#decks) this.#clear(d);
    this.#active = undefined;
    this.#switch = undefined;
    this.#container.hidden = true;
    this.#updateOverlay();
  }

  /**
   * The "Click to listen" click: resumes the audio context and touches every element inside the
   * gesture, so each may play later without one (iOS asks per element).
   */
  unlock(): void {
    this.#blocked = false;
    this.#ctx?.resume().then(() => this.#updateOverlay(), () => undefined);
    const sw = this.#switch;
    for (const d of this.#decks) {
      if (d === this.#active || d === sw?.from) continue;
      if (d.src === undefined) {
        d.el.load();
      } else {
        // The preloaded element: play and stop it at once; its gain is 0, so nothing is heard.
        this.#setGain(d, 0);
        d.el.play().then(() => { if (d !== this.#active) d.el.pause(); }, () => undefined);
      }
    }
    const a = this.#active;
    const t = this.#state?.timeline;
    if (a !== undefined && a.src !== undefined && t !== undefined && t.state === "playing") {
      // Inside the gesture: move it to the timeline first (iOS has loaded nothing before this click,
      // and may drop a seek made before playback starts; the loop sees that and seeks again), then
      // call play() even if one is pending, since only a play() in the gesture unlocks the element.
      // Not while the start seek is still pending: a second seek on top of one that hasn't finished
      // before any data was loaded left the iPod touch 7's element unable to finish a seek again
      // (f552z8); the loop corrects the drift once it plays.
      try {
        const target = positionAt(t, this.#serverNow());
        if (a.el.readyState >= HAVE_METADATA && !a.el.seeking
            && Math.abs(a.el.currentTime * 1000 - target) > PAUSED_SEEK_MS) {
          this.#seekTo(a, target + a.lead.ms, true, "unlock");
        }
        a.started = a.el.readyState >= HAVE_METADATA;
      } catch (e) {
        recordError("unlock", e);
      }
      a.playPending = false;
      this.#play(a);
    }
    recordEvent("unlock", { context: this.#ctx?.state ?? "none", readyState: a?.el.readyState, seeking: a?.el.seeking });
    this.#updateOverlay();
    this.tick();
  }

  /** One pass of the sync loop. Never throws: an error is recorded and the next tick runs as usual. */
  tick(): void {
    try {
      this.#tick();
      const stuck = this.#handOffPending;
      if (stuck !== undefined) {
        this.#handOffPending = undefined;
        if (!this.#handOff(stuck)) this.#reload(stuck);
        this.#tick();
      }
    } catch (e) {
      this.#handOffPending = undefined;
      recordError("tick", e);
    }
  }

  #tick(): void {
    const s = this.#state;
    const cur = s?.current;
    const src = s !== undefined && cur !== undefined && cur.status === "ready" && s.timeline.entryId === cur.entryId
      ? this.#source(cur) : undefined;
    if (s === undefined || cur === undefined || src === undefined) {
      this.#idle();
      return;
    }
    // Decks of this entry that are still loaded (the one playing, and during a switch the other).
    const same = (d: Deck | undefined): d is Deck => d !== undefined && d.entryId === cur.entryId && d.src !== undefined;
    const prevActive = this.#active;
    const prevFrom = this.#switch?.from;
    let deck = this.#decks.find((d) => d.entryId === cur.entryId && d.src?.url === src.url);
    if (deck === undefined) {
      deck = this.#free(src.video, [prevActive, prevFrom].filter(same));
      this.#load(deck, cur, src);
    }
    this.#active = deck;

    // A video ↔ audio-only switch: the element that was playing this entry in the other kind stays
    // audible until the new one is in sync (switching back mid-way swaps the roles).
    let from: Deck | undefined;
    if (same(prevActive) && prevActive !== deck && prevActive.src?.video !== src.video) from = prevActive;
    else if (same(prevFrom) && prevFrom !== deck && prevFrom.src?.video !== src.video) from = prevFrom;
    let sw = this.#switch;
    if (from === undefined) {
      sw = undefined;
    } else if (sw === undefined || sw.from !== from || sw.to !== deck) {
      sw = { from, to: deck, gate: new SwitchGate(performance.now(), this.#mode === "seek" ? SEEK_MODE_MS : STEADY_MS),
        fadeEndsAt: undefined };
    }
    this.#switch = sw;

    const t = s.timeline;
    const target = positionAt(t, this.#serverNow());
    const duration = cur.item.durationMs;
    const playing = t.state === "playing" && (duration === undefined || target < duration);
    if (sw !== undefined && (!playing || !this.#audible() || sw.from.el.paused)) {
      // Nothing is heard (paused, autoplay blocked, not started): switch at once.
      this.#clear(sw.from);
      sw = undefined;
      this.#switch = undefined;
    }
    if (sw === undefined) {
      this.#sync(deck, t, target, playing, dbToGain(src.gainDb));
    } else {
      this.#crossfade(sw, t, target);
      sw = this.#switch; // undefined once the crossfade has ended
    }

    // Preload the next entry about 10 s before the end; keep it while it is still the next one.
    const next = nextToPreload(s);
    const nextSrc = next !== undefined ? this.#source(next) : undefined;
    let preload: Deck | undefined;
    if (next !== undefined && nextSrc !== undefined) {
      preload = this.#decks.find((d) => d !== deck && d.entryId === next.entryId && d.src?.url === nextSrc.url);
      if (preload === undefined && playing && duration !== undefined && shouldPreload(duration - target)) {
        preload = this.#free(nextSrc.video, sw !== undefined ? [deck, sw.from] : [deck]);
        this.#load(preload, next, nextSrc);
      }
    }
    for (const d of this.#decks) {
      if (d === deck || d === sw?.from) continue;
      if (d === preload) {
        this.#setGain(d, 0);
        if (!d.el.paused) d.el.pause();
        d.el.hidden = true;
      } else {
        this.#clear(d);
      }
    }
    // The video in view: the outgoing one until the crossfade starts, then the incoming one.
    const fading = sw === undefined || sw.fadeEndsAt !== undefined;
    const shown = sw === undefined ? (deck.video ? deck : undefined)
      : fading ? (deck.video ? deck : sw.from.video ? sw.from : undefined)
      : sw.from.video ? sw.from : undefined;
    deck.el.hidden = deck !== shown;
    if (sw !== undefined) sw.from.el.hidden = sw.from !== shown;
    this.#container.hidden = shown === undefined;
    this.#updateOverlay();
  }

  /** Whether sound can be heard now (the page may play and the audio context runs). */
  #audible(): boolean {
    return !this.#blocked && (this.#ctx === undefined || this.#ctx.state === "running");
  }

  /** One tick of a switch: both elements follow the timeline; the gains move once `to` is in sync. */
  #crossfade(sw: Switch, t: Timeline, target: number): void {
    const now = performance.now();
    const fromSrc = sw.from.src;
    const toSrc = sw.to.src;
    if (fromSrc === undefined || toSrc === undefined) return;
    if (sw.fadeEndsAt === undefined) {
      this.#sync(sw.from, t, target, true, dbToGain(fromSrc.gainDb));
      this.#sync(sw.to, t, target, true, 0);
      const el = sw.to.el;
      const settled = !el.paused && !el.seeking && el.readyState >= HAVE_FUTURE_DATA;
      if (sw.gate.sample(now, settled ? el.currentTime * 1000 - target : undefined)) {
        sw.fadeEndsAt = now + CROSSFADE_MS;
        this.#ramp(sw.from, 0, CROSSFADE_MS);
        this.#ramp(sw.to, dbToGain(toSrc.gainDb), CROSSFADE_MS);
        // Empty the outgoing element right after the ramp instead of at the next 250 ms tick.
        window.setTimeout(() => this.tick(), CROSSFADE_MS + 10);
      }
      return;
    }
    this.#sync(sw.from, t, target, true, undefined);
    this.#sync(sw.to, t, target, true, undefined);
    if (now >= sw.fadeEndsAt) {
      // Done: empty the outgoing element (stops a video download).
      this.#clear(sw.from);
      this.#switch = undefined;
    }
  }

  #source(entry: CurrentEntry): Source | undefined {
    return chooseSource(entry.item, (type) => {
      try {
        return document.createElement("audio").canPlayType(type) !== "";
      } catch {
        return true;
      }
    }, this.#fallback.has(entry.item.id), this.#audioOnly);
  }

  /** A deck of the kind that isn't one of `keep` (those playing), preferring an empty one. */
  #free(video: boolean, keep: Deck[]): Deck {
    const candidates = this.#decks.filter((d) => d.video === video && keep.indexOf(d) < 0);
    const deck = candidates.find((d) => d.src === undefined) ?? candidates[0]
      ?? this.#decks.find((d) => d.video === video);
    if (deck === undefined) throw new Error("no free media element");
    return deck;
  }

  #load(deck: Deck, entry: CurrentEntry, src: Source): void {
    this.#loadInto(deck, entry.entryId, entry.item.id, src);
  }

  #loadInto(deck: Deck, entryId: string, itemId: string, src: Source): void {
    this.#clear(deck);
    deck.entryId = entryId;
    deck.itemId = itemId;
    deck.healthPosMs = -1;
    deck.counters = {};
    deck.src = src;
    deck.started = false;
    deck.guard.reset();
    deck.ctl.reset();
    deck.landing = undefined;
    deck.lastPosMs = -1;
    deck.el.src = src.url;
    recordEvent("load", { el: deck.name, url: src.url });
  }

  /**
   * Loads the deck's file again (a seek never finished): a fresh media pipeline at position 0; the
   * loop's start step moves it to the timeline and plays it once the metadata is there. The element
   * keeps its user-gesture unlock (iOS grants it per element, not per source).
   */
  #reload(deck: Deck): void {
    if (deck.src === undefined) return;
    deck.el.pause();
    deck.el.load(); // the src attribute is still set: resource selection starts over
    deck.started = false;
    deck.playPending = false; // the pending play() is rejected with an AbortError
    deck.landing = undefined;
    deck.lastPosMs = -1;
    deck.guard.reset();
    deck.ctl.reset();
    deck.reloads++;
    recordEvent("reload", { el: deck.name, url: deck.src.url });
  }

  /**
   * A seek never finished on an element routed through Web Audio: the entry moves to the other
   * element of the same kind (dropping a preload there) and the stuck one is emptied, instead of
   * restarting the routed element's pipeline. Returns false when it can't (no Web Audio, a switch
   * in progress, no other element), and the caller reloads instead.
   */
  #handOff(deck: Deck): boolean {
    const src = deck.src;
    const entryId = deck.entryId;
    const itemId = deck.itemId;
    if (deck.gain === undefined || src === undefined || entryId === undefined || itemId === undefined) return false;
    if (this.#switch !== undefined || deck !== this.#active) return false;
    const other = this.#decks.find((d) => d !== deck && d.video === deck.video);
    if (other === undefined) return false;
    recordEvent("handoff", { from: deck.name, to: other.name, url: src.url, droppedPreload: other.src?.url });
    deck.reloads++;
    this.#clear(deck);
    this.#loadInto(other, entryId, itemId, src);
    this.#active = other;
    return true;
  }

  #clear(deck: Deck): void {
    if (deck.src === undefined) return;
    recordEvent("clear", { el: deck.name, url: deck.src.url });
    this.#setGain(deck, 0);
    deck.el.pause();
    deck.el.removeAttribute("src");
    deck.el.load(); // stops the download
    deck.el.hidden = true;
    deck.entryId = undefined;
    deck.itemId = undefined;
    deck.src = undefined;
    deck.started = false;
    deck.playPending = false;
    deck.landing = undefined;
    if (deck === this.#active) this.#active = undefined;
    if (deck === this.#switch?.from || deck === this.#switch?.to) this.#switch = undefined;
  }

  /** Sets the deck's gain at once (cancelling a ramp). */
  #setGain(deck: Deck, linear: number): void {
    if (deck.level === linear) return;
    deck.level = linear;
    this.#stopVolumeRamp(deck);
    if (deck.gain !== undefined) {
      deck.gain.gain.cancelScheduledValues(0);
      deck.gain.gain.value = linear;
    } else {
      try {
        deck.el.volume = Math.min(1, linear);
      } catch {
        // iOS: volume is read-only.
      }
    }
  }

  /** Moves the deck's gain linearly to `linear` over `ms` (the GainNode's own ramp where there is one). */
  #ramp(deck: Deck, linear: number, ms: number): void {
    deck.level = linear;
    this.#stopVolumeRamp(deck);
    const ctx = this.#ctx;
    if (deck.gain !== undefined && ctx !== undefined) {
      const g = deck.gain.gain;
      const now = ctx.currentTime;
      const from = g.value;
      g.cancelScheduledValues(now);
      g.setValueAtTime(from, now);
      g.linearRampToValueAtTime(linear, now + ms / 1000);
      return;
    }
    // No Web Audio: step the element's volume.
    const el = deck.el;
    const from = el.volume;
    const to = Math.min(1, linear);
    const start = performance.now();
    deck.volumeTimer = window.setInterval(() => {
      const k = Math.min(1, (performance.now() - start) / ms);
      try {
        el.volume = from + (to - from) * k;
      } catch {
        // iOS: volume is read-only.
      }
      if (k >= 1) this.#stopVolumeRamp(deck);
    }, 15);
  }

  #stopVolumeRamp(deck: Deck): void {
    if (deck.volumeTimer !== undefined) {
      window.clearInterval(deck.volumeTimer);
      deck.volumeTimer = undefined;
    }
  }

  /**
   * Keeps the deck on the timeline. `gain` (linear) is set unless undefined (a crossfade ramp owns
   * it then). Records what it did in the diagnostics ring.
   */
  #sync(deck: Deck, t: Timeline, target: number, playing: boolean, gain: number | undefined): void {
    const el = deck.el;
    if (deck.src === undefined) return;
    if (gain !== undefined) this.#setGain(deck, gain);
    const key = anchorKey(t);
    if (key !== deck.anchorKey) {
      deck.anchorKey = key;
      deck.guard.reset();
      deck.ctl.reset();
      deck.landing = undefined; // the timeline moved: that drift isn't the seek's
    }
    const pos = el.currentTime * 1000;
    const advancing = !el.paused && pos !== deck.lastPosMs;
    deck.lastPosMs = pos;
    const drift = pos - target;
    const action = this.#correct(deck, target, drift, playing, advancing);
    recordDecision({ el: deck.name, drift: Number.isFinite(drift) ? round(drift) : undefined, action,
      rate: el.playbackRate, readyState: el.readyState, seeking: el.seeking, paused: el.paused });
  }

  /** The decision part of #sync; returns what it did. */
  #correct(deck: Deck, target: number, drift: number, playing: boolean, advancing: boolean): string {
    const el = deck.el;
    const src = deck.src;
    if (src === undefined) return "empty";
    const now = performance.now();
    if (el.readyState < HAVE_METADATA) return "loading"; // still loading (iOS loads nothing before a play())
    // The last second of the item (END_GUARD_MS): no seeks and no new start; whatever plays plays
    // out (an element that ended early just waits), and the next entry starts on time.
    if (playing && inEndGuard(target, deck.lead.ms, el.duration * 1000)) {
      deck.landing = undefined;
      return "ending";
    }
    if (!deck.started) {
      deck.started = true;
      this.#setRate(deck, 1);
      const measure = playing && this.#audible();
      this.#seekTo(deck, target + (measure ? deck.lead.ms : 0), measure, "start");
      if (playing) this.#play(deck);
      return "start";
    }
    if (!playing) {
      if (!el.paused) el.pause();
      this.#setRate(deck, 1);
      deck.landing = undefined;
      // Keep the paused element where the timeline is, so resuming starts in sync.
      if (!el.seeking && Math.abs(drift) > PAUSED_SEEK_MS) {
        this.#seekTo(deck, target, false, "paused");
        return "paused-seek";
      }
      return "paused";
    }
    if (el.paused) {
      // Not playing yet (or the browser refused): its position says nothing about the file, so
      // move it to the timeline without counting that as a hard seek, then start it.
      if (this.#blocked) {
        if (now - deck.lastBlockedTry < BLOCKED_RETRY_MS) return "blocked";
        deck.lastBlockedTry = now;
      }
      if (!el.seeking && Math.abs(drift) > PAUSED_SEEK_MS) {
        const measure = this.#audible();
        this.#seekTo(deck, target + (measure ? deck.lead.ms : 0), measure, "resume");
      }
      this.#play(deck);
      return "play";
    }
    if (deck.playPending) return "play-pending";
    if (el.seeking) {
      if (now - deck.seekAt > SEEK_STUCK_MS) {
        recordEvent("seek-stuck", { el: deck.name, forMs: round(now - deck.seekAt) });
        if (deck.gain !== undefined && this.#switch === undefined && deck === this.#active) {
          this.#handOffPending = deck; // after this tick (tick() hands it over, or reloads)
          return "handoff";
        }
        this.#reload(deck);
        return "reload";
      }
      return "seeking";
    }
    // Buffering: the drift means nothing yet. (Not just readyState: iOS may report less than
    // HAVE_FUTURE_DATA while it plays, so an element that advances counts as playing.)
    if (el.readyState < HAVE_FUTURE_DATA && !advancing) return "buffering";
    const landing = deck.landing;
    if (landing !== undefined) {
      const firstTick = landing.seekedAt === undefined;
      const r = landing.sample(now, drift);
      if (firstTick && r.kind !== "expired") {
        recordEvent("seeked", { el: deck.name, why: landing.why, driftMs: round(drift), tookMs: round(now - landing.at) });
      }
      if (r.kind === "settling") return "settling";
      deck.landing = undefined;
      if (r.kind === "lost") {
        deck.lostSeeks++;
        recordEvent("landed", { el: deck.name, why: landing.why, lost: true, driftMs: round(r.driftMs) });
      } else if (r.kind === "settled") {
        const used = landing.learn && deck.lead.landed(r.driftMs);
        if (!landing.learn) deck.lead.guess(r.driftMs);
        deck.ctl.landed(r.driftMs, { stable: r.stable, delayMs: used ? landing.leadMs - r.driftMs : undefined });
        recordEvent("landed", { el: deck.name, why: landing.why, driftMs: round(r.driftMs), stable: r.stable,
          seekedDriftMs: round(r.seekedDriftMs), afterMs: round(r.afterMs), leadMs: round(deck.lead.ms), used,
          cooldownMs: deck.ctl.cooldownMs, bandMs: deck.ctl.seekBandMs });
      }
    }
    const a = deck.ctl.decide(now, drift);
    if (a.kind === "seek") {
      const hard = Math.abs(drift) > HARD_SEEK_MS;
      if (hard && src.original && !deck.guard.hardSeek(now)) {
        this.#giveUpOriginal(deck);
        return "give-up";
      }
      this.#setRate(deck, 1);
      this.#seekTo(deck, target + deck.lead.ms, true, hard ? "hard" : "correct");
      return "seek";
    }
    if (el.playbackRate !== a.rate) {
      this.#setRate(deck, a.rate);
      return "rate";
    }
    return a.rate === 1 ? "steady" : "nudge";
  }

  #setRate(deck: Deck, rate: number): void {
    const el = deck.el;
    if (el.playbackRate === rate) return;
    el.playbackRate = rate;
    // Some browsers reset it on a new source; keep asking for the pitch to be kept.
    if (rate !== 1) keepPitch(el);
    recordEvent("rate", { el: deck.name, rate });
  }

  /**
   * Moves the element to `posMs` (clamped to the file). `measure`: the following ticks check where
   * it settled (SeekLanding) and, for a seek within the buffered data, teach the element's SeekLead.
   * A measured seek aims `deck.lead.ms` ahead of the target.
   */
  #seekTo(deck: Deck, posMs: number, measure: boolean, why: string): void {
    const el = deck.el;
    let pos = Math.max(0, posMs);
    if (Number.isFinite(el.duration) && el.duration > 0) pos = Math.min(pos, el.duration * 1000 - 50);
    if (!Number.isFinite(pos)) {
      recordEvent("seek-skipped", { el: deck.name, why, posMs: String(posMs) });
      return;
    }
    const buffered = isBuffered(el, pos / 1000);
    el.currentTime = pos / 1000;
    deck.seekAt = performance.now();
    deck.seeks++;
    deck.landing = measure ? new SeekLanding(deck.seekAt, why, deck.lead.ms, buffered) : undefined;
    recordEvent("seek", { el: deck.name, why, toMs: round(pos), leadMs: round(deck.lead.ms), readyState: el.readyState,
      paused: el.paused, buffered });
  }

  #play(deck: Deck): void {
    if (deck.playPending) return;
    deck.playPending = true;
    let p: Promise<void> | undefined;
    try {
      p = deck.el.play();
    } catch (e) {
      deck.playPending = false;
      this.#playFailed(e);
      return;
    }
    // Very old browsers return nothing; Safari 15 returns a promise.
    if (p === undefined) {
      deck.playPending = false;
      return;
    }
    p.then(() => {
      deck.playPending = false;
      this.#blocked = false;
      this.#updateOverlay();
      this.tick();
    }, (e: unknown) => {
      deck.playPending = false;
      this.#playFailed(e);
    });
  }

  #playFailed(e: unknown): void {
    const name = typeof e === "object" && e !== null && "name" in e ? String((e as { name: unknown }).name) : String(e);
    recordEvent("play-failed", { name });
    if (name === "NotAllowedError") {
      this.#blocked = true;
      this.#updateOverlay();
    }
    // AbortError (the source changed meanwhile) needs nothing; the next tick tries again.
  }

  #onError(deck: Deck): void {
    if (deck.src === undefined || deck.el.getAttribute("src") === null) return; // cleared on purpose
    const itemId = deck.itemId;
    recordEvent("element-error", { el: deck.name, url: deck.src.url, code: deck.el.error?.code,
      message: deck.el.error?.message });
    if (deck.src.original && itemId !== undefined) {
      this.#giveUpOriginal(deck);
    } else {
      console.warn("Can't play " + deck.src.url, deck.el.error);
    }
  }

  /** Plays this item from `audio.m4a` from now on (the next tick loads it). */
  #giveUpOriginal(deck: Deck): void {
    if (deck.itemId !== undefined) this.#fallback.add(deck.itemId);
    this.#clear(deck);
    queueMicrotask(() => this.tick());
  }

  #updateOverlay(): void {
    const wants = this.#active !== undefined && this.#state?.timeline.state === "playing";
    const locked = this.#blocked || (this.#ctx !== undefined && this.#ctx.state !== "running");
    this.#overlay.hidden = !(wants && (locked || this.#needsRestore));
    const button = this.#overlay.querySelector("button");
    const text = this.#needsRestore ? "Tap to restore sound" : "Click to listen";
    if (button !== null && button.textContent !== text) button.textContent = text;
  }

  /** The overlay's button: "Click to listen", or after a mute "Tap to restore sound". */
  #overlayClick(): void {
    if (this.#needsRestore) this.#restoreWithoutWebAudio();
    else this.unlock();
  }

  /**
   * Inside the "Tap to restore sound" gesture: empties and drops the Web Audio elements, closes
   * the context, builds a fresh pool that plays straight from the elements, loads the current
   * entry and unlocks every new element (the usual start seek follows once the metadata is there).
   */
  #restoreWithoutWebAudio(): void {
    recordEvent("restore", { from: this.#active?.name, ...this.#contextInfo() });
    this.#needsRestore = false;
    this.#webAudioOff = "mute";
    const old = this.#decks.splice(0);
    this.#switch = undefined;
    for (const d of old) {
      this.#clear(d);
      try {
        d.gain?.disconnect();
        d.analyser?.disconnect();
      } catch {
        // already disconnected
      }
      d.el.remove();
    }
    this.#active = undefined;
    const ctx = this.#ctx;
    this.#ctx = undefined;
    ctx?.close().catch(() => undefined);
    this.#buildDecks();
    this.tick(); // loads the current entry into a new element
    this.unlock(); // play() on it and load() on the others, inside the gesture
  }

  /** Reads the analyser of every element that plays (every METER_INTERVAL_MS). */
  #readMeters(): void {
    try {
      for (const d of this.#decks) {
        const a = d.analyser;
        const buf = d.samples;
        if (a === undefined || buf === undefined || d.src === undefined || d.el.paused) continue;
        if (typeof a.getFloatTimeDomainData === "function") {
          a.getFloatTimeDomainData(buf);
        } else {
          const bytes = new Uint8Array(buf.length);
          a.getByteTimeDomainData(bytes);
          for (let i = 0; i < bytes.length; i++) buf[i] = ((bytes[i] ?? 128) - 128) / 128;
        }
        d.meter.add(windowStats(buf));
      }
    } catch (e) {
      recordError("meter", e);
    }
  }

  /** One health sample (every second) into the diagnostics ring, and the mute check. */
  #sampleHealth(): void {
    try {
      this.#health();
    } catch (e) {
      recordError("health", e);
    }
  }

  #health(): void {
    const ctx = this.#ctx;
    const a = this.#active;
    const levels: Record<string, Level> = {};
    for (const d of this.#decks) {
      const l = d.meter.take();
      if (l.windows > 0) levels[d.name] = l;
    }
    if (a === undefined && Object.keys(levels).length === 0) {
      this.#clockWatch.reset();
      return;
    }
    const sample: Record<string, unknown> = {};
    const ctxState: string | undefined = ctx?.state;
    if (ctx !== undefined) {
      sample["ctx"] = ctxState;
      sample["ck"] = this.#clockWatch.sample(performance.now(), ctx.currentTime);
      if (ctxState !== "running") this.#resumeContext("health");
    }
    if (document.visibilityState !== "visible") sample["vis"] = document.visibilityState;
    const out: Record<string, unknown[]> = {};
    for (const name of Object.keys(levels)) {
      const l = levels[name] as Level;
      // [peak dB, RMS dB, windows read, gaps, silent windows]; null dB = exact silence
      out[name] = [toDb(l.peak), toDb(l.rms), l.windows, l.gaps, l.silent];
    }
    if (Object.keys(out).length > 0) sample["out"] = out;
    if (a !== undefined) {
      const el = a.el;
      const t = this.#state?.timeline;
      const pos = el.currentTime * 1000;
      sample["el"] = a.name;
      sample["pos"] = round(pos);
      if (t !== undefined) sample["dr"] = round(pos - positionAt(t, this.#serverNow()));
      sample["rs"] = el.readyState;
      if (el.paused) sample["p"] = 1;
      if (el.seeking) sample["sk"] = 1;
      if (el.muted) sample["mu"] = 1;
      sample["g"] = Math.round(a.level * 1000) / 1000;
      const c = counters(el);
      const prev = a.counters;
      a.counters = c;
      // [audio bytes, video bytes, frames, dropped frames] decoded in this second (WebKit)
      if (c.audioBytes !== undefined || c.frames !== undefined) {
        sample["wk"] = [delta(c.audioBytes, prev.audioBytes), delta(c.videoBytes, prev.videoBytes),
          delta(c.frames, prev.frames), delta(c.droppedFrames, prev.droppedFrames)];
      }
      // getVideoPlaybackQuality totals: [frames, dropped, corrupted]
      if (c.vqTotal !== undefined) sample["vq"] = [c.vqTotal, c.vqDropped, c.vqCorrupted];
      const advancing = !el.paused && a.healthPosMs >= 0 && pos !== a.healthPosMs;
      a.healthPosMs = pos;
      const level = levels[a.name];
      if (a.analyser !== undefined && level !== undefined && this.#switch === undefined && !this.#needsRestore) {
        const shouldSound = advancing && !el.seeking && !el.muted && a.level > 0 && ctxState === "running" && !this.#blocked;
        // The decoded-bytes counter only counts where the engine fills it (it may stay 0 throughout).
        const decodedBytes = (c.audioBytes ?? 0) > 0 ? delta(c.audioBytes, prev.audioBytes) : undefined;
        const muted = a.mute.sample({ shouldSound, level, decodedBytes });
        if (a.mute.silentSeconds > 0) sample["ms"] = a.mute.silentSeconds;
        if (muted) this.#webAudioMuted(a);
      }
    }
    recordHealth(sample);
  }

  /** The Web Audio path of `deck` has gone silent: ask for a tap to play without it. */
  #webAudioMuted(deck: Deck): void {
    recordEvent("webaudio-mute", { el: deck.name, url: deck.src?.url, posMs: round(deck.el.currentTime * 1000),
      silentS: deck.mute.silentSeconds, ...this.#contextInfo() });
    this.#needsRestore = true;
    this.#updateOverlay();
  }
}
