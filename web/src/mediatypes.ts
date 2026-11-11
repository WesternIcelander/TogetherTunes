// Media types (docs/media-types.md): the names, labels and the rules of an edit, without the DOM,
// so a new UI can reuse them. The server enforces the same rules (PATCH /api/items/{id}).

export type MediaType = "audio" | "uncategorized" | "music_video" | "movie" | "show";

/** A show episode's details (type `show` only). */
export interface ShowInfo { name: string; season?: number; episode: number }

/** Every media type, in the order a picker lists them. */
export const MEDIA_TYPES: readonly MediaType[] = ["uncategorized", "music_video", "movie", "show", "audio"];

const LABELS: Record<MediaType, string> = {
  audio: "Audio",
  uncategorized: "Video (uncategorized)",
  music_video: "Music video",
  movie: "Movie",
  show: "Show episode",
};

export function mediaTypeLabel(type: string | undefined): string {
  return type !== undefined && Object.prototype.hasOwnProperty.call(LABELS, type) ? LABELS[type as MediaType] : "Video";
}

/** The types an item of `kind` can have: an audio item is always `audio`. */
export function allowedTypes(kind: "audio" | "video"): readonly MediaType[] {
  return kind === "audio" ? ["audio"] : MEDIA_TYPES;
}

/** A whole number ≥ 0 from a form field, `null` for blank, `undefined` if invalid. */
function number(raw: string): number | null | undefined {
  const s = raw.trim();
  if (s === "") return null;
  if (!/^\d{1,7}$/.test(s)) return undefined;
  return Number(s);
}

/**
 * The `mediaType` / `show` part of a PATCH body from form values, or an error text. Show details
 * only go with type `show` (the server clears them for other types).
 */
export function mediaTypeBody(type: string, showName: string, season: string, episode: string):
    { body: { mediaType: MediaType; show?: ShowInfo } } | { error: string } {
  if (!(MEDIA_TYPES as readonly string[]).includes(type)) return { error: "Choose a media type." };
  const mediaType = type as MediaType;
  if (mediaType !== "show") return { body: { mediaType } };
  const name = showName.trim();
  if (name === "") return { error: "A show episode needs the show's name." };
  const s = number(season);
  if (s === undefined) return { error: "The season must be a whole number (0 or more), or empty." };
  const e = number(episode);
  if (e === undefined || e === null) return { error: "The episode must be a whole number (0 or more)." };
  const show: ShowInfo = s === null ? { name, episode: e } : { name, season: s, episode: e };
  return { body: { mediaType, show } };
}
