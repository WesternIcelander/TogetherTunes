// The admin's yt-dlp box (docs/library-and-ingest.md: "Keep yt-dlp up to date"): the installed version and
// the "Update yt-dlp" button's run (GET /api/admin/ytdlp, POST /api/admin/ytdlp/update). No DOM here, so
// Node tests can import it.

/** One `yt-dlp -U` run. */
export interface YtdlpUpdate {
  state: "running" | "succeeded" | "failed";
  startedAt: number;
  finishedAt?: number;
  exitCode?: number;
  versionBefore?: string;
  versionAfter?: string;
  log: string;
}

/** `GET /api/admin/ytdlp`. */
export interface YtdlpInfo { available: boolean; version?: string; update?: YtdlpUpdate }

/** Whether an update is running (the page polls meanwhile and disables the button). */
export function updating(info: YtdlpInfo): boolean {
  return info.update !== undefined && info.update.state === "running";
}

/** The version line. */
export function versionText(info: YtdlpInfo): string {
  if (!info.available) return "yt-dlp isn't installed on the server, so YouTube links don't work.";
  return info.version === undefined ? "yt-dlp is installed; its version couldn't be read." : "yt-dlp version " + info.version + ".";
}

/** One line about the current or last update, or undefined if there was none since the server started. */
export function updateText(u: YtdlpUpdate | undefined): string | undefined {
  if (u === undefined) return undefined;
  if (u.state === "running") return "Updating…";
  if (u.state === "failed") {
    return "The update failed" + (u.exitCode === undefined ? "" : ` (exit code ${u.exitCode})`)
      + ". See the output below.";
  }
  if (u.versionAfter !== undefined && u.versionBefore !== undefined && u.versionAfter !== u.versionBefore) {
    return `Updated from ${u.versionBefore} to ${u.versionAfter}.`;
  }
  if (u.versionAfter !== undefined && u.versionAfter === u.versionBefore) {
    return `Already up to date (${u.versionAfter}).`;
  }
  return "The update finished.";
}
