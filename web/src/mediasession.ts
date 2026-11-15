// System media controls (docs/ui.md "System media controls"): what the page puts into the browser's
// Media Session (`navigator.mediaSession`), so the operating system's media controls show the
// current song and its Play / Pause / Next buttons act on this page.
//
// DOM-free: `sessionPlan` decides from the room state, the actor and this window's sound what the
// session should hold (metadata, playback state, which actions, the position), and `MediaSessionSync`
// applies a plan to a `navigator.mediaSession`-like object, touching it only when something changed.
// The frame (frame.ts) feeds it on its 250 ms tick and supplies the handlers:
//   - play / pause: the listener's own mute (unmute / mute); the room never pauses.
//   - nexttrack: a skip through the normal server path (`next`), only for people who may skip
//     (`canSkip`, never a vote-skip); the server still enforces it.
//   - previoustrack, seekbackward, seekforward, seekto: always null (no browser seeking on the element).
// Only the window that is actually heard fills the session; the others (another window of the same
// person has the sound, the browser blocks sound, requests-only mode) leave it empty.

/** The room state as far as the session needs it. */
export interface SessionStateLike {
  room: {
    name: string;
    alone?: boolean;
    controlMode?: string;
    you: { canSkip: boolean; restriction?: unknown };
  };
  timeline: { state: string; entryId?: string; anchorServerMs: number; anchorPositionMs: number };
  current?: {
    entryId: string;
    status?: string;
    item: {
      id: string;
      title?: string;
      artist?: string;
      album?: string;
      durationMs?: number;
      coverUrl?: string;
      show?: { name: string; season?: number; episode: number };
    };
  };
}

export interface SessionInput {
  /** The room the page follows (undefined: none). */
  state: SessionStateLike | undefined;
  /** This window plays the room's sound (listen mode, holds the sound, the browser lets it play). */
  heard: boolean;
  /** The listener's own mute (player bar). */
  muted: boolean;
  /** The server's clock now (ms), for the position. */
  serverNow: number;
  /** The page's origin (`location.origin`): artwork URLs are made absolute with it. */
  origin: string;
  /** The cover's size once the page has loaded it (keyed by its URL). */
  coverSize?: { url: string; width: number; height: number };
}

export interface ArtworkImage {
  src: string;
  sizes?: string;
  type?: string;
}

export interface SessionMetadata {
  title: string;
  artist: string;
  album: string;
  artwork: ArtworkImage[];
}

export interface SessionPosition {
  /** Seconds. */
  duration: number;
  /** Seconds, 0..duration. */
  position: number;
  playbackRate: number;
}

export type SessionAction = "play" | "pause" | "nexttrack";

/** Actions this module never offers: always set to null. */
export const NULL_ACTIONS: readonly string[] = ["previoustrack", "seekbackward", "seekforward", "seekto"];

export interface SessionPlan {
  metadata: SessionMetadata | null;
  playbackState: "none" | "playing" | "paused";
  /** The actions to offer (the others are set to null). */
  actions: SessionAction[];
  /** Undefined: no position (nothing plays, or the duration isn't known). */
  position: SessionPosition | undefined;
  /** Changes when the position must be sent again (track change, pause, mute, resync), not every tick. */
  positionKey: string;
}

const EMPTY: SessionPlan = { metadata: null, playbackState: "none", actions: [], position: undefined, positionKey: "" };

/** `url` as an absolute URL against `origin` (already absolute URLs stay as they are). */
export function absoluteUrl(url: string, origin: string): string {
  if (/^[a-z][a-z0-9+.-]*:/i.test(url)) return url;
  if (url.startsWith("//")) return origin.replace(/\/\/.*$/, "") + url;
  return origin.replace(/\/+$/, "") + (url.startsWith("/") ? "" : "/") + url;
}

/** The artwork list for an item: its cover (JPEG) when it has one, with its size once known; else none. */
export function artworkFor(coverUrl: string | undefined, origin: string,
    size?: { url: string; width: number; height: number }): ArtworkImage[] {
  if (coverUrl === undefined || coverUrl === "") return [];
  const image: ArtworkImage = { src: absoluteUrl(coverUrl, origin), type: "image/jpeg" };
  if (size !== undefined && size.url === coverUrl && size.width > 0 && size.height > 0) {
    image.sizes = Math.round(size.width) + "x" + Math.round(size.height);
  }
  return [image];
}

/** The metadata for the current entry: title, artist (a show's name for episodes), album (else the room's name; none alone). */
export function metadataFor(state: SessionStateLike, origin: string,
    coverSize?: { url: string; width: number; height: number }): SessionMetadata | null {
  const c = state.current;
  if (c === undefined) return null;
  const item = c.item;
  let artist = item.artist ?? "";
  if (artist === "" && item.show !== undefined) artist = item.show.name;
  const album = item.album !== undefined && item.album !== "" ? item.album
    : state.room.alone === true ? "" : state.room.name;
  return { title: item.title ?? "", artist, album, artwork: artworkFor(item.coverUrl, origin, coverSize) };
}

/** Whether the current entry is on the timeline (playing or paused), i.e. something plays here. */
export function somethingPlays(state: SessionStateLike | undefined): boolean {
  if (state === undefined) return false;
  const c = state.current;
  const t = state.timeline;
  return c !== undefined && t.entryId === c.entryId && t.state !== "idle";
}

/** Whether the system's Next button may be offered: people who may skip (never as a vote-skip). */
export function offersNext(state: SessionStateLike): boolean {
  // As panels.ts canSkipNow: `canSkip` (owner control, or a role that may skip), not while restricted.
  const you = state.room.you;
  return you.canSkip && you.restriction === undefined;
}

/** The timeline position (ms) at `now`, clamped to the duration when known. */
function positionMs(state: SessionStateLike, now: number): number {
  const t = state.timeline;
  let p = t.state === "playing" ? t.anchorPositionMs + (now - t.anchorServerMs) : t.anchorPositionMs;
  p = Math.max(0, p);
  const d = state.current?.item.durationMs;
  if (d !== undefined && d > 0) p = Math.min(p, d);
  return p;
}

/** What the session should hold now. */
export function sessionPlan(input: SessionInput): SessionPlan {
  const s = input.state;
  if (!input.heard || s === undefined || !somethingPlays(s)) return EMPTY;
  const metadata = metadataFor(s, input.origin, input.coverSize);
  if (metadata === null) return EMPTY;
  const playing = s.timeline.state === "playing" && !input.muted;
  const actions: SessionAction[] = ["play", "pause"];
  if (offersNext(s)) actions.push("nexttrack");
  const d = s.current?.item.durationMs;
  let position: SessionPosition | undefined;
  let positionKey = "";
  if (d !== undefined && Number.isFinite(d) && d > 0) {
    const t = s.timeline;
    position = { duration: d / 1000, position: positionMs(s, input.serverNow) / 1000, playbackRate: 1 };
    positionKey = [t.entryId, t.state, t.anchorServerMs, t.anchorPositionMs, d, playing ? "p" : "s"].join("|");
  }
  return { metadata, playbackState: playing ? "playing" : "paused", actions, position, positionKey };
}

/** A key that changes whenever the metadata does. */
export function metadataKey(m: SessionMetadata | null): string {
  return m === null ? "" : JSON.stringify(m);
}

/** The parts of `navigator.mediaSession` used here. */
export interface MediaSessionLike {
  metadata: unknown;
  playbackState: string;
  setActionHandler(action: string, handler: ((details: unknown) => void) | null): void;
  setPositionState?(state?: SessionPosition): void;
}

export interface SessionHandlers {
  play(): void;
  pause(): void;
  nexttrack(): void;
}

/**
 * Keeps a Media Session in line with the plans it is given: sets the metadata (made with
 * `makeMetadata`, the page's `MediaMetadata`), the playback state, the action handlers and the
 * position only when they change. Unsupported actions (their `setActionHandler` throws) are skipped.
 */
export class MediaSessionSync {
  readonly #session: MediaSessionLike | undefined;
  readonly #makeMetadata: (m: SessionMetadata) => unknown;
  readonly #handlers: SessionHandlers;
  #metaKey: string | undefined;
  #playbackState: string | undefined;
  #actions: string | undefined;
  #positionKey: string | undefined;

  constructor(session: MediaSessionLike | undefined, makeMetadata: (m: SessionMetadata) => unknown,
      handlers: SessionHandlers) {
    this.#session = session;
    this.#makeMetadata = makeMetadata;
    this.#handlers = handlers;
  }

  /** Applies `plan` (cheap when nothing changed). */
  apply(plan: SessionPlan): void {
    const ms = this.#session;
    if (ms === undefined) return;
    const metaKey = metadataKey(plan.metadata);
    if (metaKey !== this.#metaKey) {
      this.#metaKey = metaKey;
      try {
        ms.metadata = plan.metadata === null ? null : this.#makeMetadata(plan.metadata);
      } catch {
        // A browser that refuses the artwork: try without it.
        try {
          ms.metadata = plan.metadata === null ? null : this.#makeMetadata({ ...plan.metadata, artwork: [] });
        } catch {
          ms.metadata = null;
        }
      }
    }
    const actions = plan.actions.join(",");
    if (actions !== this.#actions) {
      this.#actions = actions;
      for (const a of ["play", "pause", "nexttrack"] as SessionAction[]) {
        this.#setHandler(a, plan.actions.indexOf(a) >= 0 ? () => this.#handlers[a]() : null);
      }
      for (const a of NULL_ACTIONS) this.#setHandler(a, null);
    }
    if (plan.playbackState !== this.#playbackState) {
      this.#playbackState = plan.playbackState;
      try {
        ms.playbackState = plan.playbackState;
      } catch {
        // not supported
      }
    }
    if (plan.positionKey !== this.#positionKey) {
      this.#positionKey = plan.positionKey;
      if (typeof ms.setPositionState === "function") {
        try {
          if (plan.position !== undefined) ms.setPositionState(plan.position);
          else ms.setPositionState();
        } catch {
          // Out of range or not supported: no position shown.
        }
      }
    }
  }

  #setHandler(action: string, handler: (() => void) | null): void {
    try {
      this.#session?.setActionHandler(action, handler);
    } catch {
      // This browser doesn't know the action.
    }
  }
}
