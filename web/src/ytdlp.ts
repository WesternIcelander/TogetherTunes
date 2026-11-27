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

/** Whether yt-dlp loaded the PO token provider plugin that uses the provider URL (and its version). */
export interface PoTokenPlugin { loaded: boolean; version?: string }

/** `GET /api/admin/ytdlp`. */
export interface YtdlpInfo { available: boolean; version?: string; update?: YtdlpUpdate; poTokenPlugin?: PoTokenPlugin }

/** `POST /api/admin/ytdlp/po-token-test`: the provider server's ping, and the plugin checked again. */
export interface PoTokenTest {
  url: string;
  /** No URL was entered or stored, so the plugin's default was tested. */
  defaultUrl: boolean;
  reachable: boolean;
  httpStatus?: number;
  version?: string;
  error?: string;
  poTokenPlugin?: PoTokenPlugin;
}

/** The message when yt-dlp has no token provider plugin (the deployment notes say how to install it). */
export const PLUGIN_MISSING = "yt-dlp has no PO token provider plugin installed: see the deployment notes.";

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

/** The PO token plugin line, or undefined while it isn't known (no yt-dlp, or the check couldn't run). */
export function pluginText(info: YtdlpInfo): string | undefined {
  if (!info.available || info.poTokenPlugin === undefined) return undefined;
  const p = info.poTokenPlugin;
  if (!p.loaded) return PLUGIN_MISSING;
  return "yt-dlp has a PO token provider plugin" + (p.version === undefined ? "" : ` (version ${p.version})`) + ".";
}

/**
 * The Test button's result: what to fix first (the plugin, then the server), or OK with the provider's
 * version. `ok` is true only when both work.
 */
export function testText(t: PoTokenTest): { ok: boolean; text: string } {
  const where = t.defaultUrl ? `the default PO token provider URL ${t.url} (none is set)` : t.url;
  const server = t.reachable
    ? `The PO token provider at ${where} answers` + (t.version === undefined ? "." : ` (version ${t.version}).`)
    : `The PO token provider at ${where} can't be reached: ${t.error ?? "no answer"}.`
      + " Check that the provider server is running and that the URL is right.";
  if (t.poTokenPlugin !== undefined && !t.poTokenPlugin.loaded) {
    return { ok: false, text: PLUGIN_MISSING + " " + server };
  }
  if (!t.reachable) return { ok: false, text: server };
  const unknown = t.poTokenPlugin === undefined ? " Whether yt-dlp has the token provider plugin couldn't be checked." : "";
  return { ok: t.poTokenPlugin !== undefined, text: (t.poTokenPlugin !== undefined ? "OK. " : "") + server + unknown };
}
