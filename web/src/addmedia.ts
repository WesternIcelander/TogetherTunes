// The "Add media" panel of the library page: file uploads (chunked, resumable; see uploads.ts)
// and YouTube imports, each followed by its ingest job's status until the item is ready.
import { ApiError } from "./api.js";
import { button, formatBytes, h } from "./dom.js";
import { ingestText, jobIngestStatus, JsonLines, lookupText } from "./jobtext.js";
import { inProgress, itemPath, statusBadge, watchItem, type ItemStatus } from "./media.js";
import { message } from "./session.js";
import { rememberedUploads, Upload, type UploadProgress } from "./uploads.js";

interface YoutubeResult { itemId: string; cached: boolean; status: ItemStatus }

export interface AddMediaOptions {
  canUpload: boolean;
  canYoutube: boolean;
  /** Called when an item was added (it may still be processing) and again once it is ready. */
  onItemChanged: () => void;
}

/**
 * A row in the activity list for one item: its status and job, polled until ingest ends.
 * `label` is what the user added (file name or link).
 */
function trackItem(list: HTMLElement, row: HTMLElement, label: string, itemId: string, onChanged: () => void): void {
  const status = h("span", { class: "muted" });
  const link = h("a", { href: itemPath(itemId), text: label });
  row.replaceChildren(h("div", { class: "activity-head" }, link, " ", status));
  if (!row.isConnected) list.prepend(row);
  let wasInProgress = true;
  watchItem(itemId, ({ item, job }) => {
    link.textContent = item.title;
    const parts: (Node | string)[] = [];
    if (item.status === "ready") parts.push("Ready");
    else if (inProgress(item.status)) parts.push(ingestText(job === undefined ? undefined : jobIngestStatus(job)));
    else if (item.status === "failed") parts.push(ingestText({ state: "failed", error: item.error }));
    else {
      const badge = statusBadge(item.status);
      if (badge !== null) parts.push(badge);
    }
    status.replaceChildren(...parts);
    const now = inProgress(item.status);
    if (wasInProgress && !now) onChanged();
    wasInProgress = now;
  }, (e) => { status.textContent = message(e); });
}

function progressBar(): HTMLProgressElement {
  return h("progress", { max: "1", value: "0" });
}

export function renderAddMedia(container: HTMLElement, options: AddMediaOptions): void {
  const activity = h("ul", { class: "activity", "aria-live": "polite" });
  const parts: Node[] = [];

  if (options.canUpload) {
    const input = h("input", { type: "file", multiple: true, accept: "audio/*,video/*", id: "upload-files" });
    const drop = h("label", { class: "dropzone", for: "upload-files" },
      h("strong", { text: "Upload files" }),
      h("span", { class: "muted", text: " Choose or drop audio or video files. Interrupted uploads continue when you pick the same file again." }));
    const addToLibrary = h("input", { type: "checkbox", checked: true });
    const remembered = rememberedUploads();
    const reminder = h("p", { class: "muted small" });
    if (remembered.length > 0) {
      reminder.textContent = "Unfinished uploads in this browser: " + remembered.join(", ")
        + ". Pick the same file again to continue.";
    }
    reminder.hidden = remembered.length === 0;

    const startFile = (file: File): void => {
      const row = h("li", { class: "activity-row" });
      const bar = progressBar();
      const text = h("span", { class: "muted small" });
      const controls = h("span", { class: "row" });
      let upload: Upload;
      const show = (p: UploadProgress): void => {
        bar.value = p.size > 0 ? p.sentBytes / p.size : 1;
        const amount = formatBytes(p.sentBytes) + " of " + formatBytes(p.size);
        switch (p.state) {
          case "starting": text.textContent = "Starting…"; break;
          case "uploading": text.textContent = "Uploading " + amount; break;
          case "retrying": text.textContent = amount + ". " + (p.error ?? ""); break;
          case "paused": text.textContent = "Paused at " + amount; break;
          case "processing": text.textContent = "Checking the file…"; break;
          case "failed": text.textContent = p.error ?? "Failed"; text.className = "error small"; break;
          case "done": break;
        }
        if (p.state !== "failed") text.className = "muted small";
        pauseButton.hidden = !(p.state === "uploading" || p.state === "retrying" || p.state === "starting");
        resumeButton.hidden = !(p.state === "paused" || (p.state === "failed" && p.error !== "Cancelled"));
        resumeButton.textContent = p.state === "failed" ? "Retry" : "Resume";
        cancelButton.hidden = p.state === "done" || p.state === "processing" || (p.state === "failed" && p.error === "Cancelled");
        if (p.state === "done" && p.result !== undefined) {
          reminder.hidden = true;
          const label = file.name + (p.result.deduplicated ? " (already on the server)" : "");
          trackItem(activity, row, label, p.result.itemId, options.onItemChanged);
          options.onItemChanged();
        }
      };
      const onError = (e: unknown): void => { text.textContent = message(e); };
      const pauseButton = button("Pause", () => upload.pause(), onError);
      const resumeButton = button("Resume", () => { void upload.start(); }, onError);
      const cancelButton = button("Cancel", () => upload.cancel(), onError);
      controls.append(pauseButton, resumeButton, cancelButton);
      upload = new Upload(file, show);
      upload.addToLibrary = addToLibrary.checked;
      row.append(h("div", { class: "activity-head" }, h("strong", { text: file.name }),
        upload.resumable ? h("span", { class: "muted small", text: " (continuing)" }) : null),
        h("div", { class: "row" }, bar, text, controls));
      activity.prepend(row);
      void upload.start();
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
    parts.push(h("div", { class: "add-upload" }, input, drop,
      h("label", { class: "check" }, addToLibrary, " Add uploads to my library"), reminder));
  }

  if (options.canYoutube) {
    const form = h("form", { class: "row add-youtube" },
      h("input", { name: "url", type: "url", required: true, placeholder: "https://www.youtube.com/watch?v=…",
        "aria-label": "YouTube link", class: "grow" }),
      h("label", { class: "check" }, h("input", { name: "presentAsAudio", type: "checkbox" }), " Audio only"),
      h("button", { type: "submit", text: "Import" }));
    const error = h("p", { class: "error", role: "alert" });
    form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      error.textContent = "";
      const data = new FormData(form);
      const url = String(data.get("url") ?? "").trim();
      const submit = form.querySelector("button");
      if (submit !== null) submit.disabled = true;
      // The lookup can take a while (docs/api.md#adding-media): show its stages and the seconds so far.
      const started = Date.now();
      let stageText: string | undefined;
      const showLookup = (): void => { lookup.textContent = lookupText(stageText, Date.now() - started); };
      showLookup();
      lookup.hidden = false;
      const ticker = window.setInterval(showLookup, 1000);
      importWithStages({ url, presentAsAudio: data.get("presentAsAudio") !== null }, (text) => {
        stageText = text;
        showLookup();
      })
        .then((r) => {
          form.reset();
          const row = h("li", { class: "activity-row" });
          trackItem(activity, row, r.cached ? url + " (already on the server)" : url, r.itemId, options.onItemChanged);
          options.onItemChanged();
        })
        .catch((e: unknown) => { error.textContent = message(e); })
        .finally(() => {
          window.clearInterval(ticker);
          lookup.hidden = true;
          if (submit !== null) submit.disabled = false;
        });
    });
    const lookup = h("p", { class: "muted small", role: "status" });
    lookup.hidden = true;
    parts.push(h("div", {}, h("h3", { text: "Import from YouTube" }), form, lookup, error));
  }

  if (parts.length === 0) {
    parts.push(h("p", { class: "muted", text: "You don't have permission to add media." }));
  }
  container.replaceChildren(...parts, activity);
}

/**
 * `POST /api/imports/youtube` asking for the lookup's stages (`Accept: application/x-ndjson`):
 * calls `onStage` with each stage's text while yt-dlp runs, resolves with the result. A known
 * item (no lookup) answers with plain JSON at once, which is handled the same way.
 */
async function importWithStages(body: { url: string; presentAsAudio: boolean },
  onStage: (text: string) => void): Promise<YoutubeResult> {
  let response: Response;
  try {
    response = await fetch("/api/imports/youtube", {
      method: "POST", credentials: "same-origin", body: JSON.stringify(body),
      headers: { Accept: "application/x-ndjson, application/json", "Content-Type": "application/json" },
    });
  } catch {
    throw new ApiError(0, "network_error", "Could not reach the server");
  }
  const type = response.headers.get("Content-Type") ?? "";
  if (!type.startsWith("application/x-ndjson")) {
    const text = await response.text();
    let json: unknown;
    try {
      json = text === "" ? undefined : JSON.parse(text);
    } catch {
      json = undefined;
    }
    if (!response.ok) {
      const error = (json as { error?: { code?: string; message?: string } } | undefined)?.error;
      throw new ApiError(response.status, error?.code ?? "http_" + response.status, error?.message ?? response.statusText);
    }
    return json as YoutubeResult;
  }
  const lines = new JsonLines();
  let result: YoutubeResult | undefined;
  let failure: ApiError | undefined;
  const handle = (values: unknown[]): void => {
    for (const v of values) {
      const line = v as { type?: string; text?: string; status?: number; error?: { code?: string; message?: string } };
      if (line.type === "progress" && typeof line.text === "string") onStage(line.text);
      else if (line.type === "result") result = v as YoutubeResult;
      else if (line.type === "error") {
        failure = new ApiError(Number(line.status ?? 0), line.error?.code ?? "error", line.error?.message ?? "Import failed");
      }
    }
  };
  const decoder = new TextDecoder();
  const reader = response.body?.getReader();
  if (reader === undefined) {
    handle(lines.push(await response.text()));
  } else {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      handle(lines.push(decoder.decode(value, { stream: true })));
    }
    handle(lines.push(decoder.decode()));
  }
  handle(lines.end());
  if (failure !== undefined) throw failure;
  if (result === undefined) throw new ApiError(0, "network_error", "The import ended without an answer");
  return result;
}
