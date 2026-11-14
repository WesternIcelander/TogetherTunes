// Navigation hooks shared by every view, for the single-page app's shell (shell.ts):
// - `navigate()`: what views call instead of `location.assign`, so that a script's own navigation
//   (after saving a form, say) shows the next view in place (and keeps a playing room going);
// - `viewScope()` (taken at a view module's start; `pageSignal()` underneath): the view's signal,
//   aborted when the shell takes the view away, for listeners a view adds to `window` or
//   `document`, its timers and pollers, and the room view's socket and player; its path; and its
//   elements, looked up inside the view only;
// - `attachRoom()`: how the room view tells the shell about itself, so it can keep playing while
//   other views are shown (mini player);
// - the note shown after a reload stopped playback;
// - the desktop frame's hooks (frame.ts: the sidebar, the right panel, the player bar), so views can
//   ask it to reload the sidebar's lists or reach the room it follows.
// Without the shell (a module loaded on its own) navigate() is location.assign.
import type { ActorRef } from "./actor.js";
import type { IngestStatus } from "./jobtext.js";
import type { RoomMode, RoomState } from "./roomclient.js";

/** Handles a navigation in place; returns false to leave it to the browser. */
export type Navigator = (href: string, replace: boolean) => boolean;

let navigator: Navigator | undefined;
let controller = new AbortController();

/** Installed by the shell (undefined removes it). */
export function setNavigator(n: Navigator | undefined): void {
  navigator = n;
}

/** What the shell does when who is using the page changed (a login): re-identify without a reload. */
let identityHook: (() => Promise<void>) | undefined;

/** Installed by the shell (undefined removes it). */
export function setIdentityHook(hook: (() => Promise<void>) | undefined): void {
  identityHook = hook;
}

/**
 * Who is using the page changed (logged in, registered, set up, or switched account), without a
 * reload (docs/ui.md#single-page-app): the shell loads the new identity, redraws the frame and
 * re-identifies an open room's socket, so playback goes on. Never throws.
 */
export async function identityChanged(): Promise<void> {
  if (identityHook === undefined) return;
  try {
    await identityHook();
  } catch {
    // The next navigation picks the identity up anyway.
  }
}

/** Goes to `href` (a view of this site): in place where the shell can, otherwise a normal page load. */
export function navigate(href: string, replace = false): void {
  if (navigator !== undefined && navigator(href, replace)) return;
  if (replace) location.replace(href);
  else location.assign(href);
}

/** Shows this view again: in place (the view's module starts over), otherwise a reload. */
export function reloadPage(): void {
  if (navigator !== undefined && navigator(location.href, true)) return;
  location.reload();
}

/**
 * The signal of the view being shown (the last one the shell started): aborted when the shell takes
 * it away. Only right at a module's start is it that module's own: after an `await` it may already
 * be the next view's. View modules take `viewScope(import.meta.url)` at their start instead and use
 * its signal everywhere.
 */
export function pageSignal(): AbortSignal {
  return controller.signal;
}

/**
 * For the shell: starts the signal of the next view and returns its controller (the shell aborts
 * it when it takes the view away; the room view's may outlive the views shown after it).
 */
export function startPageSignal(): AbortController {
  controller = new AbortController();
  return controller;
}

/** Where and with what signal a view's module was started (the shell's background room has its own). */
export interface ViewStart {
  /** The view's path and query (the room view reads its room from it; `location` may be another view's). */
  path: string;
  search: string;
  /** Aborted when the shell takes the view away. */
  signal: AbortSignal;
  /** The view's element (its template's clone); undefined: the document. */
  root?: HTMLElement;
}

const starts = new Map<string, ViewStart>();

/** For the shell: what the module imported under `?mount=N` gets from `viewStart()`. */
export function registerViewStart(mount: number, start: ViewStart): void {
  const key = String(mount);
  starts.set(key, start);
  start.signal.addEventListener("abort", () => starts.delete(key));
}

/**
 * What the shell started this module for (pass `import.meta.url`), or undefined without the shell
 * (then `location` and `pageSignal()` are the module's own).
 */
export function viewStart(moduleUrl: string): ViewStart | undefined {
  const m = /[?&]mount=(\d+)/.exec(moduleUrl);
  return m !== null && m[1] !== undefined ? starts.get(m[1]) : undefined;
}

/**
 * What a view module keeps from its start: take it **synchronously at the module's start**
 * (`const VIEW = viewScope(import.meta.url)`), before any `await`, because after one `pageSignal()`
 * and `location` may already be the next view's. Every timer, poller, socket and listener the view
 * starts stops on `signal`, async work that finishes after it aborted does nothing, and
 * `element()` looks up only inside the view's own element, so a stale instance can't draw into the
 * view shown after it.
 */
export interface ViewScope {
  /** Aborted when the shell takes the view away. */
  signal: AbortSignal;
  /** The view's path and query (`location`'s at its start). */
  path: string;
  search: string;
  /** The view's element, or the document without the shell. */
  root: ParentNode;
  /** The element with this id inside the view (throws when missing). */
  element<T extends HTMLElement = HTMLElement>(id: string): T;
  /** Like `element`, or null when missing. */
  find<T extends HTMLElement = HTMLElement>(id: string): T | null;
  /** `setInterval` until the view is taken away. */
  every(f: () => void, ms: number): void;
  /** `setTimeout` that doesn't fire after the view was taken away. */
  after(f: () => void, ms: number): void;
}

/** This view module's scope (pass `import.meta.url`); see `ViewScope`. */
export function viewScope(moduleUrl: string): ViewScope {
  const start = viewStart(moduleUrl);
  const signal = start?.signal ?? pageSignal();
  const root: ParentNode = start?.root ?? document;
  const find = <T extends HTMLElement>(id: string): T | null => {
    const el = root === document ? document.getElementById(id) : root.querySelector("[id=\"" + id + "\"]");
    return el as T | null;
  };
  return {
    signal,
    path: start?.path ?? location.pathname,
    search: start !== undefined ? start.search : location.search,
    root,
    element<T extends HTMLElement>(id: string): T {
      const el = find<T>(id);
      if (el === null) throw new Error("Missing #" + id);
      return el;
    },
    find,
    every(f: () => void, ms: number): void {
      if (signal.aborted) return;
      const id = window.setInterval(f, ms);
      signal.addEventListener("abort", () => window.clearInterval(id), { once: true });
    },
    after(f: () => void, ms: number): void {
      if (signal.aborted) return;
      const cancel = (): void => window.clearTimeout(id);
      const id = window.setTimeout(() => {
        signal.removeEventListener("abort", cancel);
        if (!signal.aborted) f();
      }, ms);
      signal.addEventListener("abort", cancel, { once: true });
    },
  };
}

/** What the shell needs from the room view, to keep it playing while other views are shown. */
export interface ShellRoom {
  /** Whether the room plays here now (listen mode, not kicked): only then the shell keeps it. */
  active(): boolean;
  /** Whether `ref` (id or slug) is this room. */
  isRoom(ref: string): boolean;
  /** The room view's path (`/rooms/{slug}`). */
  path(): string;
  name(): string;
  /** The title of the room view. */
  title(): string;
  /** What plays: title (empty when nothing) and the timeline state. */
  nowPlaying(): { title: string; state: string };
  /** Why the room stopped playing here (kicked), or undefined. */
  stopped(): string | undefined;
  audioOnly(): boolean;
  /** Whether the room is audio-only: then there is no video / audio-only switch. */
  audioOnlyRoom?(): boolean;
  setAudioOnly(on: boolean): void;
  /** The room view is shown again (re-render the navigation etc.). */
  shown(): void;
  /** The room's id. */
  id(): string;
  /** The room state as the room view knows it (undefined until the first `state`). */
  state(): RoomState | undefined;
  /** The socket's actor (undefined while anonymous). */
  actor(): ActorRef | undefined;
  /** What ingest is doing for the entries' items that aren't ready (`ingest` messages). */
  ingest(): Record<string, IngestStatus>;
  /** Sends a room action (`rid`, answered by `ack` or `error`). */
  request(msg: Record<string, unknown>): Promise<Record<string, unknown>>;
  /** The server's clock (ms), for positions on the timeline. */
  serverNow(): number;
  /** Listen or requests-only (remote) mode here. */
  mode(): RoomMode;
  /** Whether the room forces requests-only mode (then the mode can't be changed). */
  forcedRequestsOnly(): boolean;
  /** Switches between listening here and requests-only mode (remembered for the room). */
  setRequestsOnly(on: boolean): void;
  /** The listener's volume (linear gain 0..1) and mute, applied to the room's player. */
  setVolume(gain: number, muted: boolean): void;
  /** The element the room's videos play in (the frame's video panel holds it). */
  playerContainer(): HTMLElement | undefined;
  /**
   * Who is using the page changed (a login): loads the new identity and reconnects the room's socket
   * as it, without stopping playback (the player keeps following the timeline meanwhile).
   */
  reidentify(): Promise<void>;
}

/** What the desktop frame (frame.ts) offers to views. */
export interface FrameHooks {
  /** Loads the sidebar's lists again (after a playlist was made, renamed, filled, ...). */
  refreshLibrary(): void;
  /** The room the frame follows (shown or playing in the background), if any. */
  room(): ShellRoom | undefined;
}

let frame: FrameHooks | undefined;

/** Installed by the shell's frame. */
export function setFrame(f: FrameHooks | undefined): void {
  frame = f;
}

/** Asks the frame to load the sidebar's lists again (nothing without one). */
export function refreshLibrary(): void {
  frame?.refreshLibrary();
}

/** The room the frame follows, if any (for "Add All" in a playlist, say). */
export function currentRoom(): ShellRoom | undefined {
  return frame?.room();
}

/** The shell's side, for the room view. */
export interface RoomHandle {
  /** Whether another view is shown (the room's is hidden). */
  readonly away: boolean;
  /** Refreshes the frame's room parts (call on every room state). */
  update(): void;
  /** The room stopped for good here (kicked, deleted: the socket's reason). */
  gone(reason: string): void;
}

let roomHost: ((room: ShellRoom) => RoomHandle) | undefined;

/** Installed by the shell: what `attachRoom()` calls. */
export function setRoomHost(host: ((room: ShellRoom) => RoomHandle) | undefined): void {
  roomHost = host;
}

/** Called by the room view once it knows its room. Without the shell, a handle that does nothing. */
export function attachRoom(room: ShellRoom): RoomHandle {
  if (roomHost !== undefined) return roomHost(room);
  return { away: false, update: () => undefined, gone: () => undefined };
}

/** Where the shell notes which room was playing while another page is shown (this tab only). */
const PLAYING_KEY = "togethertunes.playingRoom";

export interface PlayingRoom {
  /** The room page's path. */
  path: string;
  name: string;
}

/** Notes (or with null forgets) that `room` plays while another page is shown. */
export function rememberPlaying(room: PlayingRoom | null): void {
  try {
    if (room === null) sessionStorage.removeItem(PLAYING_KEY);
    else sessionStorage.setItem(PLAYING_KEY, JSON.stringify(room));
  } catch {
    // Blocked storage: no note after a reload.
  }
}

/** The room that was playing when this page was loaded from scratch (a reload), if any. */
export function playingBeforeLoad(): PlayingRoom | undefined {
  try {
    const raw = sessionStorage.getItem(PLAYING_KEY);
    if (raw === null) return undefined;
    const v = JSON.parse(raw) as Partial<PlayingRoom>;
    if (typeof v.path !== "string" || typeof v.name !== "string" || !v.path.startsWith("/rooms/")) return undefined;
    return { path: v.path, name: v.name };
  } catch {
    return undefined;
  }
}

/** Whether this document already looked for a room that stopped with the last load. */
let stoppedNoteChecked = false;

/**
 * After a full page load (a reload while the shell showed another view, say): a bar saying that
 * playback of the room stopped and needs a new "Click to enable sound" in the room. Looked for once per
 * document (the first view that renders the navigation); nothing when nothing was playing.
 */
export function showStoppedNote(): void {
  if (stoppedNoteChecked) return;
  stoppedNoteChecked = true;
  const room = playingBeforeLoad();
  rememberPlaying(null);
  if (room === undefined) return;
  const bar = document.createElement("div");
  bar.id = "stopped-note";
  bar.className = "mini-player";
  bar.setAttribute("role", "status");
  const text = document.createElement("span");
  text.className = "grow";
  const link = document.createElement("a");
  link.href = room.path;
  link.textContent = room.name;
  text.append("Playback of ", link, " stopped: it can't go on across a full page load (a reload, say)."
    + " Open the room and click \"Click to enable sound\" to listen again.");
  const close = document.createElement("button");
  close.type = "button";
  close.className = "link";
  close.textContent = "Dismiss";
  close.addEventListener("click", () => {
    bar.remove();
    document.body.classList.remove("has-mini-player");
  });
  bar.append(text, close);
  document.body.append(bar);
  document.body.classList.add("has-mini-player");
}
