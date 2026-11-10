// The item page (/items/{id}, a direct link anyone may open; docs/libraries.md#visibility):
// metadata, cover, a player once the item is ready, the ingest job's status (and log, for those
// who may edit), and for users: add to / remove from the library, add to a playlist, share, edit
// the metadata, purge.
import { api, ApiError } from "./api.js";
import { button, formatDuration, formatTime, h } from "./dom.js";
import {
  ingestBadge, jobSummary, kindLabel, mediaPath, playlistPicker, watchItem, type ItemPage, type JobView,
} from "./media.js";
import { checkPasswordChange, element, loadMe, message, onReady, renderNav, type Me } from "./session.js";
import {
  audioEstimate, audioRange, audioTrimBody, currentRange, estimate, estimateText, formatTime as formatTrimTime,
  fractionToMs, moveEnd, moveStart, msToFraction, parseTime, playerView, playheadMs, previewSpan, trimBody, type Range,
  type TrimEstimate, type TrimView,
} from "./trim.js";

let me: Me;
let itemId = "";
let stopWatching: (() => void) | undefined;
/** The media element is kept across re-renders so playback isn't interrupted. */
let player: HTMLMediaElement | undefined;
let playerFor = "";
/** Whether the job log is expanded (undefined: the default, open for a failed item). */
let logOpen: boolean | undefined;

function showError(e: unknown): void {
  element("item-error").textContent = message(e);
}

function showNotice(text: string): void {
  const notice = element("item-notice");
  notice.textContent = text;
  notice.hidden = text === "";
}

/** The id from `/items/{id}` (or `?id=` when the page is opened as a plain file). */
function idFromLocation(): string {
  const m = /^\/items\/([^/]+)\/?$/.exec(location.pathname);
  if (m !== null && m[1] !== undefined) {
    try {
      return decodeURIComponent(m[1]);
    } catch {
      return "";
    }
  }
  return new URLSearchParams(location.search).get("id") ?? "";
}

/** "14:32" today, else the date and time: when a change that can be undone was made. */
function changeTime(ms: number, now: Date = new Date()): string {
  const d = new Date(ms);
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  return d.toDateString() === now.toDateString()
    ? time
    : d.toLocaleDateString([], { month: "short", day: "numeric" }) + " " + time;
}

/** The given nodes without the nulls. */
function nodes(...list: (Node | null)[]): Node[] {
  return list.filter((n): n is Node => n !== null);
}

function field(label: string, value: Node | string | undefined): HTMLElement | null {
  if (value === undefined || value === "") return null;
  return h("div", { class: "field" }, h("dt", { text: label }), h("dd", {}, value));
}

function renderPlayer(item: ItemPage): HTMLElement | null {
  if (item.status !== "ready") {
    player = undefined;
    playerFor = "";
    return null;
  }
  const video = item.kind === "video" && !item.presentAsAudio;
  // The version's URL: a re-encode published while this plays doesn't change the bytes under it.
  const src = mediaPath(item.id, video ? "mezz.mp4" : "audio.m4a", item.mediaVersion);
  // A newer version only replaces a player that isn't playing; one in mid-play finishes on its version.
  const sameKind = player !== undefined && (player instanceof HTMLVideoElement) === video;
  if (player === undefined || (playerFor !== src && !(sameKind && !player.paused))) {
    player = video
      ? h("video", { controls: true, preload: "metadata", playsinline: true, src })
      : h("audio", { controls: true, preload: "none", src });
    playerFor = src;
  }
  return h("div", { class: "player" }, player);
}

function renderJob(job: JobView | undefined, item: ItemPage): HTMLElement | null {
  if (job === undefined) return null;
  const running = job.state === "running" || job.state === "queued";
  const box = h("section", { class: "panel" }, h("h2", { text: "Processing" }),
    h("p", {}, jobSummary(job)),
    running ? h("progress", { max: "1", value: String(job.progress) }) : null,
    h("p", { class: "muted small" }, "Queued " + formatTime(job.createdAt)
      + (job.startedAt !== undefined ? " · started " + formatTime(job.startedAt) : "")
      + (job.finishedAt !== undefined ? " · finished " + formatTime(job.finishedAt) : "")));
  if (job.log !== undefined && job.log !== "") {
    const details = h("details", {}, h("summary", { text: "Log" }), h("pre", { class: "job-log", text: job.log }));
    details.open = logOpen ?? item.status === "failed";
    details.addEventListener("toggle", () => { logOpen = details.open; });
    box.append(details);
  }
  // A finished job of a ready item is only of interest to those who may see the log.
  if (!running && item.status === "ready" && job.log === undefined) return null;
  return box;
}

function editForm(item: ItemPage, onSaved: (item: ItemPage) => void): HTMLElement {
  const form = h("form", { class: "form" },
    h("label", {}, "Title", h("input", { name: "title", required: true, maxlength: "300", value: item.title })),
    h("label", {}, "Artist", h("input", { name: "artist", maxlength: "300", value: item.artist ?? "" })),
    h("label", {}, "Album", h("input", { name: "album", maxlength: "300", value: item.album ?? "" })),
    item.kind === "video"
      ? h("label", { class: "check" }, h("input", { name: "presentAsAudio", type: "checkbox", checked: item.presentAsAudio }),
        " Play as audio (show the card instead of the video)")
      : null,
    h("button", { type: "submit", text: "Save" }));
  const error = h("p", { class: "error", role: "alert" });
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    error.textContent = "";
    const data = new FormData(form);
    const body: Record<string, unknown> = {
      title: String(data.get("title") ?? ""),
      artist: String(data.get("artist") ?? ""),
      album: String(data.get("album") ?? ""),
    };
    if (item.kind === "video") body["presentAsAudio"] = data.get("presentAsAudio") !== null;
    const submit = form.querySelector("button");
    if (submit !== null) submit.disabled = true;
    api<ItemPage>("PATCH", "/api/items/" + encodeURIComponent(item.id), body)
      .then((updated) => { showNotice("Saved."); onSaved(updated); })
      .catch((e: unknown) => { error.textContent = message(e); })
      .finally(() => { if (submit !== null) submit.disabled = false; });
  });
  return h("details", { class: "panel" }, h("summary", { text: "Edit details" }), form, error);
}

/** Stops the trim panels' playhead and preview timers when the panels are replaced. */
let stopTrimPanels: (() => void)[] = [];

/** What one trim timeline edits (the video trim, or an audio trim). */
interface TimelineSpec {
  title: string;
  note: string;
  /** The timeline's length. */
  view: TrimView;
  current: Range;
  /** The player's span on the original's timeline (`playerView`). */
  player: TrimView;
  estimate: (range: Range) => TrimEstimate;
  body: (range: Range) => unknown;
  /** Extra controls (e.g. "Follow the video trim"). */
  extra?: HTMLElement | null;
}

/**
 * A trim timeline (docs/library-and-ingest.md#trim): a timeline of the original with start and end
 * handles (they move freely, frame-accurate), the playhead, typed timestamps, what Save will do,
 * Preview (plays the selection from the current version, no encode) and Save (queues the trim job).
 */
function timelinePanel(item: ItemPage, spec: TimelineSpec): HTMLElement {
  const view = spec.view;
  let range: Range = spec.current;
  const selection = h("div", { class: "trim-selection" });
  const startHandle = h("div", { class: "trim-handle", role: "slider", tabindex: "0", "aria-label": "Start" });
  const endHandle = h("div", { class: "trim-handle", role: "slider", tabindex: "0", "aria-label": "End" });
  const playhead = h("div", { class: "trim-playhead" });
  const bar = h("div", { class: "trim-bar" }, selection, playhead, startHandle, endHandle);
  const startInput = h("input", { name: "start", inputmode: "decimal", size: "10", "aria-label": "Start (m:ss.mmm)" });
  const endInput = h("input", { name: "end", inputmode: "decimal", size: "10", "aria-label": "End (m:ss.mmm)" });
  const hint = h("p", { class: "muted small" });
  const error = h("p", { class: "error", role: "alert" });
  const save = h("button", { type: "button", text: "Save" });

  const draw = (): void => {
    const a = msToFraction(range.startMs, view) * 100;
    const b = msToFraction(range.endMs, view) * 100;
    startHandle.style.left = a + "%";
    endHandle.style.left = b + "%";
    selection.style.left = a + "%";
    selection.style.width = Math.max(0, b - a) + "%";
    startInput.value = formatTrimTime(range.startMs);
    endInput.value = formatTrimTime(range.endMs);
    const e = spec.estimate(range);
    hint.textContent = estimateText(e);
    save.disabled = e.kind === "none";
  };
  const fractionAt = (clientX: number): number => {
    const box = bar.getBoundingClientRect();
    return box.width <= 0 ? 0 : (clientX - box.left) / box.width;
  };
  const drag = (handle: HTMLElement, move: (ms: number) => void): void => {
    handle.addEventListener("pointerdown", (ev) => {
      ev.preventDefault();
      handle.setPointerCapture(ev.pointerId);
      const onMove = (e: PointerEvent): void => { move(fractionToMs(fractionAt(e.clientX), view)); draw(); };
      const onUp = (): void => {
        handle.removeEventListener("pointermove", onMove);
        handle.removeEventListener("pointerup", onUp);
        handle.removeEventListener("pointercancel", onUp);
      };
      handle.addEventListener("pointermove", onMove);
      handle.addEventListener("pointerup", onUp);
      handle.addEventListener("pointercancel", onUp);
    });
  };
  drag(startHandle, (ms) => { range = moveStart(range, ms); });
  drag(endHandle, (ms) => { range = moveEnd(range, ms, view); });
  // arrow keys: one frame (1/30 s)
  const step = (handle: "start" | "end", dir: number): void => {
    if (handle === "start") range = moveStart(range, range.startMs + dir * (1000 / 30));
    else range = moveEnd(range, range.endMs + dir * (1000 / 30), view);
    draw();
  };
  for (const [el, which] of [[startHandle, "start"], [endHandle, "end"]] as const) {
    el.addEventListener("keydown", (ev) => {
      if (ev.key === "ArrowLeft" || ev.key === "ArrowRight") {
        ev.preventDefault();
        step(which, ev.key === "ArrowLeft" ? -1 : 1);
      }
    });
  }
  const typed = (input: HTMLInputElement, apply: (ms: number) => void): void => {
    input.addEventListener("change", () => {
      const ms = parseTime(input.value);
      error.textContent = ms === undefined ? "Type a time as m:ss.mmm, e.g. 1:05.250." : "";
      if (ms !== undefined) apply(ms);
      draw();
    });
  };
  typed(startInput, (ms) => { range = moveStart(range, ms); });
  typed(endInput, (ms) => { range = moveEnd(range, ms, view); });

  // the playhead follows the player (its time is on the current version)
  let previewEnd: number | undefined;
  const timer = window.setInterval(() => {
    const p = player;
    if (p === undefined) return;
    playhead.style.left = msToFraction(playheadMs(spec.player, p.currentTime), view) * 100 + "%";
    if (previewEnd !== undefined && p.currentTime >= previewEnd) {
      p.pause();
      previewEnd = undefined;
    }
  }, 100);
  stopTrimPanels.push(() => window.clearInterval(timer));

  const preview = button("Preview", async () => {
    const span = previewSpan(spec.player, range);
    const p = player;
    if (span === undefined || p === undefined) {
      error.textContent = "The current version has none of the selection to preview; Save makes it.";
      return;
    }
    error.textContent = "";
    p.currentTime = span.fromS;
    previewEnd = span.toS;
    await p.play();
  }, (e) => { error.textContent = message(e); });
  save.addEventListener("click", () => {
    error.textContent = "";
    save.disabled = true;
    postTrim(item, spec.body(range)).catch((e: unknown) => { error.textContent = message(e); save.disabled = false; });
  });

  draw();
  return h("details", { class: "panel" }, h("summary", { text: spec.title }),
    h("p", { class: "muted small", text: (spec.note === "" ? "" : spec.note + " ") + "Times are on the original file (" + formatTrimTime(view.originalMs) + " long)." }),
    bar,
    h("div", { class: "row" },
      h("label", {}, "Start ", startInput), h("label", {}, "End ", endInput)),
    hint,
    h("div", { class: "row" }, preview, save, spec.extra ?? null),
    error);
}

async function postTrim(item: ItemPage, body: unknown): Promise<void> {
  await api<ItemPage>("POST", "/api/items/" + encodeURIComponent(item.id) + "/trim", body);
  showNotice("Trim saved: the item is being encoded. It keeps playing the current version until then.");
  await refresh();
}

/**
 * The trim panels: for a video, "Trim video" and the audio-only rooms' range (docs/library-and-ingest.md#audio-trim);
 * for an audio item, "Trim audio".
 */
function trimPanels(item: ItemPage, view: TrimView): HTMLElement[] {
  for (const stop of stopTrimPanels) stop();
  stopTrimPanels = [];
  const video = item.kind === "video";
  const audioPanel = timelinePanel(item, {
    title: video ? "Trim audio for audio-only rooms" : "Trim audio",
    note: video
      ? "Audio-only rooms play this range of the video's audio (" + (view.audioStartMs === undefined
        ? "now the same as the video" : "now " + formatTrimTime(view.audioOnlyMs ?? 0) + " long")
        + "); other rooms play the video's own audio."
      : "The audio is encoded again from the original for this range; the original is kept.",
    view: { ...view, videoStartMs: 0 },
    current: audioRange(view, video),
    player: playerView(view, video),
    estimate: (range) => audioEstimate(view, video, range),
    body: (range) => audioTrimBody(view, range),
    extra: view.audioStartMs !== undefined
      ? button(video ? "Follow the video trim" : "Remove the trim",
        async () => { await postTrim(item, audioTrimBody(view, undefined)); }, (e) => { showNotice(message(e)); })
      : null,
  });
  if (!video) return [audioPanel];
  return [timelinePanel(item, {
    title: "Trim video",
    note: "",
    view,
    current: currentRange(view),
    player: view,
    estimate: (range) => estimate(view, range),
    body: (range) => trimBody(view, range),
  }), audioPanel];
}

function shareForm(item: ItemPage): HTMLElement {
  const form = h("form", { class: "form" },
    h("label", {}, "Username", h("input", { name: "toUsername", required: true, autocomplete: "off", maxlength: "64" })),
    h("label", {}, "Message (optional)", h("textarea", { name: "message", maxlength: "500", rows: "2" })),
    h("button", { type: "submit", text: "Share" }));
  const error = h("p", { class: "error", role: "alert" });
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    error.textContent = "";
    const data = new FormData(form);
    const text = String(data.get("message") ?? "").trim();
    const body: Record<string, unknown> = { itemId: item.id, toUsername: String(data.get("toUsername") ?? "").trim() };
    if (text !== "") body["message"] = text;
    const submit = form.querySelector("button");
    if (submit !== null) submit.disabled = true;
    api("POST", "/api/shares", body)
      .then(() => { showNotice("Shared with " + String(body["toUsername"]) + "."); form.reset(); })
      .catch((e: unknown) => { error.textContent = message(e); })
      .finally(() => { if (submit !== null) submit.disabled = false; });
  });
  return h("details", { class: "panel" }, h("summary", { text: "Share with someone" }), form, error);
}

function render(item: ItemPage, job: JobView | undefined): void {
  document.title = item.title + " · TogetherTunes";
  const body = element("item-body");
  // Only ask for a cover the item has (not every item has one).
  const cover = h("img", { class: "cover", alt: "" });
  cover.hidden = !item.hasCover;
  if (item.hasCover) cover.setAttribute("src", mediaPath(item.id, "cover.jpg", item.mediaVersion));
  cover.addEventListener("error", () => { cover.hidden = true; });
  const badge = ingestBadge(item.status, job);
  const source = item.source === "youtube" && item.youtubeId !== undefined
    ? h("a", { href: "https://www.youtube.com/watch?v=" + encodeURIComponent(item.youtubeId), rel: "noopener noreferrer",
      target: "_blank", text: "YouTube" })
    : "Upload";

  const actions = h("div", { class: "row item-actions" });
  if (me.kind === "user") {
    if (item.you.inLibrary) {
      actions.append(button("Remove from my library", async () => {
        await api("DELETE", "/api/library/" + encodeURIComponent(item.id));
        showNotice("Removed from your library.");
        await refresh();
      }, showError));
    } else if (item.status !== "failed" || item.source === "youtube") {
      actions.append(button(item.status === "purged" && item.source === "youtube" ? "Fetch again and add to my library"
        : "Add to my library", async () => {
        await api("PUT", "/api/library/" + encodeURIComponent(item.id));
        showNotice("Added to your library.");
        await refresh();
      }, showError));
    }
    actions.append(playlistPicker(item.id, (text, e) => {
      if (e !== undefined) showError(e);
      else showNotice(text);
    }));
  }
  actions.append(button("Copy link", async () => {
    const url = location.origin + "/items/" + encodeURIComponent(item.id);
    try {
      await navigator.clipboard.writeText(url);
      showNotice("Link copied.");
    } catch {
      showNotice(url);
    }
  }, showError));
  if (item.you.canRetry === true) {
    // A YouTube fetch that failed is often temporary (network, throttling, an outdated yt-dlp).
    actions.append(button("Retry", async () => {
      await api("POST", "/api/items/" + encodeURIComponent(item.id) + "/retry");
      showNotice("Fetching it again.");
      await refresh();
    }, showError));
  }
  if (item.undo !== undefined) {
    // Goes back to the previous version of the media (kept for a while after a change); the change
    // undone is kept in turn, so the undo can itself be undone.
    actions.append(button("Undo the change from " + changeTime(item.undo.changedAt), async () => {
      await api("POST", "/api/items/" + encodeURIComponent(item.id) + "/undo");
      showNotice("Undone.");
      await refresh();
    }, showError));
  }
  if (item.you.canPurge && item.status !== "purged") {
    actions.append(button("Purge media", async () => {
      if (!confirm("Delete this item's media files from the server now? Its entry stays, as \"media expired\".")) return;
      await api("DELETE", "/api/items/" + encodeURIComponent(item.id));
      showNotice("Purged.");
      await refresh();
    }, showError, "danger"));
  }

  body.replaceChildren(...nodes(
    h("div", { class: "item-head" }, cover,
      h("div", {},
        h("h1", {}, item.title, badge !== null ? " " : "", badge),
        item.artist !== undefined ? h("p", { class: "item-artist", text: item.artist }) : null,
        h("dl", { class: "fields" },
          field("Album", item.album),
          field("Length", formatDuration(item.durationMs)),
          field("Kind", kindLabel(item)),
          field("Source", source),
          field("Added", formatTime(item.addedAt))))),
    item.status === "failed" && item.error !== undefined ? h("p", { class: "error", text: "Ingest failed: " + item.error }) : null,
    item.status === "failed" && item.source !== "youtube"
      ? h("p", { class: "muted", text: "A failed upload's file isn't kept, so it can't be retried: upload the file again." })
      : null,
    item.status === "purged"
      ? h("p", { class: "muted", text: item.source === "youtube"
          ? "Media expired. Adding it to your library fetches it from YouTube again."
          : "Media expired. Upload the file again to restore it." })
      : null,
    renderPlayer(item),
    actions,
    renderJob(job, item)));
}

/**
 * The edit and share forms, in their own container so the status polling (which re-renders the
 * rest) doesn't throw away what the user is typing.
 */
function renderForms(item: ItemPage, job: JobView | undefined): void {
  element("item-forms").replaceChildren(
    ...nodes(item.you.canEdit ? editForm(item, (updated) => { render(updated, job); renderForms(updated, job); }) : null,
      ...(item.trim !== undefined && !(job !== undefined && (job.state === "queued" || job.state === "running"))
        ? trimPanels(item, item.trim) : []),
      me.kind === "user" && item.status !== "failed" ? shareForm(item) : null));
}

function formsKeyOf(item: ItemPage, job: JobView | undefined): string {
  const busy = job !== undefined && (job.state === "queued" || job.state === "running");
  return JSON.stringify([item.mediaVersion, item.status, item.trim ?? null, busy]);
}

async function refresh(): Promise<void> {
  stopWatching?.();
  await new Promise<void>((resolve) => {
    let first = true;
    let formsKey = "";
    stopWatching = watchItem(itemId, ({ item, job }) => {
      render(item, job);
      // The forms are kept while polling (so typing isn't lost), but follow a finished trim job
      // or a new version: the trim panel shows again with the new settings.
      const key = formsKeyOf(item, job);
      if (first) {
        first = false;
        formsKey = key;
        renderForms(item, job);
        resolve();
      } else if (key !== formsKey) {
        formsKey = key;
        renderForms(item, job);
      }
    }, (e) => {
      if (first) {
        first = false;
        stopWatching?.();
        if (e instanceof ApiError && e.status === 404) {
          element("item-forms").replaceChildren();
          element("item-body").replaceChildren(h("h1", { text: "Not found" }),
            h("p", { text: "There is no item at this link." }));
        } else {
          showError(e);
        }
        resolve();
      }
    });
  });
}

async function start(): Promise<void> {
  try {
    me = await loadMe();
  } catch (e) {
    element("item-status").textContent = message(e);
    return;
  }
  if (!checkPasswordChange(me)) return;
  renderNav(me);
  itemId = idFromLocation();
  element("item-status").hidden = true;
  if (itemId === "") {
    element("item-body").replaceChildren(h("h1", { text: "Not found" }));
    return;
  }
  await refresh();
}

onReady(start);
