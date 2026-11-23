// Find a music video (docs/library-and-ingest.md#linked-media): the texts of the candidates
// dialog, the pick's body and what the pick did. Pure logic (no DOM), run under Node in the tests
// (src/test/resources/web-tests/videosearch.test.mjs); songmenu.ts draws the dialog.

/** One candidate of `POST /api/items/{id}/video-search`. */
export interface VideoCandidate {
  videoId: string;
  title: string;
  channel?: string;
  durationMs?: number;
  /** The match type when it came from the music-service lookup (`music_video`, `lyric_video`, …). */
  type?: string;
  /** An https thumbnail on YouTube's image hosts (the server checks it); the page loads it directly. */
  thumbnailUrl?: string;
}

/** The candidate's buttons. */
export const PREVIEW_LABEL = "Preview on YT";
export const USE_LABEL = "Use this video";

/** The watch page a candidate's **Preview on YT** opens (in a new tab). */
export function previewUrl(videoId: string): string {
  return "https://www.youtube.com/watch?v=" + encodeURIComponent(videoId);
}

/** The thumbnail to show: the server's, else YouTube's own for the video ID. */
export function thumbnailSrc(c: Pick<VideoCandidate, "videoId" | "thumbnailUrl">): string {
  return c.thumbnailUrl !== undefined && c.thumbnailUrl !== "" ? c.thumbnailUrl
    : "https://i.ytimg.com/vi/" + encodeURIComponent(c.videoId) + "/hqdefault.jpg";
}

/** A candidate row's texts: the title, the line under it (uploader · length · type) and the buttons' labels. */
export function candidateRow(c: VideoCandidate): { title: string; detail: string; preview: string; use: string } {
  return { title: c.title, detail: candidateDetail(c), preview: PREVIEW_LABEL, use: USE_LABEL };
}

/** `POST /api/items/{id}/video-search/pick`'s answer. */
export interface PickResult {
  itemId: string;
  status: string;
  linked: boolean;
  linkType: "music_video" | "lyric_video";
}

/** Shown when the search found nothing. */
export const NO_VIDEO_FOUND = "No music video found.";

/** A match type's name, or undefined for a plain search result (or an unknown type). */
export function candidateTypeLabel(type: string | undefined): string | undefined {
  switch (type) {
    case "music_video": return "Music Video";
    case "lyric_video": return "Lyric Video";
    case "official_video": return "Official video";
    case "visualizer": return "Visualizer";
    default: return undefined;
  }
}

/** A length as "3:51" (or "1:02:03"); empty when unknown. */
export function lengthText(ms: number | undefined): string {
  if (ms === undefined || !(ms > 0)) return "";
  const total = Math.round(ms / 1000);
  const s = total % 60;
  const m = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  const ss = (s < 10 ? "0" : "") + s;
  return hours > 0 ? hours + ":" + (m < 10 ? "0" : "") + m + ":" + ss : m + ":" + ss;
}

/** The line under a candidate's title: uploader (channel) · length · type, the known parts only. */
export function candidateDetail(c: VideoCandidate): string {
  return [c.channel ?? "", lengthText(c.durationMs), candidateTypeLabel(c.type) ?? ""]
    .filter((p) => p !== "").join(" · ");
}

/** The pick's body. */
export function pickBody(c: Pick<VideoCandidate, "videoId">): { videoId: string } {
  return { videoId: c.videoId };
}

/** What the pick did to the song, for the notice. */
export function pickedText(r: PickResult, songTitle: string): string {
  const as = r.linkType === "lyric_video" ? "Lyric Video" : "Music Video";
  return r.linked ? "Linked to " + songTitle + " as " + as + "."
    : "Not linked: " + songTitle + " already has the most linked media it can have.";
}

/** The search's error, for the dialog. */
export function searchErrorText(code: string | undefined, fallback: string): string {
  switch (code) {
    case "too_many_searches": return "Too many searches in the past hour. Try again later.";
    case "feature_disabled": return "Adding videos from links is turned off on this server.";
    case "not_authenticated": return "Log in to find a music video.";
    case "not_a_candidate": return "That video isn't available any more. Search again.";
    default: return fallback;
  }
}
