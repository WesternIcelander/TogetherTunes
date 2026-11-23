// YouTube search results (docs/ui.md#header-search, the search page): who may search, the row
// texts (thumbnail, title, "uploader · length", Preview on YT), which actions a row offers (Add to
// Queue in a room that takes YouTube links from this person, Add to library… for anyone who may
// search) and the texts after an action or an error. Pure logic (no DOM), run under Node in the
// tests (src/test/resources/web-tests/ytsearch.test.mjs); ytrows.ts draws the rows.
import { lengthText, PREVIEW_LABEL, previewUrl, thumbnailSrc, type VideoCandidate } from "./videosearch.js";

/** One result of `GET /api/youtube/search`. */
export type YoutubeResult = VideoCandidate;

/** Results the search page asks for (the server's default). */
export const PAGE_LIMIT = 10;
/** Results the dropdown asks for. */
export const DROPDOWN_YOUTUBE_LIMIT = 8;

export const YOUTUBE_SECTION_TITLE = "YouTube";
export const NO_YOUTUBE_RESULTS = "Nothing found on YouTube.";
export const ADD_TO_QUEUE_LABEL = "Add to Queue";
export const ADD_TO_LIBRARY_LABEL = "Add to library…";

/** `GET /api/youtube/search` for `q`. */
export function youtubeSearchPath(q: string, limit: number = PAGE_LIMIT): string {
  return "/api/youtube/search?" + new URLSearchParams({ q: q.trim(), limit: String(limit) }).toString();
}

/**
 * Whether this person may search YouTube: signed in with `library.youtube` (the server leaves it
 * out of `/api/me` while `ytdlp.enabled` is off).
 */
export function canSearchYoutube(me: { kind: string; permissions?: readonly string[] } | undefined): boolean {
  return me !== undefined && me.kind === "user" && (me.permissions ?? []).indexOf("library.youtube") >= 0;
}

/** The watch page a result's link points at (Preview on YT, drags, Add to Queue, Add to library…). */
export function watchUrl(r: Pick<YoutubeResult, "videoId">): string {
  return previewUrl(r.videoId);
}

/** The line under a result's title: uploader · length, the known parts only. */
export function youtubeDetail(r: YoutubeResult): string {
  return [r.channel ?? "", lengthText(r.durationMs)].filter((p) => p !== "").join(" · ");
}

/** A result row's texts. */
export function youtubeRow(r: YoutubeResult): { title: string; detail: string; preview: string; thumbnail: string;
  url: string } {
  return { title: r.title, detail: youtubeDetail(r), preview: PREVIEW_LABEL, thumbnail: thumbnailSrc(r),
    url: watchUrl(r) };
}

export type YoutubeAction = "addToQueue" | "addToLibrary";

export const YOUTUBE_ACTION_LABELS: Readonly<Record<YoutubeAction, string>> = {
  addToQueue: ADD_TO_QUEUE_LABEL,
  addToLibrary: ADD_TO_LIBRARY_LABEL,
};

/** The room open here, as far as a result's actions care. */
export interface YoutubeRoomLike {
  you: { canYoutube: boolean; restriction?: unknown };
}

/**
 * A result's actions: **Add to Queue** when a room is open here and the person may request YouTube
 * links in it (not banned or timed out there), then **Add to library…** (anyone who may search may
 * import YouTube links).
 */
export function youtubeActions(room: YoutubeRoomLike | undefined): YoutubeAction[] {
  const out: YoutubeAction[] = [];
  if (room !== undefined && room.you.canYoutube && room.you.restriction === undefined) out.push("addToQueue");
  out.push("addToLibrary");
  return out;
}

/**
 * What Add to Queue did, for the notice; `link`: a YouTube link (a new video plays once it has been
 * downloaded), else a library item.
 */
export function queuedText(outcome: "added" | "suggested" | "playing", title: string, roomName: string,
    link = true): string {
  switch (outcome) {
    case "suggested": return title + " suggested to " + roomName + ".";
    case "playing": return "Playing " + title + ".";
    default: return title + " added to " + roomName + "."
      + (link ? " New videos play once they have been downloaded." : "");
  }
}

/** A YouTube search's error. */
export function youtubeErrorText(code: string | undefined, fallback: string): string {
  switch (code) {
    case "too_many_searches": return "Too many YouTube searches in the past hour. Try again later.";
    case "feature_disabled": return "Adding videos from links is turned off on this server.";
    case "not_authenticated": return "Log in to search YouTube.";
    default: return fallback;
  }
}
