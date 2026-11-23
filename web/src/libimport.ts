// Library import on the admin user page (docs/library-and-ingest.md#library-import, docs/ui.md):
// the data of GET /api/admin/users/{id}/library-import and what the section shows for it (which
// parts, the count texts, the progress text, the problems), plus the zip upload's arithmetic.
// DOM-free (libimport.test.mjs); the page code is libimportui.ts.
// User-visible text says "music library", "Library.xml" and "media folder", never an app's name.

export type ImportState = "checking" | "ready" | "importing" | "paused" | "done" | "failed" | "cancelled";

export interface ImportCounts {
  tracks: number;
  matched: number;
  missing: number;
  /** Skipped tracks by reason (`protected`, `unsupported_format`, `unreadable`, …). */
  skipped: Record<string, number>;
  alreadyInLibrary: number;
  videos?: number;
  playlists: { regular: number; smart: number; folders: number };
  unreferencedFiles: number;
  toImport: number;
  done: number;
  imported: number;
  already: number;
  liked?: number;
  playlistsMade: number;
  playlistsUpdated: number;
  problems: number;
}

export interface ImportProblem {
  kind: string;
  reason?: string;
  title?: string;
  artist?: string;
  path?: string;
  playlist?: string;
}

export interface LibraryImport {
  id: string;
  userId: string;
  state: ImportState;
  source: { path?: string; uploadId?: string };
  smartPlaylists: boolean;
  counts: ImportCounts;
  /** The first problems (the server sends up to 20). */
  problems: ImportProblem[];
  error?: string;
  createdAt: number;
  updatedAt: number;
  startedAt?: number;
  finishedAt?: number;
}

export interface OtherImport { userId: string; username: string; displayName: string; state: ImportState }

/** `GET /api/admin/users/{id}/library-import`. */
export interface ImportStatus {
  import?: LibraryImport | null;
  otherImport?: OtherImport;
  pathOption: { enabled: boolean; allowedPaths: string[] };
}

export type Phase = "idle" | "other" | "checking" | "ready" | "running" | "finished";

export type ImportAction = "import" | "cancel" | "cancelImport";

export interface ImportView {
  phase: Phase;
  /** The source form (choice, smart playlists, Check). */
  showForm: boolean;
  /** "Folder or zip on the server": only when the server allows paths. */
  showPathOption: boolean;
  /** The allowed paths, as a hint under the path box. */
  pathHint?: string;
  /** "Another import is running (for …)." */
  otherText?: string;
  heading?: string;
  /** Count lines (the check's counts, the counts so far, or the result). */
  lines: string[];
  progress?: { value: number; max: number; text: string };
  actions: ImportAction[];
  /** Whether the problems list (and Show all) belongs on screen. */
  showProblems: boolean;
  error?: string;
}

const SKIP_REASONS: Record<string, string> = {
  protected: "protected",
  unsupported_format: "unsupported format",
  unreadable: "unreadable",
  too_long: "too long",
  too_large: "too large",
  suspicious_compression: "suspicious compression",
};

/** "1 track", "2 tracks". */
export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

export function reasonText(reason: string | undefined): string {
  if (reason === undefined) return "unknown reason";
  return SKIP_REASONS[reason] ?? reason.replace(/_/g, " ");
}

function total(skipped: Record<string, number> | undefined): number {
  let n = 0;
  for (const v of Object.values(skipped ?? {})) n += v;
  return n;
}

/** "Skipped: 3 (2 protected, 1 unreadable)", "Skipped: 0". */
export function skippedText(skipped: Record<string, number> | undefined): string {
  const parts = Object.entries(skipped ?? {}).filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([reason, n]) => `${n} ${reasonText(reason)}`);
  return `Skipped: ${total(skipped)}` + (parts.length > 0 ? ` (${parts.join(", ")})` : "");
}

/** "Playlists: 31 regular, 195 smart, 7 folders" (and whether the smart ones are included). */
export function playlistsText(c: ImportCounts, smartPlaylists: boolean): string {
  const p = c.playlists ?? { regular: 0, smart: 0, folders: 0 };
  let s = `Playlists: ${p.regular} regular, ${p.smart} smart, ${plural(p.folders, "folder", "folders")}`;
  if (p.smart > 0) s += smartPlaylists ? " (smart ones included)" : " (smart ones not included)";
  return s;
}

/** The check's counts. `checking`: the "already in the library" count isn't known yet. */
export function checkLines(c: ImportCounts, smartPlaylists: boolean, checking = false): string[] {
  const lines = [
    `Tracks: ${c.tracks}`,
    `Matched to a file: ${c.matched}`,
    `Missing (no file for it): ${c.missing}`,
    skippedText(c.skipped),
    checking ? "Already in the library: still checking…" : `Already in the library: ${c.alreadyInLibrary}`,
  ];
  if ((c.videos ?? 0) > 0) lines.push(`Videos (imported as uncategorized): ${c.videos}`);
  lines.push(`To import: ${plural(c.toImport, "file", "files")}`);
  lines.push(playlistsText(c, smartPlaylists));
  lines.push(`Files not in the list: ${c.unreferencedFiles}`);
  return lines;
}

/** The counts while importing. */
export function progressLines(c: ImportCounts): string[] {
  return [`Imported: ${c.imported}`, `Already there: ${c.already}`, skippedText(c.skipped)];
}

/** "Importing: 12 of 100 files", "Paused for disk space: 12 of 100 files". */
export function progressText(i: LibraryImport): string {
  const done = i.counts.done;
  const of = `${done} of ${plural(i.counts.toImport, "file", "files")}`;
  return i.state === "paused"
    ? `Paused for disk space: ${of}. It goes on by itself when there is room.`
    : `Importing: ${of}`;
}

/** The result of a finished (or cancelled) import. */
export function resultLines(c: ImportCounts): string[] {
  let playlists = `Playlists made: ${c.playlistsMade}`;
  if (c.playlistsUpdated > 0) playlists += `, updated: ${c.playlistsUpdated}`;
  const lines = [`Imported: ${c.imported}`, `Already there: ${c.already}`, skippedText(c.skipped),
    `Missing: ${c.missing}`, playlists];
  if ((c.liked ?? 0) > 0) lines.push(`Added to Liked Songs: ${c.liked}`);
  return lines;
}

/** "Alice (alice)", or just the username when the display name is the same. */
export function userText(u: { username: string; displayName: string }): string {
  return u.displayName === "" || u.displayName === u.username ? u.username : `${u.displayName} (${u.username})`;
}

export function sourceText(source: LibraryImport["source"]): string {
  if (source.path !== undefined) return `From the server: ${source.path}`;
  return "From an uploaded zip file";
}

/** What the section shows for the API's answer. */
export function importView(status: ImportStatus): ImportView {
  const i = status.import ?? undefined;
  const view: ImportView = {
    phase: "idle",
    showForm: true,
    showPathOption: status.pathOption.enabled,
    lines: [],
    actions: [],
    showProblems: false,
  };
  if (status.pathOption.enabled && status.pathOption.allowedPaths.length > 0) {
    view.pathHint = "Allowed: " + status.pathOption.allowedPaths.join(", ");
  }
  if (i !== undefined) {
    switch (i.state) {
      case "checking":
        view.phase = "checking";
        view.heading = "Checking the export…";
        view.lines = checkLines(i.counts, i.smartPlaylists, true);
        view.actions = ["cancel"];
        view.showProblems = true;
        break;
      case "ready":
        view.phase = "ready";
        view.heading = "Checked. Import these?";
        view.lines = checkLines(i.counts, i.smartPlaylists);
        view.actions = ["import", "cancel"];
        view.showProblems = true;
        break;
      case "importing":
      case "paused":
        view.phase = "running";
        view.heading = i.state === "paused" ? "Import paused" : "Importing";
        view.progress = { value: i.counts.done, max: Math.max(1, i.counts.toImport), text: progressText(i) };
        view.lines = progressLines(i.counts);
        view.actions = ["cancelImport"];
        view.showProblems = true;
        break;
      default:
        view.phase = "finished";
        view.heading = i.state === "done" ? "Last import finished"
          : i.state === "cancelled" ? "Last import was cancelled" : "Last import failed";
        if (i.state === "failed" && i.error !== undefined) view.error = i.error;
        view.lines = i.startedAt !== undefined ? resultLines(i.counts) : [];
        view.showProblems = i.startedAt !== undefined;
    }
  }
  if (view.phase === "checking" || view.phase === "ready" || view.phase === "running") {
    view.showForm = false;
  } else if (status.otherImport !== undefined) {
    view.phase = view.phase === "finished" ? "finished" : "other";
    view.otherText = `Another import is running (for ${userText(status.otherImport)}).`;
    view.showForm = false;
  }
  return view;
}

/** How long to wait before asking again: often while something runs, seldom otherwise. */
export function pollDelayMs(status: ImportStatus | undefined): number {
  const state = status?.import?.state;
  if (state === "checking" || state === "importing" || state === "paused" || status?.otherImport !== undefined) {
    return 3000;
  }
  return 15000;
}

const MISSING: Record<string, string> = {
  not_found: "no file for it in the export",
  no_file: "the list names no file for it",
  ambiguous: "several files could be it",
};

/** One problem as a line of text. */
export function problemText(p: ImportProblem): string {
  const who = p.title !== undefined
    ? `“${p.title}”` + (p.artist !== undefined ? ` by ${p.artist}` : "")
    : p.path ?? "A track";
  const where = p.path !== undefined && p.title !== undefined ? ` (${p.path})` : "";
  switch (p.kind) {
    case "missing":
      return `Missing: ${who}: ${MISSING[p.reason ?? ""] ?? reasonText(p.reason)}`;
    case "skipped":
      return `Skipped: ${who}${where}: ${reasonText(p.reason)}`;
    case "liked":
      return `Not added to Liked Songs: ${who}`;
    case "playlist": {
      const name = `“${p.playlist ?? "Untitled playlist"}”`;
      if (p.reason === "too_many_entries") return `Playlist ${name}: too many entries, only the first ones were kept`;
      if (p.reason === "too_many_playlists") return `Playlist ${name} wasn't made: the user has too many playlists`;
      return `Playlist ${name}: ${reasonText(p.reason)}`;
    }
    default:
      return `${who}: ${reasonText(p.reason)}`;
  }
}

/** "Show all 37 problems" when there are more than shown. */
export function moreProblemsText(i: LibraryImport): string | undefined {
  return i.counts.problems > i.problems.length ? `Show all ${i.counts.problems} problems` : undefined;
}

// ------------------------------------------------------------------ the zip upload

/** The localStorage key remembering an unfinished upload of this file (to resume after a reload). */
export function uploadKey(file: { name: string; size: number; lastModified: number }): string {
  return `tt.libimport.upload.${file.size}:${file.lastModified}:${file.name}`;
}

/** The chunk to send next, from what the server has. */
export function nextChunk(receivedBytes: number, chunkSize: number): number {
  return Math.floor(receivedBytes / chunkSize);
}

/** Bytes `[start, end)` of chunk `n`. */
export function chunkRange(n: number, chunkSize: number, size: number): [number, number] {
  const start = n * chunkSize;
  return [start, Math.min(size, start + chunkSize)];
}

/** "1.5 GB", "820 MB", "12 kB". */
export function bytesText(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(bytes >= 1e11 ? 0 : 1)} GB`;
  if (bytes >= 1e6) return `${Math.round(bytes / 1e6)} MB`;
  return `${Math.max(0, Math.round(bytes / 1e3))} kB`;
}

export type UploadPhase = "starting" | "uploading" | "retrying" | "finishing" | "failed";

/** The upload's line under its progress bar. */
export function uploadText(phase: UploadPhase, sent: number, size: number, error?: string): string {
  const pct = size > 0 ? Math.floor((sent / size) * 100) : 100;
  switch (phase) {
    case "starting": return "Starting the upload…";
    case "uploading": return `Uploading: ${bytesText(sent)} of ${bytesText(size)} (${pct}%)`;
    case "retrying": return `Uploading: ${bytesText(sent)} of ${bytesText(size)} (${pct}%). ${error ?? "Connection lost"}; trying again…`;
    case "finishing": return "Upload complete; checking the zip file…";
    default: return `Upload failed: ${error ?? "unknown error"}`;
  }
}

/** Only `.zip` files can be uploaded (the server refuses others too). */
export function isZipName(name: string): boolean {
  return /\.zip$/i.test(name);
}
