// The item page (/items/{id}, a direct link anyone may open; docs/libraries.md#visibility):
// metadata, cover, a player once the item is ready, the ingest job's status (and log, for those
// who may edit), and for users: add to / remove from the library, add to a playlist, share, edit
// the metadata, purge.
import { api, ApiError } from "./api.js";
import { button, formatDuration, formatTime, h } from "./dom.js";
import {
  jobSummary, kindLabel, mediaPath, playlistPicker, statusBadge, watchItem, type ItemPage, type JobView,
} from "./media.js";
import { checkPasswordChange, element, loadMe, message, onReady, renderNav, type Me } from "./session.js";

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
  const cover = h("img", { class: "cover", alt: "", src: mediaPath(item.id, "cover.jpg", item.mediaVersion) });
  cover.addEventListener("error", () => { cover.hidden = true; });
  const badge = statusBadge(item.status, job?.progress);
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
      me.kind === "user" && item.status !== "failed" ? shareForm(item) : null));
}

async function refresh(): Promise<void> {
  stopWatching?.();
  await new Promise<void>((resolve) => {
    let first = true;
    stopWatching = watchItem(itemId, ({ item, job }) => {
      render(item, job);
      if (first) {
        first = false;
        renderForms(item, job);
        resolve();
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
