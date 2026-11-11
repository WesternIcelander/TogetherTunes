// The room page (/rooms/{idOrSlug}). The QR short link /r/{slug} redirects here with
// ?mode=remote (docs/rooms.md#web-and-app-playback), which opens the page in remote mode.
//
// The page joins the room over the WebSocket (roomclient.ts) and shows what is playing, the
// queue (with votes, pins, moves and removals as the control mode and the viewer's rights allow),
// suggestions (accept/reject for the owner and moderators), the request panel (search of the
// owner's and the viewer's libraries, or the whole server; upload, YouTube link, and a playlist for the owner and moderators), the
// history and who is here. The server's clock is estimated with ClockSync (clocksync.ts) on the
// same socket. In listen mode the page plays the room itself (ListenPlayer, listen.ts); in remote mode
// (remote.ts) it loads no media and shows the timeline delayed by the active output's
// estimatedDelayMs plus the user's nudge, as heard in VRChat. The mode is a toggle remembered per
// room; `?mode=` wins. The owner and moderators get a link to the management view (roommanage.ts).
// The outputs panel (outputspanel.ts) shows the RTMP viewer URL to everyone and the stream outputs
// (URLs with copy buttons, push status, on/off, rotate) to the owner and moderators.
// In listen mode, "Send diagnostics" posts what the player did (diagnostics.ts) to the server log,
// for devices without a console; `?sync=rate|seek` overrides the sync mode for testing, and
// The "Web Audio" setting (Auto / On / Off, per browser) decides whether the media elements play
// through Web Audio; `?webaudio=0/1` overrides it for testing (audiohealth.ts `webAudioPlan`). Auto
// uses Web Audio only where the page can't set an element's volume (iOS; `volumeSettable`).
// While the room plays in listen mode, the shell (shell.ts) shows the site's other pages in place,
// so playback goes on; a mini player there links back to this view.
import { classifyAndAdd, withClassification } from "./classifyprompt.js";
import { api, ApiError } from "./api.js";
import { actorLabel, type ActorRef } from "./actor.js";
import {
  parseWebAudioMemory, parseWebAudioSetting, volumeSettable, webAudioOverride, webAudioPlan, type WebAudioMemory,
  type WebAudioSetting,
} from "./audiohealth.js";
import { ClockSync } from "./clocksync.js";
import { captureErrors, recordEvent, snapshot, stripQuery, type ErrorTarget } from "./diagnostics.js";
import { ListenPlayer, type SoundInfo } from "./listen.js";
import { syncModeFor, type EngineHints } from "./listensync.js";
import {
  chooseMode, DelayedTimeline, effectiveDelay, formatSeconds, modeKey, nudgeKey, outputDelay, parseNudge, stepNudge,
  type RoomMode,
} from "./remote.js";
import { button, formatBytes, formatDuration, formatTime, h } from "./dom.js";
import { itemPath, myPlaylists, statusBadge, type ItemSummary, type Page } from "./media.js";
import { canRetryEntry, ingestText, lookupText } from "./jobtext.js";
import { banGuestConfirmed } from "./guestban.js";
import {
  libraryAction, markInLibrary, mergeFirstPage, queueAction, viaText, type HistoryPage, type HistoryRow,
  type HistoryViewer,
} from "./history.js";
import {
  positionAt, RoomConnection, type CurrentEntry, type HistoryEntry, type NewRequest, type RoomItem, type RoomState,
  type Timeline, type WaitingEntry,
} from "./roomclient.js";
import {
  checkPasswordChange, element, here, loadMe, LOGIN_PAGE, message, onReady, renderNav, withNext, type Me,
} from "./session.js";
import { Upload, type UploadProgress } from "./uploads.js";
import { rememberPlaying } from "./nav.js";
import { OutputsPanel } from "./outputspanel.js";
import { Shell } from "./shell.js";
import { rememberedWholeServer, rememberWholeServer } from "./searchscope.js";

// Page errors from now on go into the diagnostics ring (sent with "Send diagnostics").
captureErrors(window as unknown as ErrorTarget);

/**
 * The room page's own query (`?mode=`, `?sync=`, `?webaudio=`), kept from the load: while the
 * shell shows another page, `location` is that page's.
 */
const PAGE_SEARCH = location.search;

interface RoomResponse {
  id: string;
  slug: string;
  name: string;
}

/** The room from `/rooms/{idOrSlug}` (or `?room=` when the page is opened as a plain file). */
function roomFromLocation(): string {
  const m = /^\/rooms\/([^/]+)\/?$/.exec(location.pathname);
  if (m !== null && m[1] !== undefined) {
    try {
      return decodeURIComponent(m[1]);
    } catch {
      return "";
    }
  }
  return new URLSearchParams(PAGE_SEARCH).get("room") ?? "";
}

/** Where the video / audio-only choice is remembered (this browser only, all rooms). */
const AUDIO_ONLY_KEY = "togethertunes.audioOnly";

/** The remembered video / audio-only choice (video if nothing is stored or storage is blocked). */
export function loadAudioOnly(): boolean {
  try {
    return localStorage.getItem(AUDIO_ONLY_KEY) === "1";
  } catch {
    return false;
  }
}

/** Where the "Web Audio" setting (auto / on / off) is remembered (this browser only, all rooms). */
const WEB_AUDIO_KEY = "togethertunes.webAudio";
/** What Auto learnt on this browser (WebAudioMemory as JSON: the rate that works, or direct only). */
const WEB_AUDIO_MEMORY_KEY = "togethertunes.webAudioAuto";

function loadWebAudioSetting(): WebAudioSetting {
  return parseWebAudioSetting(storageGet(WEB_AUDIO_KEY));
}

function loadWebAudioMemory(): WebAudioMemory | undefined {
  return parseWebAudioMemory(storageGet(WEB_AUDIO_MEMORY_KEY), Date.now());
}

/** The `?webaudio=0/1` override (testing), undefined without one. */
function urlWebAudio(): boolean | undefined {
  return webAudioOverride(new URLSearchParams(PAGE_SEARCH).get("webaudio"));
}

let volumeCheck: boolean | undefined;

/** Whether this browser lets the page set a media element's volume (checked once, on a spare element). */
function canSetVolume(): boolean {
  if (volumeCheck === undefined) {
    try {
      volumeCheck = volumeSettable(document.createElement("audio"));
    } catch {
      volumeCheck = false;
    }
  }
  return volumeCheck;
}

/**
 * The note next to the setting: how the sound plays now. Nothing for the usual path (Auto: direct
 * where the volume can be set, Web Audio elsewhere).
 */
export function soundNote(sound: SoundInfo, volumeCanBeSet: boolean): string {
  if (!sound.webAudio) {
    if (!volumeCanBeSet) return "Sound: direct (no volume control)";
    return sound.off === "volume" ? "" : "Sound: direct";
  }
  if (sound.fixedRate !== undefined) return "Sound: Web Audio at " + Math.round(sound.fixedRate / 100) / 10 + " kHz";
  return "";
}

function saveAudioOnly(on: boolean): void {
  try {
    localStorage.setItem(AUDIO_ONLY_KEY, on ? "1" : "0");
  } catch {
    // Private mode or blocked storage: the choice lasts for this page only.
  }
}

/** The mode requested by the URL (`?mode=remote` from the short link), if any. */
export function modeFromLocation(): RoomMode | null {
  const mode = new URLSearchParams(PAGE_SEARCH).get("mode");
  return mode === "remote" || mode === "listen" ? mode : null;
}

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    // Private mode or blocked storage: the choice lasts for this page only.
  }
}

/** The server clock as this page estimates it (the client's wall clock until the first round). */
let clock: ClockSync | undefined;

function serverNow(): number {
  return clock !== undefined ? clock.serverNow() : Date.now();
}

let me: Me;
let conn: RoomConnection;
/** The page's mode: listen (plays the room) or remote (no media, delayed view). */
let mode: RoomMode = "listen";
/** Plays the room in listen mode; created on first entering listen mode, stopped in remote mode. */
let player: ListenPlayer | undefined;
/** The timeline as it was a while ago, for remote mode's delayed view. */
const delayed = new DelayedTimeline<CurrentEntry | undefined>();
/** The user's nudge of the remote delay for this room (ms, whole 0.5 s steps). */
let nudge = 0;
let roomId = "";
/** The last state shown, for the progress timer. */
let shown: RoomState | undefined;
/** The room's slug (for links back to it) and the reference it was opened with. */
let roomSlug = "";
let roomRef = "";
let roomName = "";
/** Why the room stopped playing here (kicked), shown in the mini player. */
let kickedText: string | undefined;
/** Shows the site's other pages in place while the room plays (shell.ts). */
let shell: Shell | undefined;
/** The outputs panel (RTMP viewer URL; stream URLs and controls for the owner and moderators). */
let outputsPanel: OutputsPanel | undefined;

// ------------------------------------------------------------------ small helpers

function showError(e: unknown): void {
  const el = element("room-error");
  el.textContent = e === "" ? "" : message(e);
}

function notice(text: string, node?: Node): void {
  const list = element("room-notices");
  const li = h("li", { class: "notice" }, node ?? text);
  const close = h("button", { type: "button", class: "link", text: "Dismiss" });
  close.addEventListener("click", () => li.remove());
  li.append(" ", close);
  list.prepend(li);
  while (list.children.length > 5) list.lastElementChild?.remove();
}

/** Sends a room action; errors are shown on the page. */
function act(msg: Record<string, unknown>): Promise<void> {
  showError("");
  return conn.request(msg).then(() => undefined);
}

function actionButton(text: string, msg: () => Record<string, unknown>, className?: string): HTMLButtonElement {
  return button(text, () => act(msg()), showError, className);
}

function itemTitle(item: RoomItem): HTMLElement {
  const title = item.title ?? "(unknown item)";
  return h("div", { class: "item-title" },
    h("a", { href: itemPath(item.id), text: title }),
    item.artist !== undefined && item.artist !== "" ? h("div", { class: "muted small", text: item.artist }) : null);
}

function cover(item: RoomItem, className: string): HTMLElement {
  if (item.coverUrl === undefined) return h("div", { class: className + " cover-none", "aria-hidden": "true" });
  return h("img", { class: className, src: item.coverUrl, alt: "", loading: "lazy" });
}

/**
 * An entry's status badge. While its item is being fetched or encoded (or failed) the badge says
 * what ingest is doing, from the room's `ingest` messages ("Downloading 45 %", "Failed: …"), and is
 * marked with the item id so later `ingest` messages update it in place ({@link showIngest}).
 */
function entryStatusBadge(status: string, itemId: string): HTMLElement | null {
  if (status === "ready") return null;
  if (status === "processing" || status === "failed") {
    const badge = h("span", { class: "badge badge-status badge-" + (status === "failed" ? "failed" : "processing") });
    badge.dataset["ingestItem"] = itemId;
    badge.dataset["entryStatus"] = status;
    badge.textContent = ingestBadgeText(status, itemId);
    return badge;
  }
  if (status === "purged") return statusBadge(status);
  return h("span", { class: "badge badge-status", text: status });
}

function ingestBadgeText(status: string, itemId: string): string {
  const known = conn.ingest[itemId];
  if (status === "failed") {
    return ingestText({ state: "failed", error: known?.state === "failed" ? known.error : undefined });
  }
  // Just retried: the entry waits again before the next `ingest` message says so.
  return ingestText(known?.state === "failed" ? undefined : known);
}

/** An `ingest` message arrived: the badges of entries that wait for ingest show the news. */
function showIngest(): void {
  document.querySelectorAll<HTMLElement>("[data-ingest-item]").forEach((badge) => {
    const itemId = badge.dataset["ingestItem"] ?? "";
    const text = ingestBadgeText(badge.dataset["entryStatus"] ?? "processing", itemId);
    if (badge.textContent !== text) badge.textContent = text;
  });
}

function addedByLine(addedBy: ActorRef | undefined, prefix = "Added by "): HTMLElement | null {
  if (addedBy === undefined) return null;
  return h("span", { class: "muted small" }, prefix, actorLabel(addedBy));
}

function isMine(entry: CurrentEntry): boolean {
  const a = conn.actor;
  return a !== undefined && entry.addedBy !== undefined && entry.addedBy.kind === a.kind && entry.addedBy.id === a.id;
}

// ------------------------------------------------------------------ header and presence

function renderHeader(s: RoomState): void {
  roomSlug = s.room.slug;
  roomName = s.room.name;
  // While the shell shows another page, the title is that page's.
  if (shell?.away !== true) document.title = s.room.name + " · TogetherTunes";
  element("room-name").textContent = s.room.name;
  const mode = { owner: "Owner picks", vote: "Vote", open: "Open" }[s.room.controlMode] ?? s.room.controlMode;
  const role = s.room.you.role;
  element("room-meta").textContent = "Control: " + mode + (role !== undefined ? " · You are " + role : "");
  // The management view (settings, members, guest bans) for the owner and moderators.
  const manage = element<HTMLAnchorElement>("room-manage-link");
  manage.hidden = !s.room.you.canModerate;
  manage.href = "/rooms/" + encodeURIComponent(s.room.slug) + "/manage";
  const p = s.presence;
  const parts = [p.listening + " listening", p.remote + " remote"];
  let text = parts.join(" · ");
  if (p.anonymous > 0) text += " · +" + p.anonymous + " anonymous";
  element("room-presence").textContent = text;
}

function renderIdentity(): void {
  const el = element("room-identity");
  const a = conn.actor;
  if (a === undefined) {
    // The room state's `you` shows an anonymous visitor no rights (they need an identity first),
    // so offer to become a guest when the room lets guests do anything; the state that follows
    // the identity then shows what the guest may really do (server AND room settings).
    const guests = shown?.room.settings["guests"] as Record<string, unknown> | undefined;
    const roomAllows = guests === undefined || guests["canVote"] === true || guests["canRequestLibrary"] === true
      || guests["canUpload"] === true || guests["canYoutube"] === true;
    const loginLink = h("a", { href: withNext(LOGIN_PAGE, here()), text: "Log in" });
    if (me.kind === "anonymous" && me.guestsEnabled && roomAllows) {
      el.replaceChildren(h("span", { class: "muted small", text: "You are listening anonymously. " }),
        button("Join as guest", () => conn.ensureIdentity(), (e) => { if (e !== CANCELLED) showError(e); }),
        h("span", { class: "muted small", text: " to vote or request, or " }), loginLink, ".");
    } else {
      el.replaceChildren(h("span", { class: "muted small", text: "You are listening anonymously. " }), loginLink,
        h("span", { class: "muted small", text: " to vote or request." }));
    }
    return;
  }
  const parts: (Node | string)[] = [h("span", { class: "muted small", text: "You: " }), actorLabel(a)];
  if (a.kind === "guest" && (me.kind === "guest" || me.kind === "anonymous") && me.customNames) {
    parts.push(" ", button("Change name", async () => {
      const name = await askGuestName(a.name);
      if (name === a.name || name === "") return;
      const updated = await api<ActorRef>("PATCH", "/api/me/guest", { name });
      conn.actor = { kind: "guest", id: updated.id, name: updated.name, isGuest: true };
      renderIdentity();
    }, (e) => { if (e !== CANCELLED) showError(e); }, "link"));
  }
  el.replaceChildren(...parts);
}

const CANCELLED = new Error("Cancelled");

/** Asks for a guest name in the page (no `<dialog>`: Safari 15.0 lacks it). Rejects with CANCELLED. */
function askGuestName(suggested: string): Promise<string> {
  const box = element("room-guest-name");
  return new Promise((resolve, reject) => {
    const input = h("input", { name: "name", type: "text", required: true, minlength: "2", maxlength: "32",
      value: suggested, "aria-label": "Your name", class: "grow" });
    const form = h("form", { class: "row" }, input, h("button", { type: "submit", text: "Continue" }));
    const cancel = h("button", { type: "button", text: "Cancel" });
    form.append(cancel);
    form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      box.hidden = true;
      box.replaceChildren();
      resolve(input.value.trim());
    });
    cancel.addEventListener("click", () => {
      box.hidden = true;
      box.replaceChildren();
      reject(CANCELLED);
    });
    box.replaceChildren(h("p", { text: "Choose the name others see next to your votes and requests." }), form);
    box.hidden = false;
    input.focus();
    input.select();
  });
}

// ------------------------------------------------------------------ now playing

let progressBar: HTMLProgressElement | undefined;
let seekBar: HTMLInputElement | undefined;
let seeking = false;
let positionText: HTMLElement | undefined;

/** What the now-playing panel shows: the timeline now, or in remote mode as it was `delay` ago. */
interface NowView {
  timeline: Timeline;
  current: CurrentEntry | undefined;
}

/** The output delay and the delay the remote view uses (output delay + nudge). */
function remoteDelays(s: RoomState | undefined): { kind: string; outputMs: number; totalMs: number } {
  const out = outputDelay(s?.outputs, s?.room.settings);
  return { kind: out.kind, outputMs: out.delayMs, totalMs: effectiveDelay(out.delayMs, nudge) };
}

function nowView(s: RoomState): NowView {
  if (mode === "remote") {
    const v = delayed.atTime(serverNow() - remoteDelays(s).totalMs);
    if (v !== undefined) return { timeline: v.timeline as Timeline, current: v.value };
  }
  return { timeline: s.timeline, current: s.current };
}

/** The view the panel was last drawn with, so the timer can tell when the delayed view moved on. */
let drawn: NowView | undefined;

function renderNowPlaying(s: RoomState): void {
  const view = nowView(s);
  drawn = view;
  const el = element("room-now");
  progressBar = undefined;
  seekBar = undefined;
  positionText = undefined;
  const you = s.room.you;
  const controls = h("div", { class: "row now-controls" });
  if (you.canSkip) {
    controls.append(actionButton("Previous", () => ({ type: "previous" })));
    if (s.timeline.state === "playing") controls.append(actionButton("Pause", () => ({ type: "pause" })));
    else controls.append(actionButton("Play", () => ({ type: "play" })));
    controls.append(actionButton("Skip", () => ({ type: "next" })));
  }
  if (s.room.controlMode === "vote" && s.skipVotes !== undefined && s.current !== undefined && you.canVote) {
    const sv = s.skipVotes;
    controls.append(actionButton("Vote to skip (" + sv.votes + "/" + sv.needed + ")", () => ({ type: "voteSkip" })));
  } else if (s.skipVotes !== undefined) {
    controls.append(h("span", { class: "muted small", text: "Skip votes: " + s.skipVotes.votes + "/" + s.skipVotes.needed }));
  }

  const c = view.current;
  if (c === undefined) {
    el.replaceChildren(h("h2", { text: "Now playing" }),
      h("p", { class: "muted", text: s.queue.length > 0 ? "Nothing is playing." : "Nothing is playing. The queue is empty." }),
      controls);
    controls.hidden = controls.childElementCount === 0;
    return;
  }
  const item = c.item;
  const st = view.timeline.state;
  const stateText = st === "playing" ? "Playing" : st === "paused" ? "Paused" : st;
  positionText = h("span", { class: "small num" });
  const duration = item.durationMs ?? 0;
  let bar: HTMLElement;
  if (you.canSkip && duration > 0) {
    const range = h("input", { type: "range", min: "0", max: String(duration), step: "1000", class: "grow",
      "aria-label": "Position" });
    range.addEventListener("input", () => { seeking = true; });
    range.addEventListener("change", () => {
      seeking = false;
      act({ type: "seek", positionMs: Math.round(Number(range.value)) }).catch(showError);
    });
    seekBar = range;
    bar = range;
  } else {
    progressBar = h("progress", { max: String(Math.max(duration, 1)), value: "0", class: "grow" });
    bar = progressBar;
  }
  el.replaceChildren(h("h2", { text: "Now playing" }),
    h("div", { class: "now" },
      cover(item, "cover now-cover"),
      h("div", { class: "now-info" },
        itemTitle(item),
        h("div", { class: "row" }, h("span", { class: "badge", text: stateText }), entryStatusBadge(c.status, c.item.id),
          addedByLine(c.addedBy)),
        h("div", { class: "row" }, bar, positionText),
        controls)));
  tick();
}

/** Moves the progress bar along (four times a second). */
function tick(): void {
  const s = shown;
  if (s === undefined) return;
  let view = drawn;
  if (mode === "remote") {
    // The delayed view reached another change: draw the panel again.
    const v = nowView(s);
    if (view === undefined || v.timeline !== view.timeline || v.current !== view.current) {
      renderNowPlaying(s);
      return; // renderNowPlaying ticks
    }
    view = v;
  }
  if (view === undefined || view.current === undefined || positionText === undefined) return;
  const duration = view.current.item.durationMs ?? 0;
  const delay = mode === "remote" ? remoteDelays(s).totalMs : 0;
  let pos = positionAt(view.timeline, serverNow() - delay);
  if (duration > 0) pos = Math.min(pos, duration);
  positionText.textContent = formatDuration(pos) + (duration > 0 ? " / " + formatDuration(duration) : "");
  if (progressBar !== undefined) progressBar.value = pos;
  if (seekBar !== undefined && !seeking) seekBar.value = String(pos);
}

// ------------------------------------------------------------------ queue and suggestions

function voteControls(s: RoomState, e: WaitingEntry): HTMLElement {
  const box = h("span", { class: "votes row" });
  const canVote = s.room.you.canVote;
  const up = h("button", { type: "button", class: "icon" + (e.yourVote === 1 ? " voted" : ""), text: "▲",
    title: e.yourVote === 1 ? "Take back your vote" : "Vote up", "aria-pressed": e.yourVote === 1 ? "true" : "false" });
  up.disabled = !canVote;
  up.addEventListener("click", () => {
    up.disabled = true;
    act({ type: "vote", entryId: e.entryId, value: e.yourVote === 1 ? 0 : 1 }).catch(showError)
      .finally(() => { up.disabled = !canVote; });
  });
  box.append(up, h("span", { class: "num vote-count", text: String(e.votes) }));
  if (s.room.settings.allowDownvotes === true) {
    const down = h("button", { type: "button", class: "icon" + (e.yourVote === -1 ? " voted" : ""), text: "▼",
      title: e.yourVote === -1 ? "Take back your vote" : "Vote down", "aria-pressed": e.yourVote === -1 ? "true" : "false" });
    down.disabled = !canVote;
    down.addEventListener("click", () => {
      down.disabled = true;
      act({ type: "vote", entryId: e.entryId, value: e.yourVote === -1 ? 0 : -1 }).catch(showError)
        .finally(() => { down.disabled = !canVote; });
    });
    box.append(down);
  }
  return box;
}

function canRemove(s: RoomState, e: WaitingEntry): boolean {
  const you = s.room.you;
  return you.canModerate || isMine(e) || (s.room.controlMode === "open" && you.canRequest && you.identified);
}

/** Whether you may fetch this failed entry again (`queue.retry`); see {@link canRetryEntry}. */
function canRetry(s: RoomState, e: WaitingEntry): boolean {
  const you = s.room.you;
  return canRetryEntry(e.status, e.item.source, you.canModerate, isMine(e), you.canYoutube);
}

function retryButton(e: WaitingEntry): HTMLButtonElement {
  return actionButton("Retry", () => ({ type: "queue.retry", entryId: e.entryId }));
}

function canMove(s: RoomState): boolean {
  const you = s.room.you;
  if (s.room.controlMode === "owner") return you.canModerate;
  if (s.room.controlMode === "open") return you.canModerate || (you.canRequest && you.identified);
  return false;
}

function queueRow(s: RoomState, e: WaitingEntry, i: number, list: WaitingEntry[]): HTMLLIElement {
  const you = s.room.you;
  const actions = h("span", { class: "row entry-actions" });
  if (canRetry(s, e)) actions.append(retryButton(e));
  if (s.room.controlMode === "vote" && you.canModerate) {
    actions.append(actionButton(e.pinned ? "Unpin" : "Pin",
      () => ({ type: "queue.pin", entryId: e.entryId, pinned: !e.pinned })));
  }
  if (canMove(s)) {
    const prev = list[i - 1];
    const after = list[i + 2];
    if (prev !== undefined) {
      actions.append(actionButton("↑", () => ({ type: "queue.move", entryId: e.entryId, beforeEntryId: prev.entryId }), "icon"));
    }
    if (list[i + 1] !== undefined) {
      actions.append(actionButton("↓", () => ({ type: "queue.move", entryId: e.entryId,
        beforeEntryId: after === undefined ? null : after.entryId }), "icon"));
    }
  }
  if (canRemove(s, e)) {
    actions.append(actionButton("Remove", () => ({ type: "queue.remove", entryId: e.entryId })));
  }
  return h("li", { class: "entry", "data-entry": e.entryId },
    s.room.controlMode === "vote" ? voteControls(s, e) : h("span", { class: "num muted entry-pos", text: String(i + 1) }),
    cover(e.item, "cover entry-cover"),
    h("div", { class: "grow" },
      itemTitle(e.item),
      h("div", { class: "row" },
        e.pinned ? h("span", { class: "badge badge-current", text: "Pinned" }) : null,
        entryStatusBadge(e.status, e.item.id),
        h("span", { class: "muted small num", text: formatDuration(e.item.durationMs) }),
        addedByLine(e.addedBy))),
    actions);
}

function renderQueue(s: RoomState): void {
  const el = element("room-queue");
  const heading = h("h2", { text: "Up next" + (s.queue.length > 0 ? " (" + s.queue.length + ")" : "") });
  if (s.queue.length === 0) {
    el.replaceChildren(heading, h("p", { class: "muted", text: "The queue is empty." }));
    return;
  }
  const list = h("ol", { class: "entries" });
  s.queue.forEach((e, i) => list.append(queueRow(s, e, i, s.queue)));
  const hint = s.room.controlMode === "vote"
    ? h("p", { class: "muted small", text: "The most votes play first; pinned entries before them." }) : "";
  el.replaceChildren(heading, hint, list);
}

function renderSuggestions(s: RoomState): void {
  const el = element("room-suggestions");
  if (s.suggestions.length === 0) {
    el.hidden = s.room.controlMode !== "owner";
    el.replaceChildren(h("h2", { text: "Suggestions" }),
      h("p", { class: "muted", text: s.room.you.canModerate
        ? "No suggestions. Requests from listeners show up here for you to accept or reject."
        : "In this room the owner picks the music; your requests are sent as suggestions." }));
    return;
  }
  el.hidden = false;
  const list = h("ul", { class: "entries" });
  for (const e of s.suggestions) {
    const actions = h("span", { class: "row entry-actions" });
    if (canRetry(s, e)) actions.append(retryButton(e));
    if (s.room.you.canModerate) {
      actions.append(actionButton("Accept", () => ({ type: "suggest.accept", entryId: e.entryId })),
        actionButton("Reject", () => ({ type: "suggest.reject", entryId: e.entryId })));
    } else if (canRemove(s, e)) {
      actions.append(actionButton("Withdraw", () => ({ type: "queue.remove", entryId: e.entryId })));
    }
    list.append(h("li", { class: "entry", "data-entry": e.entryId },
      cover(e.item, "cover entry-cover"),
      h("div", { class: "grow" },
        itemTitle(e.item),
        h("div", { class: "row" }, entryStatusBadge(e.status, e.item.id),
          h("span", { class: "muted small num", text: formatDuration(e.item.durationMs) }),
          addedByLine(e.addedBy, "Suggested by "))),
      actions));
  }
  el.replaceChildren(h("h2", { text: "Suggestions (" + s.suggestions.length + ")" }), list);
}

// ------------------------------------------------------------------ history

// The room's history (docs/rooms.md#history) comes from GET /api/rooms/{room}/history, 50 at a
// time ("Show more"); the first page is loaded again whenever the room state's own (short) history
// changes, so it follows what plays. What each row offers is decided in history.ts.

/** The rows shown, newest first. */
let historyRows: HistoryRow[] = [];
let historyCursor: string | undefined;
let historyWindow = 0;
/** The newest history entry of the room state when the first page was last loaded. */
let historyTop: string | undefined;
let historyLoading = false;
let historyAgain = false;
/** What the list was drawn for, so it is only drawn again when that changes. */
let historyKey = "";
const historyStatus = h("p", { class: "muted small", role: "status" });

function historyViewer(s: RoomState): HistoryViewer {
  return { user: me.kind === "user", moderates: s.room.you.canModerate,
    canRequestLibrary: s.room.you.canRequestLibrary === true };
}

function renderHistory(s: RoomState): void {
  const top = s.history.length > 0 ? s.history[0]!.entryId : "";
  if (top !== historyTop) {
    historyTop = top;
    void loadHistory(true);
  }
  drawHistory(s);
}

async function loadHistory(first: boolean): Promise<void> {
  if (historyLoading) {
    if (first) historyAgain = true;
    return;
  }
  historyLoading = true;
  try {
    const params = new URLSearchParams({ limit: "50" });
    if (!first && historyCursor !== undefined) params.set("cursor", historyCursor);
    const page = await api<HistoryPage>("GET", "/api/rooms/" + encodeURIComponent(roomId) + "/history?" + params);
    historyWindow = page.noRepeatWindow;
    if (first) {
      const more = historyRows.length > page.items.length;
      historyRows = page.items.length === 0 ? [] : mergeFirstPage(historyRows, page.items);
      if (!more) historyCursor = page.nextCursor;
    } else {
      historyRows = historyRows.concat(page.items.filter((r) => !historyRows.some((o) => o.entryId === r.entryId)));
      historyCursor = page.nextCursor;
    }
    historyKey = "";
    if (shown !== undefined) drawHistory(shown);
  } catch (e) {
    historyStatus.textContent = "History: " + message(e);
  } finally {
    historyLoading = false;
    if (historyAgain) {
      historyAgain = false;
      void loadHistory(true);
    }
  }
}

function drawHistory(s: RoomState): void {
  const viewer = historyViewer(s);
  const key = [viewer.user, viewer.moderates, viewer.canRequestLibrary, s.room.controlMode].join(",");
  if (key === historyKey) return;
  historyKey = key;
  const el = element("room-history");
  if (historyRows.length === 0) {
    el.replaceChildren(h("h2", { text: "History" }), h("p", { class: "muted", text: "Nothing has played yet." }),
      historyStatus);
    return;
  }
  // Covers come with the room state's recent history (the REST rows are metadata only).
  const covers = new Map<string, RoomItem>();
  for (const e of s.history as HistoryEntry[]) covers.set(e.item.id, e.item);
  const list = h("ul", { class: "entries history" });
  for (const r of historyRows) list.append(historyRow(r, viewer, s, covers.get(r.item.id)));
  const more = h("button", { type: "button", text: "Show more" });
  more.hidden = historyCursor === undefined;
  more.addEventListener("click", () => void loadHistory(false));
  el.replaceChildren(h("h2", { text: "History" }), historyStatus, list, more);
}

function historyRow(r: HistoryRow, viewer: HistoryViewer, s: RoomState, withCover: RoomItem | undefined): HTMLElement {
  const actions = h("div", { class: "row entry-actions" });
  const lib = libraryAction(r, viewer);
  if (lib === "in-library") {
    actions.append(h("span", { class: "badge", text: "In your library" }));
  } else if (lib === "add") {
    actions.append(button("Add to Library", async () => {
      if (await withClassification((c) => api("PUT", "/api/library/" + encodeURIComponent(r.item.id), c),
        r.item.title ?? undefined) === null) return;
      markInLibrary(historyRows, r.item.id);
      historyStatus.textContent = (r.item.title ?? "") + ": added to your library.";
      historyKey = "";
      drawHistory(shown ?? s);
    }, (e) => { historyStatus.textContent = message(e); }, "small"));
  }
  const q = queueAction(r, viewer, historyWindow);
  if (q.shown) {
    const add = button(requestVerb(s), async () => {
      const ack = await conn.request({ type: "queue.add", historyEntryId: r.entryId });
      historyStatus.textContent = (r.item.title ?? "") + ": " + addedMessage(ack);
    }, (e) => { if (e !== CANCELLED) historyStatus.textContent = message(e); }, "small");
    if (q.disabled) {
      add.disabled = true;
      add.title = q.reason ?? "";
    }
    actions.append(add);
    if (q.disabled && q.reason !== undefined) actions.append(h("span", { class: "muted small", text: q.reason }));
  }
  if (viewer.moderates && r.addedBy !== undefined && r.addedBy.isGuest) {
    const guest = r.addedBy;
    actions.append(button("Ban guest", async () => {
      if (await banGuestConfirmed("/api/rooms/" + encodeURIComponent(roomId), guest)) {
        historyStatus.textContent = guest.name + " is banned from this room.";
      }
    }, (e) => { historyStatus.textContent = message(e); }, "small"));
  }
  const item: RoomItem = withCover ?? { id: r.item.id, title: r.item.title, artist: r.item.artist };
  const via = viaText(r.via);
  return h("li", { class: "entry" },
    cover(item, "cover entry-cover"),
    h("div", { class: "grow" },
      itemTitle({ id: r.item.id, title: r.item.title, artist: r.item.artist }),
      h("div", { class: "row" },
        r.state === "skipped" ? h("span", { class: "badge", text: "Skipped" }) : null,
        r.item.status === "purged" ? h("span", { class: "badge", text: "Expired" }) : null,
        h("span", { class: "muted small", text: formatTime(r.playedAt) }),
        addedByLine(r.addedBy),
        via !== "" ? h("span", { class: "muted small", text: via }) : null)),
    actions);
}

// ------------------------------------------------------------------ requests

/** "Add" for those whose requests go straight into the queue, "Suggest" in owner mode. */
function requestVerb(s: RoomState): string {
  return s.room.controlMode === "owner" && !s.room.you.canModerate ? "Suggest" : "Add to queue";
}

function addedMessage(ack: Record<string, unknown>): string {
  return ack["state"] === "suggested" ? "Sent as a suggestion." : "Added to the queue.";
}

/** What the request panel was built for, so it is only rebuilt when that changes. */
let requestKey = "";

function renderRequests(s: RoomState): void {
  const you = s.room.you;
  const isUser = me.kind === "user";
  // library search: off for guests without the capability and when the room's libraryRequests is off
  const canSearch = you.canRequestLibrary === true;
  const key = [s.room.controlMode, you.identified, canSearch, you.canUpload, you.canYoutube, you.canModerate, isUser].join(",");
  if (key === requestKey) return;
  requestKey = key;
  const el = element("room-request");
  const parts: Node[] = [h("h2", { text: s.room.controlMode === "owner" && !you.canModerate ? "Suggest something" : "Request" })];
  if (canSearch) parts.push(searchPanel());
  if (you.canUpload) parts.push(uploadPanel());
  if (you.canYoutube) parts.push(youtubePanel());
  if (you.canModerate && isUser) parts.push(playlistPanel());
  if (parts.length === 1) {
    parts.push(h("p", { class: "muted", text: me.kind === "user" ? "You can't request in this room."
      : !you.identified ? "Join as a guest or log in to request."
        : "Requests aren't open to guests here. Log in to request." }));
  }
  el.replaceChildren(...parts);
}

function searchPanel(): HTMLElement {
  const results = h("ul", { class: "entries search-results" });
  const status = h("p", { class: "muted small", role: "status" });
  const more = h("button", { type: "button", text: "More" });
  more.hidden = true;
  const input = h("input", { name: "q", type: "search", placeholder: "Search title, artist or album",
    "aria-label": "Search", class: "grow", maxlength: "200" });
  const kind = h("select", { name: "kind", "aria-label": "Kind" },
    h("option", { value: "", text: "All" }), h("option", { value: "audio", text: "Audio" }),
    h("option", { value: "video", text: "Video" }));
  const all = h("input", { name: "all", type: "checkbox" });
  all.checked = rememberedWholeServer();
  const form = h("form", { class: "row" }, input, kind, h("button", { type: "submit", text: "Search" }),
    h("label", { class: "small" }, all, " Search the whole server"));
  let cursor: string | undefined;
  let generation = 0;

  const row = (item: ItemSummary): HTMLLIElement => {
    const li = h("li", { class: "entry" });
    const add = button(shown !== undefined ? requestVerb(shown) : "Add", async () => {
      const ack = await conn.request({ type: "queue.add", itemId: item.id });
      status.textContent = (item.title ?? "") + ": " + addedMessage(ack);
    }, (e) => { if (e !== CANCELLED) status.textContent = message(e); });
    li.append(h("div", { class: "grow" },
      h("div", { class: "item-title" }, h("a", { href: itemPath(item.id), text: item.title }),
        item.artist !== undefined ? h("div", { class: "muted small", text: item.artist }) : null),
      h("div", { class: "row" }, statusBadge(item.status),
        h("span", { class: "muted small num", text: formatDuration(item.durationMs) }))), add);
    return li;
  };

  const load = async (reset: boolean): Promise<void> => {
    const mine = ++generation;
    if (reset) {
      cursor = undefined;
      results.replaceChildren();
    }
    const params = new URLSearchParams();
    if (input.value.trim() !== "") params.set("q", input.value.trim());
    if (kind.value !== "") params.set("kind", kind.value);
    if (all.checked) params.set("all", "1");
    params.set("limit", "25");
    if (cursor !== undefined) params.set("cursor", cursor);
    status.textContent = "Searching…";
    try {
      const page = await api<Page<ItemSummary>>("GET", "/api/rooms/" + encodeURIComponent(roomId) + "/search?" + params);
      if (mine !== generation) return;
      for (const item of page.items) results.append(row(item));
      cursor = page.nextCursor;
      more.hidden = cursor === undefined;
      status.textContent = results.childElementCount === 0 ? "Nothing found." : "";
    } catch (e) {
      if (mine !== generation) return;
      more.hidden = true;
      status.textContent = e instanceof ApiError && e.code === "library_requests_off"
        ? "This room doesn't take requests from the library." : message(e);
    }
  };
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void load(true);
  });
  all.addEventListener("change", () => {
    rememberWholeServer(all.checked);
    if (results.childElementCount > 0 || status.textContent !== "") void load(true);
  });
  more.addEventListener("click", () => void load(false));
  return h("div", { class: "request-search" }, h("h3", { text: "From the library" }), form, status, results, more);
}

function uploadPanel(): HTMLElement {
  const input = h("input", { type: "file", multiple: true, accept: "audio/*,video/*", id: "room-upload-files" });
  const drop = h("label", { class: "dropzone", for: "room-upload-files" },
    h("strong", { text: "Upload files" }),
    h("span", { class: "muted", text: " Choose or drop audio or video files to request them." }));
  const activity = h("ul", { class: "activity", "aria-live": "polite" });
  const addToLibrary = h("input", { type: "checkbox" });
  const libraryOption = me.kind === "user"
    ? h("label", { class: "check" }, addToLibrary, " Also add to my library") : null;

  const startFile = (file: File): void => {
    const bar = h("progress", { max: "1", value: "0" });
    const text = h("span", { class: "muted small" });
    const cancel = h("button", { type: "button", text: "Cancel" });
    const row = h("li", { class: "activity-row" },
      h("div", { class: "activity-head" }, h("strong", { text: file.name })),
      h("div", { class: "row" }, bar, text, cancel));
    activity.prepend(row);
    let queued = false;
    const upload = new Upload(file, (p: UploadProgress) => {
      bar.value = p.size > 0 ? p.sentBytes / p.size : 1;
      text.className = p.state === "failed" ? "error small" : "muted small";
      switch (p.state) {
        case "checking": text.textContent = "Checking the file… " + Math.floor((p.checked ?? 0) * 100) + " %"; break;
        case "starting": text.textContent = "Starting…"; break;
        case "uploading": text.textContent = "Uploading " + formatBytes(p.sentBytes) + " of " + formatBytes(p.size); break;
        case "retrying": text.textContent = p.error ?? "Retrying"; break;
        case "paused": text.textContent = "Paused"; break;
        case "processing": text.textContent = "Checking the file…"; break;
        case "failed": text.textContent = p.error ?? "Failed"; cancel.hidden = true; break;
        case "done": {
          cancel.hidden = true;
          if (queued) break;
          queued = true;
          const uploadId = upload.uploadId;
          text.textContent = "Requesting…";
          const classify = p.result?.classify;
          conn.request({ type: "queue.add", uploadId }).then((ack) => {
            text.textContent = addedMessage(ack) + (p.result?.deduplicated === true ? " (already on the server)" : "")
              + " It plays once it has been processed.";
            // The request plays as it is; the library add waits for a media type.
            if (classify !== undefined) {
              classifyAndAdd(classify, file.name).then((added) => {
                text.textContent += added ? " Added to your library." : " Not added to your library (no media type).";
              }).catch((e: unknown) => { text.textContent += " " + message(e); });
            }
          }).catch((e: unknown) => {
            text.className = "error small";
            text.textContent = e === CANCELLED ? "Cancelled" : message(e);
          });
          break;
        }
      }
    });
    // Guests upload as a request in this room (their rights come from it); a user's upload
    // rights come from `library.upload`, so theirs is a plain upload that queue.add references.
    if (me.kind !== "user") upload.roomId = roomId;
    upload.addToLibrary = addToLibrary.checked;
    cancel.addEventListener("click", () => upload.cancel());
    const begin = me.kind === "user" ? Promise.resolve() : conn.ensureIdentity();
    begin.then(() => upload.start()).catch((e: unknown) => {
      text.className = "error small";
      text.textContent = e === CANCELLED ? "Cancelled" : message(e);
    });
  };
  const startFiles = (files: FileList | null): void => {
    if (files === null) return;
    for (const f of Array.from(files)) startFile(f);
  };
  input.addEventListener("change", () => {
    startFiles(input.files);
    input.value = "";
  });
  drop.addEventListener("dragover", (ev) => {
    ev.preventDefault();
    drop.classList.add("dragging");
  });
  drop.addEventListener("dragleave", () => drop.classList.remove("dragging"));
  drop.addEventListener("drop", (ev) => {
    ev.preventDefault();
    drop.classList.remove("dragging");
    startFiles(ev.dataTransfer?.files ?? null);
  });
  return h("div", { class: "add-upload" }, h("h3", { text: "Upload" }), input, drop, libraryOption, activity);
}

function youtubePanel(): HTMLElement {
  const url = h("input", { name: "url", type: "url", required: true, placeholder: "https://www.youtube.com/watch?v=…",
    "aria-label": "YouTube link", class: "grow" });
  const submit = h("button", { type: "submit", text: "Request" });
  const form = h("form", { class: "row add-youtube" }, url, submit);
  const status = h("p", { class: "muted small", role: "status" });
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    const link = url.value.trim();
    if (link === "") return;
    submit.disabled = true;
    status.className = "muted small";
    // The lookup can take a while: show yt-dlp's stages (request.progress) and the seconds so far.
    const started = Date.now();
    let stageText: string | undefined;
    const showLookup = (): void => { status.textContent = lookupText(stageText, Date.now() - started); };
    showLookup();
    const ticker = window.setInterval(showLookup, 1000);
    conn.request({ type: "queue.add", youtubeUrl: link }, (_stage, text) => {
      stageText = text;
      showLookup();
    }).then((ack) => {
      window.clearInterval(ticker);
      form.reset();
      status.textContent = addedMessage(ack) + " New videos play once they have been downloaded.";
    }).catch((e: unknown) => {
      window.clearInterval(ticker);
      status.className = "error small";
      status.textContent = e === CANCELLED ? "" : message(e);
    }).finally(() => {
      window.clearInterval(ticker);
      submit.disabled = false;
    });
  });
  return h("div", {}, h("h3", { text: "YouTube link" }), form, status);
}

function playlistPanel(): HTMLElement {
  const select = h("select", { "aria-label": "Playlist" }, h("option", { value: "", text: "Choose a playlist…" }));
  const shuffle = h("input", { type: "checkbox" });
  const status = h("p", { class: "muted small", role: "status" });
  myPlaylists().then((lists) => {
    for (const p of lists) select.append(h("option", { value: p.id, text: p.name + " (" + p.entryCount + ")" }));
  }).catch((e: unknown) => { status.textContent = message(e); });
  const add = button("Add playlist", async () => {
    if (select.value === "") return;
    status.textContent = "Adding…";
    const ack = await conn.request({ type: "queue.addPlaylist", playlistId: select.value, shuffle: shuffle.checked });
    const ids = ack["entryIds"] as unknown[] | undefined;
    const skipped = Number(ack["skipped"] ?? 0);
    status.textContent = "Added " + (ids?.length ?? 0) + " entries" + (skipped > 0 ? ", skipped " + skipped : "") + ".";
  }, (e) => { status.textContent = message(e); });
  return h("div", {}, h("h3", { text: "Playlist" }),
    h("div", { class: "row" }, select, h("label", { class: "check" }, shuffle, " Shuffle"), add), status);
}

// ------------------------------------------------------------------ wiring

function render(s: RoomState): void {
  shown = s;
  roomId = s.room.id;
  delayed.push(s.timeline, s.current, serverNow());
  renderHeader(s);
  renderIdentity();
  renderNowPlaying(s);
  renderQueue(s);
  renderSuggestions(s);
  renderRequests(s);
  renderHistory(s);
  outputsPanel ??= new OutputsPanel(element("room-outputs"));
  outputsPanel.update(s);
  renderRemoteDelay();
  // An audio-only room plays every item's audio (the server gives no video URLs): no switch.
  const audioOnlyRoom = isAudioOnlyRoom(s);
  element("room-audio-only-label").hidden = audioOnlyRoom;
  element("room-audio-only-room").hidden = !audioOnlyRoom;
  element("room-main").hidden = false;
  if (mode === "listen") player?.update(s);
  shell?.update();
}

// ------------------------------------------------------------------ mode and remote delay

/** The remote delay line: "Delay 5.0 s (RTMP)", the user's nudge, and −/+ 0.5 s buttons. */
function renderRemoteDelay(): void {
  const d = remoteDelays(shown);
  element("room-delay-total").textContent = formatSeconds(d.totalMs);
  const kind = ({ rtmp: "RTMP", ts: ".ts", hls: "HLS", mp3: "MP3" } as Record<string, string>)[d.kind] ?? d.kind;
  element("room-delay-detail").textContent = "(" + kind + " " + formatSeconds(d.outputMs)
    + (nudge !== 0 ? ", your nudge " + formatSeconds(nudge, true) : "") + ")";
  (element("room-delay-reset") as HTMLButtonElement).hidden = nudge === 0;
  (element("room-delay-less") as HTMLButtonElement).disabled = stepNudge(nudge, -1, d.outputMs) === nudge;
  (element("room-delay-more") as HTMLButtonElement).disabled = stepNudge(nudge, 1, d.outputMs) === nudge;
}

function setNudge(n: number): void {
  nudge = n;
  storageSet(nudgeKey(roomId), n === 0 ? null : String(n));
  renderRemoteDelay();
  if (shown !== undefined) renderNowPlaying(shown);
}

/** Shows the controls of a mode and starts or stops local playback. */
function applyMode(m: RoomMode): void {
  mode = m;
  element("room-body").dataset["mode"] = m;
  element("room-mode").textContent = m === "remote" ? "Remote mode" : "Listen mode";
  const toggle = element("room-mode-toggle") as HTMLButtonElement;
  toggle.textContent = m === "remote" ? "Listen here instead" : "I'm listening through the stream (remote mode)";
  element("room-listen-controls").hidden = m !== "listen";
  element("room-remote-controls").hidden = m !== "remote";
  if (m === "listen") {
    const p = player ?? createPlayer();
    p.update(shown);
  } else {
    // Remote mode loads no media: every element is stopped and emptied.
    player?.stop();
  }
  renderRemoteDelay();
  if (shown !== undefined) renderNowPlaying(shown);
}

/** The toggle: remember the choice for this room, tell the server, switch. */
function switchMode(m: RoomMode): void {
  if (m === mode) return;
  storageSet(modeKey(roomId), m);
  // The URL's ?mode= would win over the remembered choice on the next visit.
  const url = new URL(location.href);
  if (url.searchParams.has("mode")) {
    url.searchParams.delete("mode");
    try {
      history.replaceState(history.state, "", url.pathname + url.search + url.hash);
    } catch {
      // Not allowed (e.g. a file:// page): harmless.
    }
  }
  conn.mode = m;
  if (conn.joined) conn.request({ type: "mode", mode: m }).catch(showError);
  applyMode(m);
  // The toggle click is a user gesture: let the new player play at once.
  if (m === "listen") player?.unlock();
}

function createPlayer(): ListenPlayer {
  const syncMode = syncModeFor(engineHints(), new URLSearchParams(PAGE_SEARCH).get("sync"));
  const select = element("room-webaudio") as HTMLSelectElement;
  const note = element("room-webaudio-note");
  const showSound = (): void => {
    const sound = player?.sound;
    note.textContent = sound !== undefined ? soundNote(sound, canSetVolume()) : "";
    const override = urlWebAudio();
    note.title = sound !== undefined && !sound.webAudio
      ? (canSetVolume() ? "Played straight from the media element (volume set by the page)."
        : "Played straight from the media element: no crossfade when switching to or from audio only, and"
          + " originals play without their loudness adjustment (this browser can't set the volume).")
        + (sound.off !== undefined ? " (" + sound.off + ")" : "") : "";
    if (override !== undefined) note.textContent += " (?webaudio=" + (override ? "1" : "0") + " in the URL)";
  };
  const p = new ListenPlayer(element("room-player"), element("room-listen-overlay"), serverNow, loadAudioOnly(),
    syncMode, webAudioPlan(urlWebAudio(), loadWebAudioSetting(), loadWebAudioMemory(), canSetVolume()), {
      remember: (m) => storageSet(WEB_AUDIO_MEMORY_KEY, JSON.stringify(m)),
      onSound: showSound,
    });
  player = p;
  const hint = element("room-webaudio-hint");
  select.value = loadWebAudioSetting();
  hint.hidden = select.value !== "off";
  select.addEventListener("change", () => {
    const setting = parseWebAudioSetting(select.value);
    hint.hidden = setting !== "off";
    storageSet(WEB_AUDIO_KEY, setting === "auto" ? null : setting);
    // A deliberate choice starts over: Auto checks this browser again.
    storageSet(WEB_AUDIO_MEMORY_KEY, null);
    // The change event is a user gesture: the rebuilt elements are unlocked in it.
    p.setWebAudioPlan(webAudioPlan(urlWebAudio(), setting, undefined, canSetVolume()));
  });
  showSound();
  const toggle = element("room-audio-only") as HTMLInputElement;
  toggle.checked = p.audioOnly;
  toggle.addEventListener("change", () => setAudioOnly(toggle.checked));
  window.setInterval(() => { if (mode === "listen") p.tick(); }, 250);
  // For checking by hand: togetherTunesPlayer.debug() shows source, gain, rate and drift.
  (window as unknown as { togetherTunesPlayer: ListenPlayer }).togetherTunesPlayer = p;
  return p;
}

/** Whether the room is audio-only (its `audioOnly` setting). */
function isAudioOnlyRoom(s: RoomState | undefined): boolean {
  return s?.room.settings["audioOnly"] === true;
}

/** The video / audio-only switch (the checkbox here or the shell's mini player). */
function setAudioOnly(on: boolean): void {
  player?.setAudioOnly(on);
  saveAudioOnly(on);
  (element("room-audio-only") as HTMLInputElement).checked = on;
  shell?.update();
}

/** Whether `ref` (an id or slug from a link) is this room. */
function isThisRoom(ref: string): boolean {
  return ref === roomId || ref === roomRef || (roomSlug !== "" && ref.toLowerCase() === roomSlug.toLowerCase());
}

function createShell(): Shell {
  return new Shell({
    active: () => mode === "listen" && player !== undefined && kickedText === undefined,
    isRoom: isThisRoom,
    path: () => "/rooms/" + encodeURIComponent(roomSlug !== "" ? roomSlug : roomId),
    name: () => roomName,
    title: () => roomName + " · TogetherTunes",
    nowPlaying: () => ({ title: shown?.current?.item.title ?? "", state: shown?.timeline.state ?? "idle" }),
    stopped: () => kickedText,
    audioOnly: () => player?.audioOnly ?? loadAudioOnly(),
    audioOnlyRoom: () => isAudioOnlyRoom(shown),
    setAudioOnly,
    shown: () => renderNav(me),
  }, element("room-body"));
}

/** The engine's features that decide the sync mode (the user agent may lie). */
function engineHints(): EngineHints {
  const proto = typeof HTMLMediaElement !== "undefined" ? HTMLMediaElement.prototype : {};
  return {
    preservesPitch: "preservesPitch" in proto,
    webkitPreservesPitch: "webkitPreservesPitch" in proto,
    mozPreservesPitch: "mozPreservesPitch" in proto,
    webkitPlaybackTarget: "WebKitPlaybackTargetAvailabilityEvent" in window,
  };
}

/** Largest diagnostics report sent (the server takes 256 KiB). */
const MAX_REPORT_CHARS = 240_000;

/** Everything "Send diagnostics" sends: no addresses, tokens or cookies (see diagnostics.ts). */
function diagnosticsReport(ref: string): Record<string, unknown> {
  const cur = shown?.current;
  const report: Record<string, unknown> = {
    ref,
    userAgent: navigator.userAgent,
    // The user agent may lie (Reynard, Gecko on iOS, says Android): the engine's own hints.
    engine: { ...engineHints(), vendor: navigator.vendor, maxTouchPoints: navigator.maxTouchPoints,
      platform: navigator.platform, audioContext: typeof AudioContext !== "undefined" ? "AudioContext"
        : "webkitAudioContext" in window ? "webkitAudioContext" : "none" },
    page: stripQuery(location.pathname),
    syncOverride: new URLSearchParams(PAGE_SEARCH).get("sync"),
    webAudioOverride: new URLSearchParams(PAGE_SEARCH).get("webaudio"),
    webAudioSetting: storageGet(WEB_AUDIO_KEY),
    webAudioMemory: storageGet(WEB_AUDIO_MEMORY_KEY),
    volumeSettable: canSetVolume(),
    roomId,
    mode,
    visibility: document.visibilityState,
    epochMs: Date.now(),
    perfNowMs: Math.round(performance.now()),
    clock: clock?.state(),
    timeline: shown?.timeline,
    current: cur === undefined ? undefined : { entryId: cur.entryId, itemId: cur.item.id, kind: cur.item.kind,
      durationMs: cur.item.durationMs, status: cur.status },
    player: player?.debug(),
    ...snapshot(),
  };
  // Keep it under the server's limit: drop the oldest decisions and events first.
  for (const key of ["decisions", "health", "events"]) {
    while (JSON.stringify(report).length > MAX_REPORT_CHARS) {
      const list = report[key] as unknown[];
      if (list.length === 0) break;
      list.splice(0, Math.ceil(list.length / 4));
    }
  }
  return report;
}

async function sendDiagnostics(): Promise<void> {
  const status = element("room-diagnostics-status");
  const ref = Math.random().toString(36).slice(2, 8);
  status.textContent = "Sending…";
  try {
    recordEvent("diagnostics", { ref });
    await api("POST", "/api/diagnostics", diagnosticsReport(ref));
    status.textContent = "Sent (ref " + ref + ").";
  } catch (e) {
    status.textContent = "Not sent: " + message(e);
  }
}

function onRequest(r: NewRequest): void {
  const via = { library: "from the library", upload: "by upload", youtube: "from YouTube",
    history: "from the history" }[r.via] ?? r.via;
  const node = h("span", {}, r.by !== undefined ? actorLabel(r.by) : "Someone", " requested ",
    h("a", { href: itemPath(r.item.id), text: r.item.title ?? "an item" }), " " + via + ".");
  notice("", node);
}

const KICK_TEXT: Record<string, string> = {
  banned_from_room: "You are banned from this room.",
  room_not_found: "This room is no longer available to you.",
  room_deleted: "This room was deleted.",
  session_ended: "Your session ended. Log in again to rejoin.",
};

function notFound(): void {
  element("room-body").replaceChildren(h("h1", { text: "Not found" }),
    h("p", { text: "There is no room at this link." }));
}

async function start(): Promise<void> {
  // A room page loaded from scratch: whatever played before has stopped (no "stopped" note here).
  rememberPlaying(null);
  try {
    me = await loadMe();
  } catch (e) {
    element("room-status").textContent = message(e);
    return;
  }
  if (!checkPasswordChange(me)) return;
  renderNav(me);
  const ref = roomFromLocation();
  if (ref === "") {
    element("room-status").hidden = true;
    notFound();
    return;
  }
  let room: RoomResponse;
  try {
    room = await api<RoomResponse>("GET", "/api/rooms/" + encodeURIComponent(ref));
  } catch (e) {
    element("room-status").hidden = true;
    if (e instanceof ApiError && e.status === 404) notFound();
    else element("room-error").textContent = message(e);
    return;
  }
  roomId = room.id;
  roomSlug = room.slug;
  roomRef = ref;
  roomName = room.name;
  shell = createShell();
  document.title = room.name + " · TogetherTunes";
  element("room-name").textContent = room.name;
  mode = chooseMode(modeFromLocation(), storageGet(modeKey(room.id)));
  nudge = parseNudge(storageGet(nudgeKey(room.id)));
  element("room-status").textContent = "Connecting…";

  conn = new RoomConnection(room.id, mode, {
    state: (s) => {
      element("room-status").hidden = true;
      render(s);
    },
    connection: (connected) => {
      const status = element("room-status");
      if (connected) {
        status.hidden = true;
        clock?.start();
      } else {
        clock?.stop();
        status.hidden = false;
        status.textContent = "Connection lost. Reconnecting…";
      }
    },
    kicked: (reason) => {
      player?.stop();
      kickedText = KICK_TEXT[reason] ?? "You were removed from this room (" + reason + ").";
      shell?.update();
      element("room-main").hidden = true;
      const status = element("room-status");
      status.hidden = false;
      status.textContent = kickedText;
    },
    request: onRequest,
    identity: () => renderIdentity(),
    chooseGuestName: (generated) => askGuestName(generated),
    time: (t0, ts, t1) => clock?.reply(t0, ts, t1),
    ingest: () => showIngest(),
  });
  const c = new ClockSync((t0) => conn.sendTime(t0));
  clock = c;
  // A better clock estimate moves the target: correct at once instead of at the next tick.
  c.onUpdate = () => { if (mode === "listen") player?.tick(); };
  element("room-mode-toggle").addEventListener("click", () => switchMode(mode === "remote" ? "listen" : "remote"));
  element("room-diagnostics").addEventListener("click", () => { void sendDiagnostics(); });
  element("room-delay-less").addEventListener("click", () => setNudge(stepNudge(nudge, -1, remoteDelays(shown).outputMs)));
  element("room-delay-more").addEventListener("click", () => setNudge(stepNudge(nudge, 1, remoteDelays(shown).outputMs)));
  element("room-delay-reset").addEventListener("click", () => setNudge(0));
  applyMode(mode);
  // The client's clock may have stepped while the device slept: measure again at once.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") c.syncNow();
  });
  // For checking sync by hand (two browsers within ~50 ms): the estimate is in the console.
  (window as unknown as { togetherTunesClock: ClockSync }).togetherTunesClock = c;
  conn.actor = me.kind === "anonymous" ? undefined : { kind: me.kind, id: me.id, name: me.name, isGuest: me.isGuest };
  renderIdentity();
  conn.connect();
  // Leaving the page leaves the room at once, also when the browser keeps the page in its
  // back/forward cache (Chrome kept the socket open there, so another room's page found this one
  // still "listening"). Coming back from that cache starts over with a fresh page.
  window.addEventListener("pagehide", () => {
    conn.stop();
    player?.stop();
  });
  window.addEventListener("pageshow", (ev) => {
    if (ev.persisted) location.reload();
  });
  window.setInterval(tick, 250);
}

onReady(start);
