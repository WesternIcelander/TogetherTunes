// Chunked, resumable uploads (docs/api.md#adding-media): `POST /api/uploads` starts one, each
// chunk is a `PUT /api/uploads/{id}` with `Content-Range`, `POST …/complete` turns it into an item.
//
// Resuming: the upload id is remembered in localStorage under the file's name, size and
// modification time. Picking the same file again (also after a reload or a crash) asks the
// server how much it already has (`GET /api/uploads/{id}`) and continues from there. Network
// errors and server errors are retried with a growing delay; the server skips bytes it already
// has, so a chunk that was sent twice does no harm.
import { api, ApiError } from "./api.js";

interface Created { uploadId: string; chunkSize: number; size: number }
interface Status { receivedBytes: number; size: number; itemId?: string }
export interface Completed { itemId: string; deduplicated: boolean }

export type UploadState = "starting" | "uploading" | "paused" | "retrying" | "processing" | "done" | "failed";

export interface UploadProgress {
  state: UploadState;
  sentBytes: number;
  size: number;
  /** Set for "retrying" (the error being retried) and "failed". */
  error?: string;
  /** Set once completed. */
  result?: Completed;
}

const STORE_PREFIX = "tt.upload.";
/** Remembered uploads older than this are forgotten (the server drops abandoned uploads too). */
const STORE_MAX_AGE_MS = 7 * 24 * 3600 * 1000;
const RETRY_DELAYS_MS = [1000, 2000, 5000, 10000, 30000];

interface Stored { uploadId: string; savedAt: number }

function fileKey(file: File): string {
  return STORE_PREFIX + file.size + ":" + file.lastModified + ":" + file.name;
}

function storeGet(key: string): Stored | undefined {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return undefined;
    const s = JSON.parse(raw) as Stored;
    if (typeof s.uploadId !== "string" || !(Date.now() - s.savedAt < STORE_MAX_AGE_MS)) {
      localStorage.removeItem(key);
      return undefined;
    }
    return s;
  } catch {
    return undefined;
  }
}

function storeSet(key: string, uploadId: string): void {
  try {
    localStorage.setItem(key, JSON.stringify({ uploadId, savedAt: Date.now() } satisfies Stored));
  } catch {
    // private mode or full: resuming after a reload won't work, uploading still does
  }
}

function storeRemove(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

/** File names of unfinished uploads remembered in this browser, to remind the user to pick them again. */
export function rememberedUploads(): string[] {
  const names: string[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key === null || !key.startsWith(STORE_PREFIX)) continue;
      if (storeGet(key) === undefined) continue;
      const rest = key.substring(STORE_PREFIX.length);
      const name = rest.substring(rest.indexOf(":", rest.indexOf(":") + 1) + 1);
      names.push(name);
    }
  } catch {
    // ignore
  }
  return names;
}

/** Thrown inside the loop when the user paused or cancelled. */
class Stopped extends Error {}

function sleep(ms: number, signal: { stopped: boolean }): Promise<void> {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const tick = (): void => {
      if (signal.stopped) reject(new Stopped());
      else if (Date.now() - started >= ms) resolve();
      else window.setTimeout(tick, Math.min(250, ms));
    };
    tick();
  });
}

/** Whether an error is worth retrying: no connection, a timeout, or a server-side failure. */
function retryable(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 0 || e.status === 408 || e.status >= 500);
}

export class Upload {
  readonly file: File;
  readonly key: string;
  #uploadId: string | undefined;
  #chunkSize = 8 * 1024 * 1024;
  #sent = 0;
  #state: UploadState = "starting";
  #signal = { stopped: false };
  #xhr: XMLHttpRequest | undefined;
  #running = false;
  #cancelled = false;
  #onProgress: (p: UploadProgress) => void;
  /** Metadata for `complete`; the server reads tags and the file name when left out. */
  addToLibrary = true;
  /** The room this upload is a request in (`POST /api/uploads {roomId}`); needed for guests. */
  roomId: string | undefined;

  constructor(file: File, onProgress: (p: UploadProgress) => void) {
    this.file = file;
    this.key = fileKey(file);
    this.#onProgress = onProgress;
  }

  /** The server's id for this upload, once started (what `queue.add {uploadId}` takes). */
  get uploadId(): string | undefined {
    return this.#uploadId;
  }

  get state(): UploadState {
    return this.#state;
  }

  /** Whether an earlier, unfinished upload of this file is remembered in this browser. */
  get resumable(): boolean {
    return storeGet(this.key) !== undefined;
  }

  #report(state: UploadState, extra: Partial<UploadProgress> = {}): void {
    this.#state = state;
    this.#onProgress({ state, sentBytes: this.#sent, size: this.file.size, ...extra });
  }

  /** Starts, or continues after `pause()` or a failure. Resolves when done, failed or paused. */
  async start(): Promise<void> {
    if (this.#running || this.#cancelled || this.#state === "done") return;
    this.#running = true;
    this.#signal = { stopped: false };
    let attempt = 0;
    try {
      for (;;) {
        try {
          const done = await this.#run();
          storeRemove(this.key);
          this.#report("done", { result: done });
          return;
        } catch (e) {
          if (e instanceof Stopped || this.#signal.stopped) {
            if (!this.#cancelled) this.#report("paused");
            return;
          }
          if (!retryable(e)) {
            // The server refused the file (unreadable, too long, too large…) or lost the upload:
            // nothing left to resume. 401/403 keep it, so it can continue after logging in.
            if (e instanceof ApiError && e.status !== 401 && e.status !== 403) storeRemove(this.key);
            this.#report("failed", { error: e instanceof Error ? e.message : String(e) });
            return;
          }
          const delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)] ?? 30000;
          attempt++;
          this.#report("retrying", { error: (e as Error).message + "; retrying in " + Math.round(delay / 1000) + " s" });
          try {
            await sleep(delay, this.#signal);
          } catch {
            if (!this.#cancelled) this.#report("paused");
            return;
          }
        }
      }
    } finally {
      this.#running = false;
    }
  }

  /** Stops sending; `start()` continues later from what the server has. */
  pause(): void {
    this.#signal.stopped = true;
    this.#xhr?.abort();
  }

  /** Stops and forgets the upload in this browser (the server drops the partial file later). */
  cancel(): void {
    this.#cancelled = true;
    this.pause();
    storeRemove(this.key);
    this.#report("failed", { error: "Cancelled" });
  }

  async #run(): Promise<Completed> {
    const size = this.file.size;
    if (this.#uploadId === undefined) {
      const stored = storeGet(this.key);
      if (stored !== undefined) this.#uploadId = stored.uploadId;
    }
    if (this.#uploadId !== undefined) {
      // Resume: how much does the server have?
      let status: Status | undefined;
      try {
        status = await api<Status>("GET", "/api/uploads/" + encodeURIComponent(this.#uploadId));
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 404) throw e;
        // Gone (expired, or another account's): start over.
        storeRemove(this.key);
        this.#uploadId = undefined;
        this.#sent = 0;
      }
      if (status !== undefined) {
        if (status.size !== size) {
          storeRemove(this.key);
          this.#uploadId = undefined;
          this.#sent = 0;
        } else {
          this.#sent = status.receivedBytes;
        }
      }
    }
    if (this.#uploadId === undefined) {
      this.#report("starting");
      const created = await api<Created>("POST", "/api/uploads", 
        this.roomId === undefined ? { filename: this.file.name, size } : { filename: this.file.name, size, roomId: this.roomId });
      this.#uploadId = created.uploadId;
      if (created.chunkSize > 0) this.#chunkSize = created.chunkSize;
      this.#sent = 0;
      storeSet(this.key, created.uploadId);
    }
    this.#check();
    while (this.#sent < size) {
      this.#report("uploading");
      const start = this.#sent;
      const end = Math.min(size, start + this.#chunkSize);
      try {
        const r = await this.#put(start, end);
        this.#sent = r.receivedBytes;
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) {
          // upload_offset_mismatch: ask where the server is and continue from there
          const status = await api<Status>("GET", "/api/uploads/" + encodeURIComponent(this.#uploadId));
          this.#sent = status.receivedBytes;
          continue;
        }
        throw e;
      }
      this.#check();
    }
    this.#report("processing");
    return await api<Completed>("POST", "/api/uploads/" + encodeURIComponent(this.#uploadId) + "/complete",
      { addToLibrary: this.addToLibrary });
  }

  #check(): void {
    if (this.#signal.stopped) throw new Stopped();
  }

  /** One chunk, bytes `start` to `end - 1`, through XHR for upload progress events. */
  #put(start: number, end: number): Promise<Status> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      this.#xhr = xhr;
      xhr.open("PUT", "/api/uploads/" + encodeURIComponent(this.#uploadId ?? ""));
      xhr.setRequestHeader("Content-Range", `bytes ${start}-${end - 1}/${this.file.size}`);
      xhr.setRequestHeader("Content-Type", "application/octet-stream");
      xhr.setRequestHeader("Accept", "application/json");
      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) {
          this.#onProgress({ state: "uploading", sentBytes: start + ev.loaded, size: this.file.size });
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
          resolve(json as Status);
        } else {
          const error = (json as { error?: { code?: string; message?: string } } | undefined)?.error;
          reject(new ApiError(xhr.status, error?.code ?? "http_" + xhr.status, error?.message ?? xhr.statusText));
        }
      };
      xhr.onerror = () => reject(new ApiError(0, "network_error", "Connection lost"));
      xhr.ontimeout = () => reject(new ApiError(0, "network_error", "Timed out"));
      xhr.onabort = () => reject(new Stopped());
      xhr.send(this.file.slice(start, end));
    });
  }
}
