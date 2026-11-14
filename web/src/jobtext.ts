// Text for the encode jobs page (docs/library-and-ingest.md#encode-jobs-page): the job rows of
// GET /api/admin/jobs and GET /api/jobs, and how they read. No DOM here, so Node tests can import it.

/** `kind` of an ingest job. */
export type JobKind = "upload" | "youtube" | "refetch" | "reencode" | "quality" | "trim";

/** Which list: running and queued, unresolved failures, finished in the last 24 h. */
export type JobListState = "active" | "failed" | "done";

/** One row of the jobs lists. */
export interface JobRow {
  id: string;
  kind: JobKind;
  tier?: "quick" | "quality";
  state: "queued" | "running" | "done" | "failed";
  progress: number;
  attempts: number;
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  /** 1-based place in the whole queue (queued jobs only). */
  position?: number;
  /** A running job's step now (live), with that step's own progress. */
  step?: IngestStep;
  stepProgress?: number;
  item: {
    id: string;
    kind: "audio" | "video";
    title: string;
    artist?: string;
    source: string;
    status: "pending" | "processing" | "ready" | "failed" | "purged";
    error?: string;
  };
}

/** `GET /api/admin/jobs/{id}`: a row plus its log tail. */
export interface JobDetail extends JobRow { log?: string }

/** A page of a jobs list; `paused` only for the active list. */
export interface JobPage { items: JobRow[]; nextCursor?: string; paused?: boolean }

const KIND_TEXT: Record<JobKind, string> = {
  upload: "Upload",
  youtube: "YouTube download",
  refetch: "YouTube refetch",
  reencode: "Re-encode (new output profile)",
  quality: "Quality re-encode",
  trim: "Trim",
};

/** What a job does, for people. */
export function kindText(kind: string): string {
  return (KIND_TEXT as Record<string, string | undefined>)[kind] ?? kind;
}

/** The preset tier, or "" when the job decides when it starts. */
export function tierText(tier: string | undefined): string {
  if (tier === "quick") return "Quick preset";
  if (tier === "quality") return "Quality preset";
  return "";
}

/** "1st", "2nd", "3rd", "4th", "11th", "21st", … */
export function ordinal(n: number): string {
  const tens = n % 100;
  if (tens >= 11 && tens <= 13) return n + "th";
  switch (n % 10) {
    case 1: return n + "st";
    case 2: return n + "nd";
    case 3: return n + "rd";
    default: return n + "th";
  }
}

/** The state column: "Running 42%", "Queued, 3rd in line", "Failed after 2 attempts", "Done". */
export function stateText(row: JobRow): string {
  switch (row.state) {
    case "running": {
      const attempt = row.attempts > 1 ? " (attempt " + row.attempts + ")" : "";
      if (row.step !== undefined) {
        return ingestText({ state: "running", kind: row.kind, step: row.step, progress: row.stepProgress }) + attempt;
      }
      const pct = Math.floor(Math.max(0, Math.min(1, row.progress)) * 100);
      return "Running " + pct + "%" + attempt;
    }
    case "queued":
      return row.position === undefined ? "Queued" : "Queued, " + ordinal(row.position) + " in line";
    case "failed":
      return "Failed" + (row.attempts > 1 ? " after " + row.attempts + " attempts" : "");
    case "done":
      return "Done";
    default:
      return String(row.state);
  }
}

/** How long a running or finished job has taken, as "12 s", "3 min 05 s", "1 h 02 min"; "" if unknown. */
export function elapsedText(row: JobRow, now: number): string {
  if (row.startedAt === undefined || row.state === "queued") return "";
  const end = row.state === "running" ? now : row.finishedAt;
  if (end === undefined) return "";
  const s = Math.max(0, Math.round((end - row.startedAt) / 1000));
  if (s < 60) return s + " s";
  const m = Math.floor(s / 60);
  if (m < 60) return m + " min " + String(s % 60).padStart(2, "0") + " s";
  return Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0") + " min";
}

/** The note above the queue while workers hold back queued jobs. */
export const PAUSED_TEXT = "The server is low on disk space: queued jobs wait until there is room again"
  + " (running jobs carry on).";

/** What the page says when a list is empty. */
export function emptyText(state: JobListState, admin: boolean): string {
  const whose = admin ? "" : " for items in your library";
  switch (state) {
    case "active": return "Nothing is encoding or queued" + whose + ".";
    case "failed": return "No failed jobs" + whose + ".";
    case "done": return "No jobs finished in the last 24 hours" + whose + ".";
  }
}

// ------------------------------------------------------------------ ingest progress in words

/** The step a running ingest job is in (docs/library-and-ingest.md#encode-jobs-page). */
export type IngestStep = "download" | "encode" | "finish";

/**
 * What the ingest of an item that isn't ready is doing: an entry of the room's `ingest` message
 * (docs/api.md#websocket), or made from an item's latest job ({@link jobIngestStatus}).
 */
export interface IngestStatus {
  state: "queued" | "running" | "failed";
  kind?: string;
  /** 1-based place in the whole ingest queue (queued only). */
  position?: number;
  step?: IngestStep;
  /** The step's own progress, 0..1. */
  progress?: number;
  /** A short reason (failed only). */
  error?: string;
}

function percent(p: number | undefined): string {
  const v = p === undefined || !isFinite(p) ? 0 : Math.max(0, Math.min(1, p));
  return Math.floor(v * 100) + " %";
}

/**
 * The words for an item's ingest, the same on the room page, the Library page and the item page:
 * "Waiting to download (3rd in line)", "Downloading 45 %", "Encoding 30 %", "Finishing…",
 * "Failed: <short reason>". Nothing known yet reads as waiting.
 */
export function ingestText(s: IngestStatus | undefined): string {
  if (s === undefined) return "Waiting to download";
  switch (s.state) {
    case "running":
      switch (s.step) {
        case "download": return "Downloading " + percent(s.progress);
        case "encode": return "Encoding " + percent(s.progress);
        case "finish": return "Finishing…";
        default: return "Starting…";
      }
    case "failed":
      return s.error !== undefined && s.error !== "" ? "Failed: " + s.error : "Failed";
    default: {
      const download = s.kind === undefined || s.kind === "youtube" || s.kind === "refetch";
      return (download ? "Waiting to download" : "Waiting to encode")
        + (s.position !== undefined ? " (" + ordinal(s.position) + " in line)" : "");
    }
  }
}

/**
 * Whether a room entry gets a Retry button (`queue.retry`): a failed YouTube entry, for the owner
 * and moderators, and for whoever requested it while they may still add YouTube links in the room.
 * A failed upload never does: its file isn't kept, so there's nothing to retry.
 */
export function canRetryEntry(status: string, source: string | undefined, canModerate: boolean, mine: boolean,
  canYoutube: boolean): boolean {
  if (status !== "failed" || source !== "youtube") return false;
  return canModerate || (mine && canYoutube);
}

/** An item's latest job (`GET /api/items/{id}/job`) as an {@link IngestStatus}; undefined once it is done. */
export function jobIngestStatus(job: { kind: string; state: string; step?: IngestStep; stepProgress?: number },
  itemError?: string): IngestStatus | undefined {
  switch (job.state) {
    case "queued": return { state: "queued", kind: job.kind };
    case "running": return { state: "running", kind: job.kind, step: job.step, progress: job.stepProgress };
    case "failed": return { state: "failed", kind: job.kind, error: itemError };
    default: return undefined;
  }
}

// ------------------------------------------------------------------ YouTube lookups

/** What the page says before yt-dlp has named a stage. */
export const LOOKUP_TEXT = "Looking up the video…";

/**
 * The line shown while a YouTube link is looked up: the latest stage's text (or
 * {@link LOOKUP_TEXT}) and the whole seconds since it started, so it visibly moves even when
 * yt-dlp is quiet for a while: "Reading the video page… 6 s".
 */
export function lookupText(stageText: string | undefined, elapsedMs: number): string {
  const s = Math.max(0, Math.floor(elapsedMs / 1000));
  return (stageText !== undefined && stageText !== "" ? stageText : LOOKUP_TEXT) + " " + s + " s";
}

