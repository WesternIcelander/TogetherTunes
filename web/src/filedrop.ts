// Files dropped on a playlist (docs/ui.md#library-rules, "Drag and drop"): each file is uploaded
// (uploads.ts: the SHA-256 pre-check on https servers, then the chunks) and added to the playlist,
// which also puts it into the library. Videos need a type: one video asks for its type, several
// get one type for all of them; either may be left uncategorized. The question is asked at once,
// while the pre-check and the upload already run in the background; an upload waits for the answer
// only before its `complete`. Cancel stops them all. A small box at the bottom of the page shows
// how each file is doing. Rules (which files are videos, the lines' text) in the DOM-free dragdrop.ts.
// Files dropped on a room's queue (dropFilesOnQueue) are uploaded as requests in the room and then
// requested (queue.add {uploadId}); no type question: the server puts a signed-in user's request into
// their library, a video of unknown type as uncategorized.
// Files dropped on All My Songs, Movies, Shows, Videos or Uncategorized Items (dropFilesInLibrary) are
// uploaded into the library with the target's media type (dragdrop.ts libraryDropPlan; the server
// applies it only where the item needs one); Shows first asks for each file's show, season and
// episode (episodedrop.ts), while the files are already uploading.
import { api } from "./api.js";
import { CLASSIFY_TYPES, needsClassification, type ClassifyBody } from "./classify.js";
import { askClassification, askType } from "./classifyprompt.js";
import { h } from "./dom.js";
import { fileDropQuestion, isMediaFile, isVideoFile, libraryDropPlan, libraryTargetName, uploadLine,
  type LibraryTarget } from "./dragdrop.js";
import { commonShowName, episodeBodies, episodeRows, fillShowName, type EpisodeRow } from "./episodedrop.js";
import { refreshLibrary } from "./nav.js";
import { message } from "./session.js";
import { Upload, type Completed } from "./uploads.js";

export interface FileDropTarget {
  playlistId: string;
  playlistName: string;
  /** Where in the playlist (an entry id); null or undefined: the end. */
  beforeEntryId?: string | null;
}

/** The type question for the dropped files; undefined when none of them is a video. */
function ask(files: readonly File[]): Promise<ClassifyBody | null | undefined> {
  const q = fileDropQuestion(files);
  if (q.ask === "none") return Promise.resolve(undefined);
  if (q.ask === "one") {
    return askType({
      question: (q.name ?? "The video") + ": what kind of video is this? It is uploading meanwhile.",
      allowLeave: true,
      submitText: "Add",
    });
  }
  return askType({
    question: q.videos + " videos: what kind are they? They all get this type (show episodes: drop them one at a time,"
      + " or set them on each item's page later). They are uploading meanwhile.",
    allowLeave: true,
    types: CLASSIFY_TYPES.filter((t) => t !== "show"),
    submitText: "Add",
  });
}

/** Adds a finished upload's item to the playlist (asking for a type if the server still wants one). */
async function addToPlaylist(t: FileDropTarget, file: File, done: Completed, type: ClassifyBody | undefined):
    Promise<boolean> {
  const path = "/api/playlists/" + encodeURIComponent(t.playlistId) + "/entries";
  const body: Record<string, unknown> = { itemId: done.itemId };
  if (t.beforeEntryId !== undefined && t.beforeEntryId !== null) body["beforeEntryId"] = t.beforeEntryId;
  try {
    await api("POST", path, type !== undefined && isVideoFile(file) ? { ...body, ...type } : body);
    return true;
  } catch (e) {
    const needed = needsClassification(e);
    if (needed === undefined) throw e;
    const answer = await askClassification(needed, file.name, true);
    if (answer === null) return false;
    await api("POST", path, { ...body, ...answer });
    return true;
  }
}

/**
 * Uploads `files` into the playlist. `report` gets a summary line when all are through (and
 * errors as they happen); `changed` is called once something was added.
 */
export function dropFiles(files: readonly File[], t: FileDropTarget, report: (text: string, error?: boolean) => void,
    changed: () => void): void {
  if (files.length === 0) return;
  const answer = ask(files);
  const { box, lines, close } = uploadBox("Uploading to " + t.playlistName);
  const uploads = files.map((file) => {
    const text = h("span", { class: "muted small", text: "Waiting…" });
    lines.append(h("li", {}, h("span", { class: "drop-upload-name", text: file.name }), " ", text));
    let result: Completed | undefined;
    let error: string | undefined;
    const u = new Upload(file, (p) => {
      text.textContent = uploadLine(p.state, p.sentBytes, p.size, p.checked)
        + (p.error !== undefined ? ": " + p.error : "");
      if (p.result !== undefined) result = p.result;
      if (p.state === "failed") error = p.error;
    });
    u.addToLibrary = true;
    u.beforeComplete = async () => {
      const a = await answer;
      if (a === null) return null;
      return a !== undefined && isVideoFile(file) ? { ...a } : {};
    };
    return { file, u, text, result: () => result, error: () => error };
  });
  void (async () => {
    // Cancel in the type question stops every upload (also those still waiting or hashing).
    let cancelled = false;
    void answer.then((a) => {
      if (a !== null) return;
      cancelled = true;
      for (const x of uploads) x.u.cancel();
    });
    let added = 0;
    const failed: string[] = [];
    for (const x of uploads) {
      if (cancelled) break;
      await x.u.start();
      const done = x.result();
      if (x.u.state !== "done" || done === undefined) {
        if (cancelled) break;
        failed.push(x.file.name + ": " + (x.error() ?? "stopped"));
        continue;
      }
      try {
        const type = await answer;
        if (type === null) break;
        if (await addToPlaylist(t, x.file, done, type)) {
          added++;
          x.text.textContent = "Added";
          changed();
        } else {
          x.text.textContent = "Uploaded, not added (no type chosen)";
        }
      } catch (e) {
        x.text.textContent = "Not added: " + message(e);
        failed.push(x.file.name + ": " + message(e));
      }
    }
    close.hidden = false;
    if (cancelled) {
      box.remove();
      report("Upload cancelled.");
      return;
    }
    refreshLibrary();
    report((added === 1 ? "1 file" : added + " files") + " added to " + t.playlistName + "."
      + (failed.length > 0 ? " Not added: " + failed.join("; ") : ""), failed.length > 0);
    if (failed.length === 0) window.setTimeout(() => box.remove(), 4000);
  })();
}

/**
 * Uploads `files` (audio and video only; others are skipped) as requests in room `roomId` and
 * requests each once it is uploaded (`request`: `queue.add {uploadId}`), in the background, with the
 * same box as playlist drops. No type question (the server adds a signed-in user's request to their
 * library as it is). `report` gets a summary line when all are through.
 */
export function dropFilesOnQueue(files: readonly File[], roomId: string,
    request: (msg: Record<string, unknown>) => Promise<Record<string, unknown>>,
    report: (text: string, error?: boolean) => void): void {
  const media = files.filter(isMediaFile);
  const skipped = files.filter((f) => !isMediaFile(f)).map((f) => f.name + ": not an audio or video file");
  if (media.length === 0) {
    if (skipped.length > 0) report("Not requested: " + skipped.join("; "), true);
    return;
  }
  const { box, lines, close } = uploadBox("Uploading requests");
  const uploads = media.map((file) => {
    const text = h("span", { class: "muted small", text: "Waiting…" });
    lines.append(h("li", {}, h("span", { class: "drop-upload-name", text: file.name }), " ", text));
    let error: string | undefined;
    const u = new Upload(file, (p) => {
      text.textContent = uploadLine(p.state, p.sentBytes, p.size, p.checked)
        + (p.error !== undefined ? ": " + p.error : "");
      if (p.state === "failed") error = p.error;
    });
    // The request (queue.add) puts it into the library; complete itself doesn't (no type question).
    u.addToLibrary = false;
    u.roomId = roomId;
    return { file, u, text, error: () => error };
  });
  void (async () => {
    let requested = 0;
    const failed: string[] = [...skipped];
    for (const x of uploads) {
      await x.u.start();
      if (x.u.state !== "done" || x.u.uploadId === undefined) {
        failed.push(x.file.name + ": " + (x.error() ?? "stopped"));
        continue;
      }
      try {
        const ack = await request({ type: "queue.add", uploadId: x.u.uploadId });
        requested++;
        x.text.textContent = ack["state"] === "suggested" ? "Suggested" : "Requested";
      } catch (e) {
        x.text.textContent = "Not requested: " + message(e);
        failed.push(x.file.name + ": " + message(e));
      }
    }
    close.hidden = false;
    refreshLibrary();
    report((requested === 1 ? "1 file" : requested + " files") + " requested."
      + (failed.length > 0 ? " Not requested: " + failed.join("; ") : ""), failed.length > 0);
    if (failed.length === 0) window.setTimeout(() => box.remove(), 4000);
  })();
}

// ------------------------------------------------------------------ the library views

/** A library view as a drop target; `showName`: the show open in the Shows list (prefills the dialog). */
export interface LibraryDropTarget {
  target: LibraryTarget;
  showName?: string;
}

/**
 * The Shows dialog: one row per video file with show name, season and episode (prefilled from the
 * file name), a "show name for all" field, Add and Cancel. Resolves with the classify bodies in the
 * files' order, or null when cancelled. The files upload meanwhile.
 */
function askEpisodes(files: readonly File[], fallbackName: string | undefined):
    Promise<{ mediaType: "show"; show: { name: string; season?: number; episode: number } }[] | null> {
  return new Promise((resolve) => {
    let rows: EpisodeRow[] = episodeRows(files.map((f) => f.name), fallbackName);
    const inputs = rows.map((r, i) => {
      const name = h("input", { name: "showName" + i, maxlength: "300", value: r.showName, "aria-label": "Show name" });
      const season = h("input", { name: "season" + i, inputmode: "numeric", value: r.season, placeholder: "none",
        "aria-label": "Season", size: "4" });
      const episode = h("input", { name: "episode" + i, inputmode: "numeric", value: r.episode, "aria-label": "Episode",
        size: "4" });
      return { name, season, episode };
    });
    const read = (): EpisodeRow[] => rows.map((r, i) => {
      const x = inputs[i];
      return x === undefined ? r : { fileName: r.fileName, showName: x.name.value, season: x.season.value,
        episode: x.episode.value };
    });
    const all = h("input", { name: "showNameAll", maxlength: "300", value: commonShowName(rows) });
    all.addEventListener("input", () => {
      rows = fillShowName(read(), all.value);
      rows.forEach((r, i) => { const x = inputs[i]; if (x !== undefined) x.name.value = r.showName; });
    });
    const table = h("table", { class: "episode-rows" },
      h("thead", {}, h("tr", {}, h("th", { text: "File" }), h("th", { text: "Show name" }), h("th", { text: "Season" }),
        h("th", { text: "Episode" }))),
      h("tbody", {}, ...rows.map((r, i) => {
        const x = inputs[i] as { name: HTMLInputElement; season: HTMLInputElement; episode: HTMLInputElement };
        return h("tr", {}, h("td", { class: "small episode-file", text: r.fileName }), h("td", {}, x.name),
          h("td", {}, x.season), h("td", {}, x.episode));
      })));
    const error = h("p", { class: "error", role: "alert" });
    const add = h("button", { type: "submit", text: "Add" });
    const cancel = h("button", { type: "button", text: "Cancel" });
    const form = h("form", { class: "form episodes-form", role: "dialog", "aria-modal": "true", "aria-label": "Show episodes" },
      h("h2", { text: "Show episodes" }),
      h("p", { text: (files.length === 1 ? "Which show and episode is this? It is" : "Which show and episode is each file?"
        + " They are") + " uploading meanwhile." }),
      files.length > 1 ? h("label", {}, "Show name for all", all) : null,
      table,
      error,
      h("div", { class: "row" }, add, cancel));
    const overlay = h("div", { class: "classify-prompt" }, form);
    const close = (value: { mediaType: "show"; show: { name: string; season?: number; episode: number } }[] | null):
        void => {
      overlay.remove();
      resolve(value);
    };
    cancel.addEventListener("click", () => close(null));
    overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(null); });
    form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      rows = read();
      const r = episodeBodies(rows);
      if ("error" in r) error.textContent = r.error;
      else close(r.bodies);
    });
    document.body.append(overlay);
    // The first field that still needs something (else Add).
    const missing = inputs.find((x) => x.name.value.trim() === "" || x.episode.value.trim() === "");
    if (missing === undefined) add.focus();
    else if (missing.name.value.trim() === "") (files.length > 1 && all.value === "" ? all : missing.name).focus();
    else missing.episode.focus();
  });
}

/** The box at the bottom of the page that shows how each upload is doing. */
function uploadBox(title: string): { box: HTMLElement; lines: HTMLElement; close: HTMLButtonElement } {
  const lines = h("ul", { class: "drop-upload-lines" });
  const close = h("button", { type: "button", class: "icon", text: "×", title: "Close", "aria-label": "Close" });
  const box = h("section", { class: "drop-uploads", role: "status", "aria-label": "Uploads" },
    h("div", { class: "row" }, h("strong", { class: "grow", text: title }), close), lines);
  close.hidden = true;
  close.addEventListener("click", () => box.remove());
  document.body.append(box);
  return { box, lines, close };
}

/**
 * Uploads `files` into the caller's library with the media type of the view they were dropped on
 * (docs/ui.md#library-rules): All My Songs audio → audio, video → music video; Movies, Shows and
 * Videos their type (video files only: audio is skipped and named); Uncategorized Items left
 * uncategorized. The server applies the type only where the item needs one (a new upload, or an
 * uncategorized video not yet in the library). Shows asks for the episodes first (the uploads start
 * at once and wait for the answer before `complete`; Cancel stops them). `report` gets a summary
 * line when all are through; `changed` is called once something was added.
 */
export function dropFilesInLibrary(files: readonly File[], t: LibraryDropTarget,
    report: (text: string, error?: boolean) => void, changed: () => void): void {
  const name = libraryTargetName(t.target);
  const plan = libraryDropPlan(t.target, files);
  if (plan.uploads.length === 0) {
    if (plan.skipped.length > 0) report("Not added: " + plan.skipped.join("; "), true);
    return;
  }
  const episodes = t.target === "shows" ? askEpisodes(plan.uploads.map((u) => u.file), t.showName) : undefined;
  const { box, lines, close } = uploadBox("Uploading to " + name);
  const uploads = plan.uploads.map((p, i) => {
    const text = h("span", { class: "muted small", text: "Waiting…" });
    lines.append(h("li", {}, h("span", { class: "drop-upload-name", text: p.file.name }), " ", text));
    let result: Completed | undefined;
    let error: string | undefined;
    const u = new Upload(p.file, (pr) => {
      text.textContent = uploadLine(pr.state, pr.sentBytes, pr.size, pr.checked)
        + (pr.error !== undefined ? ": " + pr.error : "");
      if (pr.result !== undefined) result = pr.result;
      if (pr.state === "failed") error = pr.error;
    });
    u.addToLibrary = true;
    const body = async (): Promise<Record<string, unknown> | null> => {
      if (episodes === undefined) return { mediaType: p.mediaType };
      const all = await episodes;
      return all === null ? null : { ...all[i] };
    };
    u.beforeComplete = body;
    return { file: p.file, u, text, body, result: () => result, error: () => error };
  });
  void (async () => {
    let cancelled = false;
    void episodes?.then((a) => {
      if (a !== null) return;
      cancelled = true;
      for (const x of uploads) x.u.cancel();
    });
    let added = 0;
    const failed: string[] = [...plan.skipped];
    for (const x of uploads) {
      if (cancelled) break;
      await x.u.start();
      const done = x.result();
      if (x.u.state !== "done" || done === undefined) {
        if (cancelled) break;
        failed.push(x.file.name + ": " + (x.error() ?? "stopped"));
        continue;
      }
      try {
        // A file the server already had (the pre-check) and that still needs a type: add it with ours.
        if (done.classify !== undefined) {
          const type = await x.body();
          if (type === null) break;
          await api("PUT", "/api/library/" + encodeURIComponent(done.itemId), type);
        }
        added++;
        x.text.textContent = "Added";
        changed();
      } catch (e) {
        x.text.textContent = "Not added: " + message(e);
        failed.push(x.file.name + ": " + message(e));
      }
    }
    close.hidden = false;
    if (cancelled) {
      box.remove();
      report("Upload cancelled.");
      return;
    }
    refreshLibrary();
    report((added === 1 ? "1 file" : added + " files") + " added to " + name + "."
      + (failed.length > 0 ? " Not added: " + failed.join("; ") : ""), failed.length > 0);
    if (failed.length === 0) window.setTimeout(() => box.remove(), 4000);
  })();
}
