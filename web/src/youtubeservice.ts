// The admin's YouTube service box (docs/library-and-ingest.md#youtube-caching): YTSucks's status (reachable,
// the active tool versions, provider health, the last update), Update now, the update log and the
// connection test (GET /api/admin/youtube-service, POST .../update, GET .../update-log, POST .../test).
// No DOM here, so Node tests can import it. The service may be named here (it is the admin's own tool,
// like MVFinder in the settings); the PO token plugin isn't.

/** The active tool versions. */
export interface ServiceVersions {
  ytdlp?: string;
  ytdlpEjs?: string;
  tokenPlugin?: string;
  tokenProvider?: string;
  deno?: string;
  ffmpeg?: string;
  channel?: string;
}

/** `GET /api/admin/youtube-service`. */
export interface ServiceStatus {
  configured: boolean;
  reachable: boolean;
  error?: string;
  versions?: ServiceVersions;
  providers?: { ready: number; total: number };
  updating?: boolean;
  staging?: boolean;
  lastUpdate?: { time?: string; result?: string };
  activeJobs?: number;
}

/** `POST /api/admin/youtube-service/update`. */
export interface UpdateStarted { started: boolean; reason?: string }

/** `GET /api/admin/youtube-service/update-log`; no `log` when there was no update yet. */
export interface UpdateLog { log?: string; truncated: boolean }

/** `POST /api/admin/youtube-service/test`. */
export interface ConnectionTest {
  url?: string;
  reachable: boolean;
  tokenAccepted?: boolean;
  httpStatus?: number;
  error?: string;
}

/** The box's explanation. */
export const INTRO = "YouTube links, searches and downloads go through YTSucks, a separate service that runs yt-dlp and"
  + " keeps it, its token provider and ffmpeg up to date. Set youtube.serviceUrl below, and youtube.serviceToken"
  + " unless this server's address is on the service's allow list. Cookies for YouTube go into the service, not here.";

/** Whether an update is running (the page polls meanwhile and disables the button). */
export function updating(s: ServiceStatus): boolean {
  return s.updating === true;
}

/** The first line: set up or not, reachable or not. */
export function statusText(s: ServiceStatus): string {
  if (!s.configured) return "No YouTube service is set (youtube.serviceUrl), so YouTube links and searches don't work.";
  if (!s.reachable) return "The YouTube service can't be reached" + (s.error === undefined ? "." : ": " + s.error);
  const jobs = s.activeJobs === undefined ? "" : ` ${s.activeJobs} ${s.activeJobs === 1 ? "download" : "downloads"} running.`;
  return (updating(s) ? "The YouTube service is updating its tools…" : "The YouTube service is running.") + jobs;
}

/** One line per known version, generic where a name would be a third-party product. */
export function versionLines(s: ServiceStatus): string[] {
  const v = s.versions;
  if (v === undefined) return [];
  const out: string[] = [];
  const add = (label: string, value: string | undefined, extra?: string): void => {
    if (value !== undefined && value !== "") out.push(label + " " + value + (extra ?? ""));
  };
  add("yt-dlp", v.ytdlp, v.channel === undefined ? undefined : ` (${v.channel})`);
  add("yt-dlp challenge solver", v.ytdlpEjs);
  add("Token provider plugin", v.tokenPlugin);
  add("Token provider", v.tokenProvider);
  add("JavaScript runtime", v.deno);
  add("ffmpeg", v.ffmpeg);
  return out;
}

/** The token providers' health, or undefined if unknown. */
export function providerText(s: ServiceStatus): string | undefined {
  const p = s.providers;
  if (p === undefined) return undefined;
  if (p.total === 0) return "No token provider is running.";
  return p.ready === p.total ? "Token provider ready." : `Token providers ready: ${p.ready} of ${p.total}.`;
}

/** The last update's line; `warning` when even the newest tools fail. Undefined if there was none. */
export function lastUpdateText(s: ServiceStatus): { text: string; warning: boolean } | undefined {
  const u = s.lastUpdate;
  if (u === undefined || u.result === undefined) return undefined;
  const when = u.time === undefined ? "Last update" : "Last update " + u.time;
  switch (u.result) {
    case "updated": return { text: when + ": updated to newer tools.", warning: false };
    case "up_to_date": return { text: when + ": already up to date.", warning: false };
    case "rejected": return { text: when + ": newer tools failed their self-test, so the current ones stay.", warning: false };
    case "failing":
      return { text: when + ": even the newest tools fail their self-test; YouTube is probably broken until a fix ships.",
        warning: true };
    default: return { text: when + ": " + u.result + ".", warning: false };
  }
}

/** What Update now says. */
export function startedText(u: UpdateStarted): string {
  if (u.started) return "Update started; the new tools are tested before they are used.";
  return "No update started" + (u.reason === undefined ? "." : ": " + u.reason + ".");
}

/** The update log for the `<pre>`. */
export function logText(l: UpdateLog): string {
  if (l.log === undefined) return "The YouTube service hasn't updated its tools yet.";
  return (l.truncated ? "(only the end of the log)\n" : "") + l.log;
}

/** Test connection's result. `ok` only when the service answers and accepts the token. */
export function testText(t: ConnectionTest): { ok: boolean; text: string } {
  const where = t.url === undefined ? "the YouTube service" : "the YouTube service at " + t.url;
  if (!t.reachable) {
    return { ok: false, text: `Can't reach ${where}: ${t.error ?? "no answer"}.` };
  }
  if (t.tokenAccepted === true) return { ok: true, text: `OK: ${where} answers and accepts this server.` };
  if (t.tokenAccepted === false) {
    return { ok: false, text: `${where.charAt(0).toUpperCase()}${where.slice(1)} answers but refuses the token: set`
      + " youtube.serviceToken to one of its tokens, or put this server's address on its allow list." };
  }
  return { ok: false, text: t.error ?? `${where} gave an unexpected answer.` };
}
