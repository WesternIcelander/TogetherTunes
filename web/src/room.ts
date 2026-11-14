// The room page (/rooms/{idOrSlug}). The QR short link /r/{slug} redirects here with
// ?mode=remote (docs/rooms.md#web-and-app-playback), which opens the page in remote mode.
//
// The room view (docs/ui.md#room-view-desktop): the frame (frame.ts) moves its video panel into the
// view's video area and draws Now Playing / Requests / From Playlist, the people (⋯ moderation menus,
// Manage Room) and Earlier from this view's room state; this module draws the header, the owner's and
// moderators' quick output buttons, the Request Link box, Join as guest / the guest's name, and for
// visitors who aren't signed in the library search and uploads where the room lets them request.
//
// The page joins the room over the WebSocket (roomclient.ts); the server's clock is estimated with
// ClockSync (clocksync.ts) on the same socket. In listen mode the page plays the room itself
// (ListenPlayer, listen.ts); in requests-only (remote) mode (remote.ts) it loads no media and shows
// what plays delayed by the active output's estimatedDelayMs plus the user's nudge, as heard through
// the stream. The mode is a toggle remembered per room; `?mode=` wins. The owner and moderators get a
// link to the management view (roommanage.ts). The outputs panel (outputspanel.ts) shows the RTMP
// viewer URL to everyone; the stream outputs themselves are in Manage Room.
// While this view lives, the debug-mode "Send diagnostics" button (frame.ts) posts what the player did
// (diagnostics.ts, `setReportSource`) to the server log, for devices without a console; `?sync=rate|seek`
// overrides the sync mode for testing, and the user's "Web Audio" preference (Auto / On / Off, user
// settings, prefs.ts; guests: what this browser had stored, else Auto) decides whether the media elements
// play through Web Audio; `?webaudio=0/1` overrides it for testing (audiohealth.ts `webAudioPlan`). Auto
// uses Web Audio only where the page can't set an element's volume (iOS; `volumeSettable`).
// While the room plays in listen mode, the single-page app's shell (shell.ts) keeps this view
// (hidden) when another view is shown, so playback goes on; the frame's player bar follows it.
// When the shell takes the view away for good, its signal (pageSignal() at the module's start)
// ends the socket, the player, the clock sync and the timers.
import { api, ApiError } from "./api.js";
import { actorLabel, type ActorRef } from "./actor.js";
import {
  parseWebAudioMemory, parseWebAudioSetting, volumeSettable, webAudioOverride, webAudioPlan, type WebAudioMemory,
  type WebAudioSetting,
} from "./audiohealth.js";
import { ClockSync } from "./clocksync.js";
import { captureErrors, recordEvent, setReportSource, snapshot, stripQuery, type ErrorTarget } from "./diagnostics.js";
import { prefs } from "./prefsclient.js";
import { ListenPlayer, type SoundInfo } from "./listen.js";
import { syncModeFor, type EngineHints } from "./listensync.js";
import {
  chooseMode, DelayedTimeline, effectiveDelay, effectiveMode, forcedRequestsOnly, formatSeconds, modeKey, modeLabel,
  nudgeKey, outputDelay, parseNudge, stepNudge,
  type RoomMode,
} from "./remote.js";
import { button, formatBytes, formatDuration, h } from "./dom.js";
import { itemPath, statusBadge, type ItemSummary, type Page } from "./media.js";
import { lookupText } from "./jobtext.js";
import {
  positionAt, RoomConnection, type CurrentEntry, type NewRequest, type RoomState, type Timeline,
} from "./roomclient.js";
import {
  checkPasswordChange, element, here, loadMe, LOGIN_PAGE, message, onReady, renderNav, withNext, type Me,
} from "./session.js";
import { Upload, type UploadProgress } from "./uploads.js";
import { attachRoom, pageSignal, rememberPlaying, viewStart, type RoomHandle } from "./nav.js";
import { shouldJoin } from "./alone.js";
import { OutputsPanel } from "./outputspanel.js";

// Page errors from now on go into the diagnostics ring (sent with "Send diagnostics").
captureErrors(window as unknown as ErrorTarget);

/**
 * What the shell started this view for: shown, or (the user's room, alone or not) playing in the
 * background from the start, while `location` is another view's.
 */
const START = viewStart(import.meta.url);

/** This view's signal: aborted when the shell takes the room view away (leaving the room). */
const ROOM_SIGNAL = START?.signal ?? pageSignal();

/** `setInterval` until the room view is taken away. */
function every(f: () => void, ms: number): void {
  const id = window.setInterval(f, ms);
  ROOM_SIGNAL.addEventListener("abort", () => window.clearInterval(id));
}

/**
 * The room page's own query (`?mode=`, `?sync=`, `?webaudio=`), kept from the load: while the
 * shell shows another page, `location` is that page's.
 */
const PAGE_SEARCH = START?.search ?? location.search;
/** The room view's path, kept from its start for the same reason. */
const PAGE_PATH = START?.path ?? location.pathname;

interface RoomResponse {
  id: string;
  slug: string;
  name: string;
  settings?: Record<string, unknown>;
  /** The user's alone room (docs/rooms.md#alone). */
  alone?: boolean;
}

/** The room from `/rooms/{idOrSlug}` (or `?room=` when the page is opened as a plain file). */
function roomFromLocation(): string {
  const m = /^\/rooms\/([^/]+)\/?$/.exec(PAGE_PATH);
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

/**
 * Where the old per-browser "Web Audio" setting (auto / on / off) was remembered: still read for guests
 * and anonymous visitors (users have the preference in their settings).
 */
const WEB_AUDIO_KEY = "togethertunes.webAudio";
/** What Auto learnt on this browser (WebAudioMemory as JSON: the rate that works, or direct only). */
const WEB_AUDIO_MEMORY_KEY = "togethertunes.webAudioAuto";

function loadWebAudioSetting(): WebAudioSetting {
  const p = prefs.get();
  return p !== undefined ? p.webAudio : parseWebAudioSetting(storageGet(WEB_AUDIO_KEY));
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
/** The page's mode: listen (plays the room) or remote (requests-only: no media, delayed view). */
let mode: RoomMode = "listen";
/** The mode the user chose (URL or toggle); `mode` differs while the room forces requests-only. */
let chosenMode: RoomMode = "listen";
/** Whether the room forces requests-only mode (its `requestsOnly` setting). */
let forced = false;
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
/** Whether this is the user's alone room (RoomResponse.alone). */
let roomAlone = false;
/** Why the room stopped playing here (kicked), shown in the mini player. */
let kickedText: string | undefined;
/** The shell's side: whether another view is shown, and the mini player (shell.ts). */
let shell: RoomHandle | undefined;
/** The outputs panel (RTMP viewer URL; stream URLs and controls for the owner and moderators). */
let outputsPanel: OutputsPanel | undefined;
/** The listener's volume and mute from the frame's player bar (applied to every player made here). */
let listenerGain = 1;
let listenerMuted = false;

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

// ------------------------------------------------------------------ header and presence

function renderHeader(s: RoomState): void {
  roomSlug = s.room.slug;
  roomName = s.room.name;
  // While the shell shows another page, the title is that page's.
  if (shell?.away !== true) document.title = s.room.name + " · TogetherTunes";
  element("room-name").textContent = s.room.name;
  const mode = { owner: "Owner picks", vote: "Vote", open: "Open" }[s.room.controlMode] ?? s.room.controlMode;
  const role = s.room.you.role;
  const r = s.room.you.restriction;
  const restricted = r === undefined ? ""
    : r.kind === "timeout" && r.until !== undefined
      ? " · You are timed out until " + new Date(r.until).toLocaleString() + ": you can listen, nothing else"
      : " · You are banned here: you can listen, nothing else";
  element("room-meta").textContent = "Control: " + mode + (role !== undefined && role !== "banned" ? " · You are " + role : "")
    + restricted;
  // The management view (settings, members, guest bans) for the owner and moderators.
  const manage = element<HTMLAnchorElement>("room-manage-link");
  manage.hidden = !s.room.you.canModerate;
  manage.href = "/rooms/" + encodeURIComponent(s.room.slug) + "/manage";
}

/**
 * Under the request section (docs/ui.md#existing-features-in-the-new-ui): **Join as guest** for an
 * anonymous visitor (where guests may do anything here), and once joined the guest's name with an
 * edit button (where the admin lets guests choose names). Users see nothing here (the header is theirs).
 */
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
    const loginLink = h("a", { href: withNext(LOGIN_PAGE, here()), text: "log in" });
    if (me.kind === "anonymous" && me.guestsEnabled && roomAllows) {
      el.replaceChildren(
        button("Join as guest", () => conn.ensureIdentity(), (e) => { if (e !== CANCELLED) showError(e); },
          "join-guest"),
        h("p", { class: "muted small" }, "to vote and request, or ", loginLink, "."));
    } else {
      el.replaceChildren(h("p", { class: "muted small" }, "You are listening anonymously. ",
        h("a", { href: withNext(LOGIN_PAGE, here()), text: "Log in" }), " to vote or request."));
    }
    return;
  }
  if (a.kind !== "guest") {
    el.replaceChildren();
    return;
  }
  const parts: (Node | string)[] = [h("span", { class: "muted small", text: "You are " }),
    h("strong", { class: "guest-name", text: a.name })];
  if ((me.kind === "guest" || me.kind === "anonymous") && me.customNames) {
    const edit = button("✎", async () => {
      const name = await askGuestName(a.name);
      if (name === a.name || name === "") return;
      const updated = await api<ActorRef>("PATCH", "/api/me/guest", { name });
      conn.actor = { kind: "guest", id: updated.id, name: updated.name, isGuest: true };
      renderIdentity();
    }, (e) => { if (e !== CANCELLED) showError(e); }, "icon guest-edit");
    edit.title = "Change your name";
    edit.setAttribute("aria-label", "Change your name");
    parts.push(" ", edit);
  }
  el.replaceChildren(h("p", { class: "guest-line" }, ...parts));
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

// ------------------------------------------------------------------ requests-only now playing

/** What the requests-only line shows: the timeline as it was `delay` ago. */
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
  const v = delayed.atTime(serverNow() - remoteDelays(s).totalMs);
  if (v !== undefined) return { timeline: v.timeline as Timeline, current: v.value };
  return { timeline: s.timeline, current: s.current };
}

/** The view the line was last drawn with, so the timer can tell when the delayed view moved on. */
let drawn: NowView | undefined;
let positionText: HTMLElement | undefined;

/**
 * Requests-only mode: what plays as heard through the stream (the room's output delay plus this
 * browser's nudge behind the timeline). The player bar and the queue show the timeline itself.
 */
function renderNowPlaying(s: RoomState): void {
  const el = element("room-now");
  el.hidden = mode !== "remote";
  positionText = undefined;
  if (mode !== "remote") {
    drawn = undefined;
    el.replaceChildren();
    return;
  }
  const view = nowView(s);
  drawn = view;
  const c = view.current;
  if (c === undefined) {
    el.replaceChildren(h("span", { class: "muted", text: "Through the stream: nothing is playing." }));
    return;
  }
  const st = view.timeline.state;
  positionText = h("span", { class: "small num" });
  el.replaceChildren(h("span", { class: "muted", text: "Through the stream: " }),
    h("a", { href: itemPath(c.item.id), text: c.item.title ?? "(unknown item)" }),
    h("span", { class: "muted", text: c.item.artist !== undefined && c.item.artist !== "" ? " · " + c.item.artist : "" }),
    h("span", { class: "muted", text: st === "paused" ? " (paused) " : " " }), positionText);
  tick();
}

/** Moves the requests-only position along (four times a second). */
function tick(): void {
  const s = shown;
  if (s === undefined || mode !== "remote") return;
  // The delayed view reached another change: draw the line again.
  const view = nowView(s);
  if (drawn === undefined || view.timeline !== drawn.timeline || view.current !== drawn.current) {
    renderNowPlaying(s);
    return; // renderNowPlaying ticks
  }
  if (view.current === undefined || positionText === undefined) return;
  const duration = view.current.item.durationMs ?? 0;
  let pos = positionAt(view.timeline, serverNow() - remoteDelays(s).totalMs);
  if (duration > 0) pos = Math.min(pos, duration);
  positionText.textContent = formatDuration(pos) + (duration > 0 ? " / " + formatDuration(duration) : "");
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

/**
 * Library search and uploads for visitors who aren't signed in (guests have no library, search or file
 * drops elsewhere), where the room lets them request that way. Signed-in users request from their lists,
 * the header's search and file drops.
 */
function renderRequests(s: RoomState): void {
  const you = s.room.you;
  const show = me.kind !== "user" && you.identified && you.restriction === undefined;
  const canSearch = show && you.canRequestLibrary === true;
  const canUpload = show && you.canUpload;
  const key = [s.room.controlMode, canSearch, canUpload, you.canModerate].join(",");
  if (key === requestKey) return;
  requestKey = key;
  const el = element("room-request");
  const parts: Node[] = [];
  if (canSearch) parts.push(searchPanel());
  if (canUpload) parts.push(uploadPanel());
  el.hidden = parts.length === 0;
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
      status.textContent = e instanceof ApiError && e.code === "library_requests_off"
        ? "This room doesn't take requests from the library." : message(e);
    }
  };
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void load(true);
  });
  more.addEventListener("click", () => void load(false));
  return h("div", { class: "request-search" }, h("h3", { text: shown !== undefined && requestVerb(shown) === "Suggest" ? "Suggest from the library" : "Request from the library" }), form, status, results, more);
}

function uploadPanel(): HTMLElement {
  const input = h("input", { type: "file", multiple: true, accept: "audio/*,video/*", id: "room-upload-files" });
  const drop = h("label", { class: "dropzone", for: "room-upload-files" },
    h("strong", { text: "Upload files" }),
    h("span", { class: "muted", text: " Choose or drop audio or video files to request them." }));
  const activity = h("ul", { class: "activity", "aria-live": "polite" });

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
    // A guest uploads as a request in this room (their rights come from it).
    upload.roomId = roomId;
    cancel.addEventListener("click", () => upload.cancel());
    conn.ensureIdentity().then(() => upload.start()).catch((e: unknown) => {
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
  return h("div", { class: "add-upload" }, h("h3", { text: "Upload" }), input, drop, activity);
}

/** What the Request Link box was built for, so it is only rebuilt when that changes (keeps what was typed). */
let requestLinkKey = "";

/** The room view's **Request Link** box (URL field + Request), for people who may request links here. */
function renderRequestLink(s: RoomState): void {
  const el = element("rv-request-link");
  const show = s.room.you.canYoutube && s.room.you.restriction === undefined;
  const key = show ? s.room.controlMode + "," + s.room.you.canModerate : "";
  if (key === requestLinkKey) return;
  requestLinkKey = key;
  el.hidden = !show;
  el.replaceChildren(...(show ? [linkPanel(s)] : []));
}

function linkPanel(s: RoomState): HTMLElement {
  const url = h("input", { name: "url", type: "url", required: true, placeholder: "https://",
    "aria-label": "Link to request", class: "grow" });
  const submit = h("button", { type: "submit", text: requestVerb(s) === "Suggest" ? "Suggest" : "Request" });
  const form = h("form", { class: "add-youtube request-link-form" }, url, submit);
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
  return h("div", {}, h("h2", { text: "Request Link" }), form, status);
}

// ------------------------------------------------------------------ wiring

function render(s: RoomState): void {
  shown = s;
  roomId = s.room.id;
  delayed.push(s.timeline, s.current, serverNow());
  renderHeader(s);
  renderIdentity();
  renderNowPlaying(s);
  renderRequests(s);
  renderRequestLink(s);
  // The viewer URL for everyone and the quick buttons here; the full outputs are in Manage Room.
  outputsPanel ??= new OutputsPanel(element("room-outputs"), element("rv-outputs"), false);
  outputsPanel.update(s);
  renderRemoteDelay();
  // The owner may force requests-only mode: then nothing plays here, whatever the user chose.
  const nowForced = forcedRequestsOnly(s.room.settings);
  if (nowForced !== forced) {
    forced = nowForced;
    setMode(effectiveMode(chosenMode, forced));
  }
  element("rv-grid").hidden = false;
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
  element("room-mode").textContent = modeLabel(m) + (forced ? " (set by the room)" : "");
  const toggle = element("room-mode-toggle") as HTMLButtonElement;
  toggle.textContent = m === "remote" ? "Listen here instead" : "Requests only (I'm listening elsewhere)";
  toggle.hidden = forced;
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

/** Tells the server the page's mode and switches to it. */
function setMode(m: RoomMode): void {
  if (m === mode) {
    applyMode(m); // the labels may still change (forced or not)
    return;
  }
  conn.mode = m;
  if (conn.joined) conn.request({ type: "mode", mode: m }).catch(showError);
  applyMode(m);
}

/** The toggle: remember the choice for this room, tell the server, switch. */
function switchMode(m: RoomMode): void {
  if (forced || m === mode) return;
  chosenMode = m;
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
  setMode(m);
  // The toggle click is a user gesture: let the new player play at once.
  if (m === "listen") player?.unlock();
}

function createPlayer(): ListenPlayer {
  const syncMode = syncModeFor(engineHints(), new URLSearchParams(PAGE_SEARCH).get("sync"));
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
  p.setVolume(listenerGain);
  p.setMuted(listenerMuted);
  // The user's Web Audio preference (user settings): applied when it loads or changes. A change made in
  // the settings view reaches here inside its change event, a user gesture: the rebuilt elements are
  // unlocked in it.
  let applied = loadWebAudioSetting();
  const unsubscribe = prefs.subscribe((pr, changed, byUser) => {
    if (changed.indexOf("webAudio") < 0 || pr.webAudio === applied) return;
    applied = pr.webAudio;
    // A deliberate choice starts over: Auto checks this browser again.
    if (byUser) storageSet(WEB_AUDIO_MEMORY_KEY, null);
    p.setWebAudioPlan(webAudioPlan(urlWebAudio(), applied, byUser ? undefined : loadWebAudioMemory(), canSetVolume()),
      byUser);
  });
  ROOM_SIGNAL.addEventListener("abort", unsubscribe);
  showSound();
  every(() => { if (mode === "listen") p.tick(); }, 250);
  // For checking by hand: togetherTunesPlayer.debug() shows source, gain, rate and drift.
  (window as unknown as { togetherTunesPlayer: ListenPlayer }).togetherTunesPlayer = p;
  return p;
}

/** Whether the room is audio-only (its `audioOnly` setting). */
function isAudioOnlyRoom(s: RoomState | undefined): boolean {
  return s?.room.settings["audioOnly"] === true;
}

/** The video / audio-only switch (the player bar's menu). */
function setAudioOnly(on: boolean): void {
  player?.setAudioOnly(on);
  saveAudioOnly(on);
  shell?.update();
}

/** Whether `ref` (an id or slug from a link) is this room. */
function isThisRoom(ref: string): boolean {
  return ref === roomId || ref === roomRef || (roomSlug !== "" && ref.toLowerCase() === roomSlug.toLowerCase());
}

function createShell(): RoomHandle {
  return attachRoom({
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
    id: () => roomId,
    state: () => shown,
    actor: () => conn.actor,
    ingest: () => conn.ingest,
    request: (msg) => conn.request(msg),
    serverNow,
    mode: () => mode,
    forcedRequestsOnly: () => forced,
    setRequestsOnly: (on) => switchMode(on ? "remote" : "listen"),
    setVolume: (gain, muted) => {
      listenerGain = gain;
      listenerMuted = muted;
      player?.setVolume(gain);
      player?.setMuted(muted);
    },
    playerContainer: () => document.getElementById("room-player") ?? undefined,
    reidentify,
  });
}

/**
 * Who is using the page changed (a login, docs/ui.md#single-page-app): the new identity, a join for a
 * user who now can be in rooms, and a new socket (the server identifies it from the cookie). The
 * player isn't touched: it keeps following the timeline, and the new socket's state continues it.
 */
async function reidentify(): Promise<void> {
  if (ROOM_SIGNAL.aborted || roomId === "" || (conn as RoomConnection | undefined) === undefined) return;
  me = await loadMe();
  if (ROOM_SIGNAL.aborted) return;
  if (shouldJoin(me, { id: roomId, alone: roomAlone })) {
    try {
      await api("POST", "/api/me/play-session/join", { room: roomId });
    } catch (e) {
      recordEvent("room", { joinFailed: String(e) });
    }
    if (ROOM_SIGNAL.aborted) return;
  }
  conn.actor = me.kind === "anonymous" ? undefined : { kind: me.kind, id: me.id, name: me.name, isGuest: me.isGuest };
  recordEvent("room", { reidentify: me.kind });
  conn.reconnect();
  renderIdentity();
  if (shell !== undefined && !shell.away) renderNav(me);
  shell?.update();
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
    webAudioSetting: loadWebAudioSetting(),
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


function onRequest(r: NewRequest): void {
  const via = { library: "from the library", upload: "by upload", youtube: "from YouTube",
    history: "from the history" }[r.via] ?? r.via;
  const node = h("span", {}, r.by !== undefined ? actorLabel(r.by) : "Someone", " requested ",
    h("a", { href: itemPath(r.item.id), text: r.item.title ?? "an item" }), " " + via + ".");
  notice("", node);
}

const KICK_TEXT: Record<string, string> = {
  banned_from_room: "You are banned from this room.",
  timed_out: "You are timed out in this room.",
  room_not_found: "This room is no longer available to you.",
  room_deleted: "This room was deleted.",
  session_ended: "Your session ended. Log in again to rejoin.",
};

function notFound(): void {
  element("room-body").replaceChildren(h("h1", { text: "Not found" }),
    h("p", { text: "There is no room at this link." }));
}

async function start(): Promise<void> {
  // A room view started: whatever played before has stopped (no "stopped" note here).
  rememberPlaying(null);
  if (ROOM_SIGNAL.aborted) return;
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
  roomAlone = room.alone === true;
  shell = createShell();
  if (!shell.away) document.title = room.name + " · TogetherTunes";
  if (shouldJoin(me, room)) {
    // Opening a room joins it (docs/rooms.md#alone): coming from the alone state saves it. The room
    // plays here either way; a refusal (a ban) shows up on the socket.
    try {
      await api("POST", "/api/me/play-session/join", { room: room.id });
    } catch (e) {
      recordEvent("room", { joinFailed: String(e) });
    }
    if (ROOM_SIGNAL.aborted) return;
  }
  element("room-name").textContent = room.name;
  mode = chooseMode(modeFromLocation(), storageGet(modeKey(room.id)));
  forced = forcedRequestsOnly(room.settings);
  chosenMode = mode;
  mode = effectiveMode(chosenMode, forced);
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
      element("rv-grid").hidden = true;
      const status = element("room-status");
      status.hidden = false;
      status.textContent = kickedText;
      // Last: the shell may take the view away (a deleted room the user was in).
      shell?.gone(reason);
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
  // The debug-mode "Send diagnostics" button (frame.ts) sends this room's report while the view lives.
  const dropReport = setReportSource(diagnosticsReport);
  ROOM_SIGNAL.addEventListener("abort", dropReport);
  element("room-delay-less").addEventListener("click", () => setNudge(stepNudge(nudge, -1, remoteDelays(shown).outputMs)));
  element("room-delay-more").addEventListener("click", () => setNudge(stepNudge(nudge, 1, remoteDelays(shown).outputMs)));
  element("room-delay-reset").addEventListener("click", () => setNudge(0));
  applyMode(mode);
  // The client's clock may have stepped while the device slept: measure again at once.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") c.syncNow();
  }, { signal: ROOM_SIGNAL });
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
  }, { signal: ROOM_SIGNAL });
  window.addEventListener("pageshow", (ev) => {
    if (ev.persisted) location.reload();
  }, { signal: ROOM_SIGNAL });
  every(tick, 250);
  // Taken away by the shell (another room, Leave, a view shown while not playing): leave the room.
  const leave = (): void => {
    conn.stop();
    clock?.stop();
    player?.dispose();
  };
  if (ROOM_SIGNAL.aborted) leave();
  else ROOM_SIGNAL.addEventListener("abort", leave);
}

onReady(start);
