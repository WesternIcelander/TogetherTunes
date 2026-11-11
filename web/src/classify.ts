// Classify on library add (docs/media-types.md#media-types), without the DOM so a new UI can reuse
// it: an `uncategorized` video needs a media type before it goes into a library. The server
// answers such an add with `409 needs_classification {itemId, suggested?}`; the client asks and
// adds again with `{mediaType, show?}` (PUT /api/library/{itemId}, or the same share). The server
// enforces the same rules.
import { MEDIA_TYPES, mediaTypeBody, type MediaType, type ShowInfo } from "./mediatypes.js";

/** What the server suggests: a type and, for `show`, what is known of the show. */
export interface Suggestion {
  mediaType: MediaType;
  show?: { name?: string; season?: number; episode?: number };
}

/** A refused library add: the item to classify and the suggestion, if any. */
export interface ClassifyNeeded { itemId: string; suggested?: Suggestion }

/** The body part that classifies: `{mediaType, show?}`. */
export interface ClassifyBody { mediaType: MediaType; show?: ShowInfo }

/** The types an adder can choose: everything but `uncategorized`. */
export const CLASSIFY_TYPES: readonly MediaType[] = MEDIA_TYPES.filter((t) => t !== "uncategorized");

/**
 * The classify question in an API error (`409 needs_classification`, an `ApiError` whose
 * `details` is the error object), or `undefined` for any other error.
 */
export function needsClassification(e: unknown): ClassifyNeeded | undefined {
  if (typeof e !== "object" || e === null) return undefined;
  const err = e as { status?: unknown; code?: unknown; details?: unknown };
  if (err.status !== 409 || err.code !== "needs_classification") return undefined;
  const details = err.details as { itemId?: unknown; suggested?: unknown } | undefined;
  if (details === undefined || typeof details.itemId !== "string") return undefined;
  const out: ClassifyNeeded = { itemId: details.itemId };
  const s = details.suggested as { mediaType?: unknown; show?: unknown } | undefined;
  if (s !== undefined && s !== null && typeof s.mediaType === "string"
      && (CLASSIFY_TYPES as readonly string[]).includes(s.mediaType)) {
    const suggestion: Suggestion = { mediaType: s.mediaType as MediaType };
    const show = s.show as { name?: unknown; season?: unknown; episode?: unknown } | undefined;
    if (suggestion.mediaType === "show" && show !== undefined && show !== null) {
      const prefill: { name?: string; season?: number; episode?: number } = {};
      if (typeof show.name === "string") prefill.name = show.name;
      if (typeof show.season === "number") prefill.season = show.season;
      if (typeof show.episode === "number") prefill.episode = show.episode;
      suggestion.show = prefill;
    }
    out.suggested = suggestion;
  }
  return out;
}

/** The form's starting values from a suggestion: the type preselected, show fields prefilled. */
export function classifyDefaults(s: Suggestion | undefined):
    { mediaType: string; showName: string; season: string; episode: string } {
  const show = s?.mediaType === "show" ? s.show : undefined;
  return {
    mediaType: s?.mediaType ?? "",
    showName: show?.name ?? "",
    season: show?.season === undefined ? "" : String(show.season),
    episode: show?.episode === undefined ? "" : String(show.episode),
  };
}

/** The classify body from the form's values, or an error text (a type must be chosen). */
export function classifyBody(type: string, showName: string, season: string, episode: string):
    { body: ClassifyBody } | { error: string } {
  if (!(CLASSIFY_TYPES as readonly string[]).includes(type)) return { error: "Choose what kind of video this is." };
  return mediaTypeBody(type, showName, season, episode);
}
