// Files dropped on a playlist (docs/ui.md#library-rules, "Drag and drop"): each file is uploaded
// (uploads.ts: the SHA-256 pre-check on https servers, then the chunks) and added to the playlist,
// which also puts it into the library. Videos need a type: one video asks for its type, several
// get one type for all of them; either may be left uncategorized. The question is asked at once,
// while the pre-check and the upload already run in the background; an upload waits for the answer
// only before its `complete`. Cancel stops them all. A small box at the bottom of the page shows
// how each file is doing. Rules (which files are videos, the lines' text) in the DOM-free dragdrop.ts.
import { api } from "./api.js";
import { CLASSIFY_TYPES, needsClassification, type ClassifyBody } from "./classify.js";
import { askClassification, askType } from "./classifyprompt.js";
import { h } from "./dom.js";
import { fileDropQuestion, isVideoFile, uploadLine } from "./dragdrop.js";
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
  const lines = h("ul", { class: "drop-upload-lines" });
  const close = h("button", { type: "button", class: "icon", text: "×", title: "Close", "aria-label": "Close" });
  const box = h("section", { class: "drop-uploads", role: "status", "aria-label": "Uploads" },
    h("div", { class: "row" }, h("strong", { class: "grow", text: "Uploading to " + t.playlistName }), close),
    lines);
  close.hidden = true;
  close.addEventListener("click", () => box.remove());
  document.body.append(box);
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
