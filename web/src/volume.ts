// The listener's volume and mute, and the room's "ducked by preview" mute, as small DOM-free
// subscriptions (docs/ui.md "Item page"):
// - `listenerVolume`: the player bar's volume (the squared gain, `volumeGain`) and mute, published by
//   the frame (frame.ts) whenever they change (on mobile, with no slider, the remembered volume);
//   the item page's preview player follows it, live while the slider moves.
// - `previewDuck`: while a preview (the item page's player) plays, the room's audio is silent. It is
//   its own mute in the room player (listen.ts), like the blocked-sound mute: it never changes, and
//   is never changed by, the listener's own mute or volume, and the room keeps running in sync.

/** The listener's volume as a linear gain (0..1) and their mute. */
export interface ListenerVolume {
  gain: number;
  muted: boolean;
}

type Unsubscribe = () => void;

/** Calls `fn` until `unsubscribe` (or the signal's abort). */
function subscribeTo<T>(set: Set<(v: T) => void>, fn: (v: T) => void, signal?: AbortSignal): Unsubscribe {
  if (signal?.aborted === true) return () => undefined;
  set.add(fn);
  const unsubscribe = (): void => { set.delete(fn); };
  signal?.addEventListener("abort", unsubscribe, { once: true });
  return unsubscribe;
}

/** The listener's volume and mute, with subscribers (told at once, then on every change). */
export class VolumeChannel {
  #value: ListenerVolume = { gain: 1, muted: false };
  readonly #listeners = new Set<(v: ListenerVolume) => void>();

  get(): ListenerVolume {
    return { ...this.#value };
  }

  /** Publishes the volume (a linear gain, clamped to 0..1) and mute; subscribers hear only changes. */
  set(gain: number, muted: boolean): void {
    const g = Number.isFinite(gain) ? Math.max(0, Math.min(1, gain)) : 1;
    if (g === this.#value.gain && muted === this.#value.muted) return;
    this.#value = { gain: g, muted };
    for (const l of [...this.#listeners]) l(this.get());
  }

  /** Calls `fn` now and on every change, until unsubscribed or `signal` aborts. */
  subscribe(fn: (v: ListenerVolume) => void, signal?: AbortSignal): Unsubscribe {
    const unsubscribe = subscribeTo(this.#listeners, fn, signal);
    if (signal?.aborted !== true) fn(this.get());
    return unsubscribe;
  }
}

/**
 * Who ducks the room: each holder (a preview player, say) ducks or stops ducking; the room is
 * ducked while any holder does.
 */
export class PreviewDuck {
  readonly #holders = new Set<unknown>();
  readonly #listeners = new Set<(ducked: boolean) => void>();

  get ducked(): boolean {
    return this.#holders.size > 0;
  }

  /** `holder` ducks the room (on) or stops (off); subscribers hear only changes of `ducked`. */
  set(holder: unknown, on: boolean): void {
    const before = this.ducked;
    if (on) this.#holders.add(holder);
    else this.#holders.delete(holder);
    const after = this.ducked;
    if (after === before) return;
    for (const l of [...this.#listeners]) l(after);
  }

  /** Calls `fn` now and on every change, until unsubscribed or `signal` aborts. */
  subscribe(fn: (ducked: boolean) => void, signal?: AbortSignal): Unsubscribe {
    const unsubscribe = subscribeTo(this.#listeners, fn, signal);
    if (signal?.aborted !== true) fn(this.ducked);
    return unsubscribe;
  }
}

/** The page's listener volume (published by the frame). */
export const listenerVolume = new VolumeChannel();

/** The page's preview duck (the item page's player while it plays; the room player listens). */
export const previewDuck = new PreviewDuck();

/**
 * The room player's gain on top of everything else: silent while the listener muted it or a preview
 * ducks it, else the listener's volume. The two mutes are independent: each only adds silence.
 */
export function roomGain(volume: number, userMuted: boolean, ducked: boolean): number {
  return userMuted || ducked ? 0 : volume;
}

/** What the preview player plays at: the listener's volume and mute (the preview isn't ducked). */
export function previewLevel(v: ListenerVolume): { volume: number; muted: boolean } {
  return { volume: Math.max(0, Math.min(1, v.gain)), muted: v.muted };
}

/** The media events that start a preview ducking the room, and those that end it. */
export const DUCK_ON_EVENTS: readonly string[] = ["play", "playing"];
export const DUCK_OFF_EVENTS: readonly string[] = ["pause", "ended", "emptied", "error"];
