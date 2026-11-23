// The "Import a music library" section of the admin user page (docs/ui.md, admin pages;
// docs/library-and-ingest.md#library-import). What it shows comes from the DOM-free libimport.ts;
// this module draws it, uploads the zip (chunked and resumable, POST/PUT/complete under
// /api/admin/library-import/uploads) and polls the import's state while the section is on screen.
import { api, ApiError } from "./api.js";
import { h } from "./dom.js";
import { chunkRange, importView, isZipName, moreProblemsText, nextChunk, problemText, sourceText, uploadKey,
  uploadText, pollDelayMs, type ImportProblem, type ImportStatus, type LibraryImport,
  type UploadPhase } from "./libimport.js";
import { retryable } from "./uploads.js";

interface UploadStatus { uploadId: string; chunkSize: number; size: number; receivedBytes: number; complete: boolean }

const UPLOADS = "/api/admin/library-import/uploads";
const RETRY_DELAYS_MS = [1000, 2000, 5000, 10000, 30000];
/** Remembered uploads older than this are forgotten (the server drops unused ones after 7 days). */
const STORE_MAX_AGE_MS = 7 * 24 * 3600 * 1000;

function storeGet(key: string): string | undefined {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return undefined;
    const s = JSON.parse(raw) as { uploadId?: unknown; savedAt?: unknown };
    if (typeof s.uploadId !== "string" || typeof s.savedAt !== "number" || !(Date.now() - s.savedAt < STORE_MAX_AGE_MS)) {
      localStorage.removeItem(key);
      return undefined;
    }
    return s.uploadId;
  } catch {
    return undefined;
  }
}

function storeSet(key: string, uploadId: string): void {
  try {
    localStorage.setItem(key, JSON.stringify({ uploadId, savedAt: Date.now() }));
  } catch {
    // private mode or full: no resume after a reload, the upload still works
  }
}

function storeRemove(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

class Cancelled extends Error {}

/**
 * One zip upload for one user's import; it outlives a redraw of the user panel (module-level
 * `active`), so the section drawn again shows it.
 */
class ZipUpload {
  readonly userId: string;
  readonly file: File;
  readonly smartPlaylists: boolean;
  phase: UploadPhase = "starting";
  sent = 0;
  error: string | undefined;
  #uploadId: string | undefined;
  #xhr: XMLHttpRequest | undefined;
  #cancelled = false;
  onChange: () => void = () => {};
  /** Called once the check has been started (or the upload failed): the section reloads. */
  onDone: () => void = () => {};

  constructor(userId: string, file: File, smartPlaylists: boolean) {
    this.userId = userId;
    this.file = file;
    this.smartPlaylists = smartPlaylists;
  }

  #set(phase: UploadPhase, error?: string): void {
    this.phase = phase;
    this.error = error;
    this.onChange();
  }

  async run(): Promise<void> {
    const key = uploadKey(this.file);
    let attempt = 0;
    for (;;) {
      try {
        const status = await this.#send(key);
        this.#set("finishing");
        await api("POST", "/api/admin/users/" + encodeURIComponent(this.userId) + "/library-import",
          { source: { uploadId: status.uploadId }, smartPlaylists: this.smartPlaylists });
        storeRemove(key);
        if (active === this) active = undefined;
        this.onDone();
        return;
      } catch (e) {
        if (this.#cancelled || e instanceof Cancelled) return;
        if (!retryable(e)) {
          if (e instanceof ApiError && e.status !== 401 && e.status !== 403 && e.status !== 507) storeRemove(key);
          if (active === this) active = undefined;
          this.#set("failed", message(e));
          this.onDone();
          return;
        }
        const delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)] ?? 30000;
        attempt++;
        this.#set("retrying", message(e));
        await new Promise((resolve) => window.setTimeout(resolve, delay));
        if (this.#cancelled) return;
      }
    }
  }

  /** Sends what the server doesn't have yet and completes the upload. */
  async #send(key: string): Promise<UploadStatus> {
    const size = this.file.size;
    let status: UploadStatus | undefined;
    const stored = this.#uploadId ?? storeGet(key);
    if (stored !== undefined) {
      try {
        status = await api<UploadStatus>("GET", UPLOADS + "/" + encodeURIComponent(stored));
        if (status.size !== size) status = undefined;
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 404) throw e;
        status = undefined; // gone (used, or cleaned up): start over
      }
      if (status === undefined) storeRemove(key);
    }
    if (status === undefined) {
      this.#set("starting");
      status = await api<UploadStatus>("POST", UPLOADS, { size, filename: this.file.name });
      storeSet(key, status.uploadId);
    }
    this.#uploadId = status.uploadId;
    this.sent = status.receivedBytes;
    while (!status.complete && status.receivedBytes < size) {
      if (this.#cancelled) throw new Cancelled();
      this.#set("uploading");
      const n = nextChunk(status.receivedBytes, status.chunkSize);
      try {
        status = await this.#put(n, status.chunkSize);
      } catch (e) {
        if (e instanceof ApiError && e.status === 409 && e.code === "upload_offset_mismatch") {
          status = await api<UploadStatus>("GET", UPLOADS + "/" + encodeURIComponent(status.uploadId));
        } else {
          throw e;
        }
      }
      this.sent = status.receivedBytes;
    }
    if (!status.complete) {
      this.#set("finishing");
      status = await api<UploadStatus>("POST", UPLOADS + "/" + encodeURIComponent(status.uploadId) + "/complete");
    }
    return status;
  }

  /** Chunk `n` through XHR, for upload progress events. */
  #put(n: number, chunkSize: number): Promise<UploadStatus> {
    const [start, end] = chunkRange(n, chunkSize, this.file.size);
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      this.#xhr = xhr;
      xhr.open("PUT", UPLOADS + "/" + encodeURIComponent(this.#uploadId ?? "") + "/chunks/" + n);
      xhr.setRequestHeader("Content-Type", "application/octet-stream");
      xhr.setRequestHeader("Accept", "application/json");
      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) {
          this.sent = start + ev.loaded;
          this.onChange();
        }
      };
      xhr.onload = () => {
        let json: unknown;
        try {
          json = xhr.responseText === "" ? undefined : JSON.parse(xhr.responseText);
        } catch {
          json = undefined;
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(json as UploadStatus);
        } else {
          const error = (json as { error?: { code?: string; message?: string } } | undefined)?.error;
          reject(new ApiError(xhr.status, error?.code ?? "http_" + xhr.status, error?.message ?? xhr.statusText));
        }
      };
      xhr.onerror = () => reject(new ApiError(0, "network_error", "Connection lost"));
      xhr.ontimeout = () => reject(new ApiError(0, "network_error", "Timed out"));
      xhr.onabort = () => reject(new Cancelled());
      xhr.send(this.file.slice(start, end));
    });
  }

  /** Stops the upload and deletes what the server has of it. */
  cancel(): void {
    this.#cancelled = true;
    this.#xhr?.abort();
    storeRemove(uploadKey(this.file));
    if (active === this) active = undefined;
    const id = this.#uploadId;
    if (id !== undefined) void api("DELETE", UPLOADS + "/" + encodeURIComponent(id)).catch(() => undefined);
  }

  /** Stops sending (leaving the page); picking the same file again resumes it. */
  pause(): void {
    this.#cancelled = true;
    this.#xhr?.abort();
    if (active === this) active = undefined;
  }
}

/** The upload running in this page, if any (one at a time). */
let active: ZipUpload | undefined;

/**
 * Draws the section for one user. It polls while it is in the document and `signal` isn't aborted;
 * an upload in progress is paused when `signal` aborts (picking the same file again resumes it).
 */
export function mountLibraryImport(userId: string, signal: AbortSignal): HTMLElement {
  const base = "/api/admin/users/" + encodeURIComponent(userId) + "/library-import";
  const error = h("p", { class: "error", role: "alert" });
  const statusBox = h("div", { class: "lib-import-status" });

  // The form: the source choice, smart playlists, Check. Drawn once, shown or hidden.
  const fileInput = h("input", { type: "file", name: "zip", accept: ".zip,application/zip" });
  const pathInput = h("input", { type: "text", name: "path", placeholder: "/path/to/export", autocomplete: "off",
    "aria-label": "Folder or zip on the server", size: "40" });
  const pathHint = h("p", { class: "muted small" });
  const useUpload = h("input", { type: "radio", name: "lib-import-source", value: "upload", checked: true });
  const usePath = h("input", { type: "radio", name: "lib-import-source", value: "path" });
  const pathChoice = h("div", { class: "lib-import-choice" },
    h("label", {}, usePath, " Folder or zip on the server"), h("div", {}, pathInput), pathHint);
  const smart = h("input", { type: "checkbox", name: "smartPlaylists" });
  const checkButton = h("button", { type: "submit", text: "Check" });
  const uploadBar = h("progress", { max: "1", value: "0" });
  const uploadLine = h("span", { class: "muted small", role: "status" });
  const uploadCancel = h("button", { type: "button", text: "Cancel" });
  const uploadBox = h("div", { class: "lib-import-upload" }, uploadBar, " ", uploadLine, " ", uploadCancel);
  uploadBox.hidden = true;
  const form = h("form", { class: "lib-import-form" },
    h("div", { class: "lib-import-choice" }, h("label", {}, useUpload, " Upload a zip file"), h("div", {}, fileInput)),
    pathChoice,
    h("label", {}, smart, " Also import smart playlists"),
    h("div", { class: "row" }, checkButton),
    uploadBox);

  const section = h("section", { class: "lib-import" },
    h("h4", { text: "Import a music library" }),
    h("p", { class: "muted", text: "A Library.xml export and its media folder, as one zip or as a folder on the server." }),
    statusBox, form, error);

  let last: ImportStatus | undefined;
  let lastJson = "";
  let showAll: ImportProblem[] | undefined;
  let timer: number | undefined;

  const drawUpload = (): void => {
    const u = active !== undefined && active.userId === userId ? active : undefined;
    uploadBox.hidden = u === undefined;
    checkButton.disabled = u !== undefined;
    if (u === undefined) return;
    uploadBar.max = Math.max(1, u.file.size);
    uploadBar.value = u.sent;
    uploadLine.textContent = uploadText(u.phase, u.sent, u.file.size, u.error);
  };

  const attach = (u: ZipUpload): void => {
    u.onChange = () => { if (section.isConnected) drawUpload(); };
    u.onDone = () => {
      if (u.phase === "failed") error.textContent = "Upload failed: " + (u.error ?? "unknown error");
      drawUpload();
      void refresh();
    };
  };

  const act = async (button: HTMLButtonElement, action: () => Promise<void>): Promise<void> => {
    error.textContent = "";
    button.disabled = true;
    try {
      await action();
    } catch (e) {
      error.textContent = message(e);
    } finally {
      button.disabled = false;
    }
    await refresh();
  };

  const problemList = (i: LibraryImport): HTMLElement | null => {
    const items = showAll ?? i.problems;
    if (items.length === 0) return null;
    const list = h("ul", { class: "lib-import-problems" }, ...items.map((p) => h("li", { text: problemText(p) })));
    const more = showAll === undefined ? moreProblemsText(i) : undefined;
    const box = h("div", {}, h("p", { text: `Problems (${i.counts.problems}):` }), list);
    if (more !== undefined) {
      const b = h("button", { type: "button", text: more });
      b.addEventListener("click", () => void act(b, async () => {
        const all: ImportProblem[] = [];
        let cursor: string | undefined;
        do {
          const page = await api<{ items: ImportProblem[]; nextCursor?: string }>("GET",
            base + "/problems" + (cursor !== undefined ? "?cursor=" + encodeURIComponent(cursor) : ""));
          all.push(...page.items);
          cursor = page.nextCursor;
        } while (cursor !== undefined && all.length < 5000);
        showAll = all;
        lastJson = "";
      }));
      box.append(b);
    }
    return box;
  };

  const draw = (status: ImportStatus): void => {
    const view = importView(status);
    const i = status.import ?? undefined;
    const parts: (Node | null)[] = [];
    if (view.otherText !== undefined) parts.push(h("p", { class: "notice", text: view.otherText }));
    if (i !== undefined && view.heading !== undefined) {
      parts.push(h("p", {}, h("strong", { text: view.heading }), " ", h("span", { class: "muted small",
        text: sourceText(i.source) })));
      if (view.error !== undefined) parts.push(h("p", { class: "error", text: view.error }));
      if (view.progress !== undefined) {
        parts.push(h("div", {}, h("progress", { max: String(view.progress.max), value: String(view.progress.value) }),
          " ", h("span", { role: "status", text: view.progress.text })));
      }
      if (view.lines.length > 0) parts.push(h("ul", { class: "lib-import-counts" }, ...view.lines.map((l) => h("li", { text: l }))));
      if (view.showProblems) parts.push(problemList(i));
      const buttons: HTMLButtonElement[] = [];
      for (const a of view.actions) {
        const b = h("button", { type: "button",
          text: a === "import" ? "Import" : a === "cancel" ? "Cancel" : "Cancel import" });
        b.dataset["action"] = a;
        b.addEventListener("click", () => {
          if (a === "cancelImport" && !confirm("Cancel the import? What is imported so far stays.")) return;
          void act(b, async () => {
            await api("POST", base + (a === "import" ? "/start" : "/cancel"));
          });
        });
        buttons.push(b);
      }
      if (buttons.length > 0) parts.push(h("div", { class: "row" }, ...buttons));
    }
    statusBox.replaceChildren(...parts.filter((p): p is Node => p !== null));
    const uploading = active !== undefined && active.userId === userId;
    form.hidden = !view.showForm && !uploading;
    pathChoice.hidden = !view.showPathOption;
    useUpload.hidden = !view.showPathOption; // one choice only: no radio button
    pathHint.textContent = view.pathHint ?? "";
    if (!view.showPathOption) useUpload.checked = true;
    drawUpload();
  };

  const refresh = async (): Promise<void> => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = undefined;
    if (signal.aborted) return;
    try {
      const status = await api<ImportStatus>("GET", base);
      if (signal.aborted) return;
      if (last?.import?.id !== status.import?.id || last?.import?.state !== status.import?.state) showAll = undefined;
      last = status;
      const json = JSON.stringify(status);
      if (json !== lastJson) {
        lastJson = json;
        draw(status);
      }
    } catch (e) {
      if (!section.isConnected && last !== undefined) return;
      error.textContent = message(e);
    }
    if (signal.aborted || (!section.isConnected && last !== undefined)) return;
    timer = window.setTimeout(() => {
      if (section.isConnected && !signal.aborted) void refresh();
    }, pollDelayMs(last));
  };

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    error.textContent = "";
    if (usePath.checked && !pathChoice.hidden) {
      const path = pathInput.value.trim();
      if (path === "") {
        error.textContent = "Enter a folder or zip file on the server.";
        return;
      }
      void act(checkButton, async () => {
        await api("POST", base, { source: { path }, smartPlaylists: smart.checked });
      });
      return;
    }
    const file = fileInput.files?.[0];
    if (file === undefined) {
      error.textContent = "Choose a zip file.";
      return;
    }
    if (!isZipName(file.name)) {
      error.textContent = "Choose a .zip file.";
      return;
    }
    if (active !== undefined) {
      error.textContent = "Another upload is running in this page.";
      return;
    }
    const u = new ZipUpload(userId, file, smart.checked);
    active = u;
    attach(u);
    drawUpload();
    void u.run();
  });
  uploadCancel.addEventListener("click", () => {
    if (active === undefined || active.userId !== userId) return;
    active.cancel();
    drawUpload();
    void refresh();
  });
  fileInput.addEventListener("change", () => { useUpload.checked = true; });
  pathInput.addEventListener("input", () => { usePath.checked = true; });

  if (active !== undefined && active.userId === userId) attach(active);
  signal.addEventListener("abort", () => {
    if (timer !== undefined) window.clearTimeout(timer);
    active?.pause();
  }, { once: true });
  form.hidden = true;
  void refresh();
  return section;
}
