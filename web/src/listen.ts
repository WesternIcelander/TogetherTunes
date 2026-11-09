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
// Sync loop (every 250 ms and on every state change): the target position comes from the timeline
// and the server clock; drift above 750 ms → hard seek, above 40 ms → rate nudge (pitch kept),
// else normal speed. An original that keeps needing hard seeks, that fails to load, or whose type
// `canPlayType` rejects, is played from `audio.m4a` instead for the rest of the session.
import {
  chooseSource, CROSSFADE_MS, dbToGain, nextToPreload, SeekGuard, shouldPreload, SwitchGate, syncAction, type Source,
} from "./listensync.js";
import { positionAt, type CurrentEntry, type RoomState, type Timeline } from "./roomclient.js";

const HAVE_METADATA = 1;
const HAVE_FUTURE_DATA = 3;
/** A paused element further than this (ms) from the timeline is moved there, so resuming starts in sync. */
const PAUSED_SEEK_MS = 100;

interface Deck {
  readonly el: HTMLMediaElement;
  readonly video: boolean;
  gain: GainNode | undefined;
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
  readonly #ctx: AudioContext | undefined;
  /** Items whose original was given up this session (they play `audio.m4a`). */
  readonly #fallback = new Set<string>();
  #state: RoomState | undefined;
  /** `play()` was refused for want of a user gesture. */
  #blocked = false;
  /** The deck for the current entry in the current mode (incoming during a switch). */
  #active: Deck | undefined;
  #switch: Switch | undefined;
  #audioOnly: boolean;

  /**
   * @param container where the elements live (the video one is shown there while it plays)
   * @param overlay the "Click to listen" overlay; shown while the browser doesn't let the page play
   * @param serverNow the server's clock (ClockSync)
   * @param audioOnly start in audio-only mode (video items play `audio.m4a`)
   */
  constructor(container: HTMLElement, overlay: HTMLElement, serverNow: () => number, audioOnly = false) {
    this.#audioOnly = audioOnly;
    this.#container = container;
    this.#overlay = overlay;
    this.#serverNow = serverNow;
    const Ctor = audioContextCtor();
    let ctx: AudioContext | undefined;
    try {
      ctx = Ctor !== undefined ? new Ctor() : undefined;
    } catch {
      ctx = undefined;
    }
    this.#ctx = ctx;
    ctx?.addEventListener("statechange", () => this.#updateOverlay());
    for (const video of [false, false, true, true]) this.#decks.push(this.#newDeck(video));
    overlay.addEventListener("click", () => this.unlock());
    // Chrome lets a context start without a gesture when the user has interacted with the site.
    ctx?.resume().catch(() => undefined);
  }

  #newDeck(video: boolean): Deck {
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
    if (this.#ctx !== undefined) {
      try {
        const source = this.#ctx.createMediaElementSource(el);
        gain = this.#ctx.createGain();
        gain.gain.value = 0;
        source.connect(gain);
        gain.connect(this.#ctx.destination);
      } catch {
        gain = undefined;
      }
    }
    const deck: Deck = { el, video, gain, entryId: undefined, itemId: undefined, src: undefined, started: false,
      playPending: false, guard: new SeekGuard(), anchorKey: "", level: gain !== undefined ? 0 : el.volume,
      volumeTimer: undefined };
    el.addEventListener("error", () => this.#onError(deck));
    el.addEventListener("loadedmetadata", () => this.tick());
    el.addEventListener("canplay", () => this.tick());
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

  /** What is playing, for checking by hand in the console (`togetherTunesPlayer.debug()`). */
  debug(): Record<string, unknown> {
    const d = this.#active;
    const t = this.#state?.timeline;
    const sw = this.#switch;
    return {
      context: this.#ctx?.state ?? "none",
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
    if (a !== undefined && this.#state?.timeline.state === "playing") this.#play(a);
    this.#updateOverlay();
    this.tick();
  }

  /** One pass of the sync loop. */
  tick(): void {
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
      sw = { from, to: deck, gate: new SwitchGate(performance.now()), fadeEndsAt: undefined };
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
    this.#clear(deck);
    deck.entryId = entry.entryId;
    deck.itemId = entry.item.id;
    deck.src = src;
    deck.started = false;
    deck.guard.reset();
    deck.el.src = src.url;
  }

  #clear(deck: Deck): void {
    if (deck.src === undefined) return;
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
   * it then).
   */
  #sync(deck: Deck, t: Timeline, target: number, playing: boolean, gain: number | undefined): void {
    const el = deck.el;
    const src = deck.src;
    if (src === undefined) return;
    if (gain !== undefined) this.#setGain(deck, gain);
    const key = anchorKey(t);
    if (key !== deck.anchorKey) {
      deck.anchorKey = key;
      deck.guard.reset();
    }
    if (el.readyState < HAVE_METADATA) return; // still loading (preload=auto)
    if (!deck.started) {
      deck.started = true;
      el.playbackRate = 1;
      el.currentTime = target / 1000;
      if (playing) this.#play(deck);
      return;
    }
    if (!playing) {
      if (!el.paused) el.pause();
      el.playbackRate = 1;
      // Keep the paused element where the timeline is, so resuming starts in sync.
      if (!el.seeking && Math.abs(el.currentTime * 1000 - target) > PAUSED_SEEK_MS) el.currentTime = target / 1000;
      return;
    }
    // Past the file's end (the server is about to advance): let it end by itself.
    if (Number.isFinite(el.duration) && target >= el.duration * 1000 - 50) return;
    if (el.paused) {
      // Not playing yet (or the browser refused): its position says nothing about the file, so
      // move it to the timeline without counting that as a hard seek, then start it.
      if (!el.seeking && Math.abs(el.currentTime * 1000 - target) > PAUSED_SEEK_MS) el.currentTime = target / 1000;
      this.#play(deck);
      return;
    }
    if (el.seeking || el.readyState < HAVE_FUTURE_DATA) return; // seeking or buffering: drift means nothing yet
    const action = syncAction(el.currentTime * 1000 - target);
    if (action.kind === "seek") {
      if (src.original && !deck.guard.hardSeek(performance.now())) {
        this.#giveUpOriginal(deck);
        return;
      }
      el.playbackRate = 1;
      el.currentTime = target / 1000;
    } else if (el.playbackRate !== action.rate) {
      el.playbackRate = action.rate;
    }
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
    }, (e: unknown) => {
      deck.playPending = false;
      this.#playFailed(e);
    });
  }

  #playFailed(e: unknown): void {
    if (e instanceof DOMException && e.name === "NotAllowedError") {
      this.#blocked = true;
      this.#updateOverlay();
    }
    // AbortError (the source changed meanwhile) needs nothing; the next tick tries again.
  }

  #onError(deck: Deck): void {
    if (deck.src === undefined || deck.el.getAttribute("src") === null) return; // cleared on purpose
    const itemId = deck.itemId;
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
    this.#overlay.hidden = !(wants && locked);
  }
}
