// Navigation hooks shared by every page, for the room page's shell (shell.ts):
// - `navigate()`: what pages call instead of `location.assign`, so that a script's own navigation
//   (after saving a form, say) also keeps playback going while a room plays;
// - `pageSignal()`: aborted when the shell takes the page away, for listeners a page adds to
//   `window` or `document` (its own elements simply go away with it);
// - the note shown after a reload stopped playback ("say so in the mini player").
// Without a playing room (a plain page load) navigate() is location.assign and the signal never fires.

/** Handles a navigation in place; returns false to leave it to the browser. */
export type Navigator = (href: string, replace: boolean) => boolean;

let navigator: Navigator | undefined;
let controller = new AbortController();

/** Installed by the shell while a room page is loaded (undefined removes it). */
export function setNavigator(n: Navigator | undefined): void {
  navigator = n;
}

/** Goes to `href` (a page on this site): in place while a room plays, otherwise a normal page load. */
export function navigate(href: string, replace = false): void {
  if (navigator !== undefined && navigator(href, replace)) return;
  if (replace) location.replace(href);
  else location.assign(href);
}

/** Loads this page again: in place while a room plays (the page's module starts over), otherwise a reload. */
export function reloadPage(): void {
  if (navigator !== undefined && navigator(location.href, true)) return;
  location.reload();
}

/** Aborted when the shell takes this page away (pass it as `{ signal }` to window/document listeners). */
export function pageSignal(): AbortSignal {
  return controller.signal;
}

/** For the shell: ends the current page's signal and starts a new one for the next page. */
export function nextPageSignal(): AbortSignal {
  controller.abort();
  controller = new AbortController();
  return controller.signal;
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

/**
 * After a full page load (a reload while the shell showed a page, say): a bar saying that
 * playback of the room stopped and needs a new "Click to listen" in the room. Shown once; does
 * nothing while the shell is running (the mini player is there) or when nothing was playing.
 */
export function showStoppedNote(): void {
  if (navigator !== undefined || document.getElementById("mini-player") !== null) return;
  const room = playingBeforeLoad();
  rememberPlaying(null);
  if (room === undefined) return;
  const bar = document.createElement("div");
  bar.id = "mini-player";
  bar.className = "mini-player";
  bar.setAttribute("role", "status");
  const text = document.createElement("span");
  text.className = "grow";
  const link = document.createElement("a");
  link.href = room.path;
  link.textContent = room.name;
  text.append("Playback of ", link, " stopped: it can't go on across a full page load (a reload, say)."
    + " Open the room and click \"Click to listen\" to listen again.");
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
