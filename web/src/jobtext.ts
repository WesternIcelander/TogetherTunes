// Text for the encode jobs page (docs/library-and-ingest.md#encode-jobs-page): the job rows of
// GET /api/admin/jobs and GET /api/jobs, and how they read. No DOM here, so Node tests can import it.

/** `kind` of an ingest job. */
export type JobKind = "upload" | "youtube" | "refetch" | "reencode" | "quality";

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
      const pct = Math.floor(Math.max(0, Math.min(1, row.progress)) * 100);
      return "Running " + pct + "%" + (row.attempts > 1 ? " (attempt " + row.attempts + ")" : "");
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
