// The room page (/rooms/{idOrSlug}). The QR short link /r/{slug} redirects here with
// ?mode=remote (docs/rooms.md#web-and-app-playback), which opens the page in remote mode.
//
// The page joins the room over the WebSocket (roomclient.ts) and shows what is playing, the
// queue (with votes, pins, moves and removals as the control mode and the viewer's rights allow),
// suggestions (accept/reject for the owner and moderators), the request panel (search within the
// room's request scope, upload, YouTube link, and a playlist for the owner and moderators), the
// history and who is here. The server's clock is estimated with ClockSync (clocksync.ts) on the
// same socket. In listen mode the page plays the room itself (ListenPlayer, listen.ts); in remote mode
// (remote.ts) it loads no media and shows the timeline delayed by the active output's
// estimatedDelayMs plus the user's nudge, as heard in VRChat. The mode is a toggle remembered per
// room; `?mode=` wins. The owner and moderators get a link to the management view (roommanage.ts).
// In listen mode, "Send diagnostics" posts what the player did (diagnostics.ts) to the server log,
// for devices without a console; `?sync=rate|seek` overrides the sync mode for testing, and
// `?webaudio=0` plays straight from the media elements instead of through Web Audio (A/B test).
import { api, ApiError } from "./api.js";
import { actorLabel, type ActorRef } from "./actor.js";
import { webAudioWanted } from "./audiohealth.js";
import { ClockSync } from "./clocksync.js";
import { captureErrors, recordEvent, snapshot, stripQuery, type ErrorTarget } from "./diagnostics.js";
import { ListenPlayer } from "./listen.js";
import { syncModeFor, type EngineHints } from "./listensync.js";
import {
  chooseMode, DelayedTimeline, effectiveDelay, formatSeconds, modeKey, nudgeKey, outputDelay, parseNudge, stepNudge,
  type RoomMode,
} from "./remote.js";
import { button, formatBytes, formatDuration, formatTime, h } from "./dom.js";
import { itemPath, myPlaylists, statusBadge, type ItemSummary, type Page } from "./media.js";
import {
  positionAt, RoomConnection, type CurrentEntry, type HistoryEntry, type NewRequest, type RoomItem, type RoomState,
  type Timeline, type WaitingEntry,
} from "./roomclient.js";
import {
  checkPasswordChange, element, here, loadMe, LOGIN_PAGE, message, onReady, renderNav, withNext, type Me,
} from "./session.js";
import { Upload, type UploadProgress } from "./uploads.js";

// Page errors from now on go into the diagnostics ring (sent with "Send diagnostics").
captureErrors(window as unknown as ErrorTarget);

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
  return new URLSearchParams(location.search).get("room") ?? "";
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

function saveAudioOnly(on: boolean): void {
  try {
    localStorage.setItem(AUDIO_ONLY_KEY, on ? "1" : "0");
  } catch {
    // Private mode or blocked storage: the choice lasts for this page only.
  }
}

/** The mode requested by the URL (`?mode=remote` from the short link), if any. */
export function modeFromLocation(): RoomMode | null {
  const mode = new URLSearchParams(location.search).get("mode");
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

function entryStatusBadge(status: string): HTMLElement | null {
  if (status === "ready") return null;
  if (status === "processing") return statusBadge("processing");
  if (status === "failed" || status === "purged") return statusBadge(status);
  return h("span", { class: "badge badge-status", text: status });
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
  document.title = s.room.name + " · TogetherTunes";
  element("room-name").textContent = s.room.name;
  const mode = { owner: "Owner picks", vote: "Vote", open: "Open" }[s.room.controlMode] ?? s.room.controlMode;
  const role = s.room.you.role;
  element("room-meta").textContent = "Control: " + mode + (role !== undefined ? " · You are " + role : "");
  // The management view (settings, members, guest bans, request history) for the owner and moderators.
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
        h("div", { class: "row" }, h("span", { class: "badge", text: stateText }), entryStatusBadge(c.status),
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

function canMove(s: RoomState): boolean {
  const you = s.room.you;
  if (s.room.controlMode === "owner") return you.canModerate;
  if (s.room.controlMode === "open") return you.canModerate || (you.canRequest && you.identified);
  return false;
}

function queueRow(s: RoomState, e: WaitingEntry, i: number, list: WaitingEntry[]): HTMLLIElement {
  const you = s.room.you;
  const actions = h("span", { class: "row entry-actions" });
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
        entryStatusBadge(e.status),
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
        h("div", { class: "row" }, entryStatusBadge(e.status),
          h("span", { class: "muted small num", text: formatDuration(e.item.durationMs) }),
          addedByLine(e.addedBy, "Suggested by "))),
      actions));
  }
  el.replaceChildren(h("h2", { text: "Suggestions (" + s.suggestions.length + ")" }), list);
}

function renderHistory(s: RoomState): void {
  const el = element("room-history");
  if (s.history.length === 0) {
    el.replaceChildren(h("h2", { text: "History" }), h("p", { class: "muted", text: "Nothing has played yet." }));
    return;
  }
  const list = h("ul", { class: "entries history" });
  for (const e of s.history as HistoryEntry[]) {
    list.append(h("li", { class: "entry" },
      cover(e.item, "cover entry-cover"),
      h("div", { class: "grow" },
        itemTitle(e.item),
        h("div", { class: "row" },
          e.state === "skipped" ? h("span", { class: "badge", text: "Skipped" }) : null,
          h("span", { class: "muted small", text: formatTime(e.playedAt) }),
          addedByLine(e.addedBy)))));
  }
  el.replaceChildren(h("h2", { text: "History" }), list);
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
  const canSearch = you.canRequest || you.canModerate;
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
  const form = h("form", { class: "row" }, input, kind, h("button", { type: "submit", text: "Search" }));
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
      status.textContent = e instanceof ApiError && e.code === "library_requests_disabled"
        ? "This room doesn't take requests from the library." : message(e);
    }
  };
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void load(true);
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
          conn.request({ type: "queue.add", uploadId }).then((ack) => {
            text.textContent = addedMessage(ack) + (p.result?.deduplicated === true ? " (already on the server)" : "")
              + " It plays once it has been processed.";
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
    status.textContent = "Looking up the video…";
    conn.request({ type: "queue.add", youtubeUrl: link }).then((ack) => {
      form.reset();
      status.textContent = addedMessage(ack) + " New videos play once they have been downloaded.";
    }).catch((e: unknown) => {
      status.className = "error small";
      status.textContent = e === CANCELLED ? "" : message(e);
    }).finally(() => { submit.disabled = false; });
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
  renderRemoteDelay();
  element("room-main").hidden = false;
  if (mode === "listen") player?.update(s);
}

// ------------------------------------------------------------------ mode and remote delay

/** The remote delay line: "Delay 5.0 s (RTMP)", the user's nudge, and −/+ 0.5 s buttons. */
function renderRemoteDelay(): void {
  const d = remoteDelays(shown);
  element("room-delay-total").textContent = formatSeconds(d.totalMs);
  const kind = { rtmp: "RTMP", ts: ".ts", hls: "HLS" }[d.kind] ?? d.kind;
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
  toggle.textContent = m === "remote" ? "Listen here instead" : "I'm listening in VRChat (remote mode)";
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
  const syncMode = syncModeFor(engineHints(), new URLSearchParams(location.search).get("sync"));
  const p = new ListenPlayer(element("room-player"), element("room-listen-overlay"), serverNow, loadAudioOnly(),
    syncMode, webAudioWanted(new URLSearchParams(location.search).get("webaudio")));
  player = p;
  const toggle = element("room-audio-only") as HTMLInputElement;
  toggle.checked = p.audioOnly;
  toggle.addEventListener("change", () => {
    p.setAudioOnly(toggle.checked);
    saveAudioOnly(toggle.checked);
  });
  window.setInterval(() => { if (mode === "listen") p.tick(); }, 250);
  // For checking by hand: togetherTunesPlayer.debug() shows source, gain, rate and drift.
  (window as unknown as { togetherTunesPlayer: ListenPlayer }).togetherTunesPlayer = p;
  return p;
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
    syncOverride: new URLSearchParams(location.search).get("sync"),
    webAudioOverride: new URLSearchParams(location.search).get("webaudio"),
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
  const via = { library: "from the library", upload: "by upload", youtube: "from YouTube" }[r.via] ?? r.via;
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
      element("room-main").hidden = true;
      const status = element("room-status");
      status.hidden = false;
      status.textContent = KICK_TEXT[reason] ?? "You were removed from this room (" + reason + ").";
    },
    request: onRequest,
    identity: () => renderIdentity(),
    chooseGuestName: (generated) => askGuestName(generated),
    time: (t0, ts, t1) => clock?.reply(t0, ts, t1),
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
  window.setInterval(tick, 250);
}

onReady(start);
