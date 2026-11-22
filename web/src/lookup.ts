// Looking song details up online (docs/library-and-ingest.md#looking-details-up-online): DOM-free,
// so it can be unit tested. What the review shows (each field with its current and found value, a
// checkbox per field: checked where the item has no value, unchecked where it differs, none where
// they're the same), the PATCH body Apply sends, and how the album picker lists the albums found.
// The server checks everything again.
import { ARTIST_SEPARATOR, countRow, type SongDetails } from "./songdetails.js";

/** The found details (`details` of `POST /api/items/{id}/lookup`), or an item's own. */
export interface LookupDetails extends SongDetails {
  title?: string;
  album?: string;
}

/** An album that has the track (a lookup without an album). */
export interface AlbumCandidate {
  album: string;
  albumArtist?: string;
  title?: string;
  artist?: string;
  date?: string;
  track?: number;
  trackTotal?: number;
}

/** `POST /api/items/{id}/lookup`. */
export type LookupResult =
  | { result: "found"; details: LookupDetails; lookupToken: string; namesOnly?: boolean; notice?: string;
    cover?: { url: string; width?: number; height?: number } }
  | { result: "albums"; albums: AlbumCandidate[]; notice?: string }
  | { result: "none"; notice?: string };

/** One row of the review. `field` names what Apply sets (`track` sets track and its total). */
export interface ReviewRow {
  field: ReviewField;
  label: string;
  current: string;
  found: string;
  /** The item already has exactly this: no checkbox. */
  same: boolean;
  /** Whether the checkbox starts checked: the item has no value. */
  checked: boolean;
}

export type ReviewField = "title" | "artists" | "album" | "albumArtist" | "track" | "disc" | "date" | "genre"
  | "label" | "copyright" | "isrc" | "barcode";

/** The review's fields in order, with their labels. */
const FIELDS: [ReviewField, string][] = [
  ["title", "Title"], ["artists", "Artists"], ["album", "Album"], ["albumArtist", "Album artist"],
  ["track", "Track"], ["disc", "Disc"], ["date", "Release date"], ["genre", "Genre"], ["label", "Label"],
  ["copyright", "Copyright"], ["isrc", "ISRC"], ["barcode", "Barcode"],
];

/** The artists as one line: the list joined, else the artist. */
function artistsText(d: LookupDetails): string {
  if (d.artists !== undefined && d.artists.length > 1) return d.artists.join(ARTIST_SEPARATOR);
  return d.artist ?? "";
}

/** A number with its total ("1 of 2", "1", "of 2"), "" for neither. */
function numberText(n: number | undefined, total: number | undefined): string {
  const row = countRow("", "", n, total);
  if (row === undefined) return "";
  return n === undefined ? "of " + row[1] : row[1];
}

/** A field's value as shown. */
export function fieldText(d: LookupDetails, field: ReviewField): string {
  switch (field) {
    case "artists": return artistsText(d);
    case "track": return numberText(d.track, d.trackTotal);
    case "disc": return numberText(d.disc, d.discTotal);
    default: {
      const v = d[field];
      return v === undefined ? "" : String(v);
    }
  }
}

/** Whether the item has the same as found (the artists: the same string and the same list). */
function sameValue(item: LookupDetails, found: LookupDetails, field: ReviewField): boolean {
  if (field === "artists") {
    const a = item.artists ?? [];
    const b = found.artists ?? [];
    return (item.artist ?? "") === (found.artist ?? "") && a.length === b.length && a.every((n, i) => n === b[i]);
  }
  return fieldText(item, field) === fieldText(found, field);
}

/**
 * The review: a row for every field the lookup found, in order. Checked where the item has no value,
 * unchecked where it has another one, and no checkbox where it's the same (`same`).
 */
export function reviewRows(item: LookupDetails, found: LookupDetails): ReviewRow[] {
  const rows: ReviewRow[] = [];
  for (const [field, label] of FIELDS) {
    const f = fieldText(found, field);
    if (f === "") continue;
    const current = fieldText(item, field);
    const same = sameValue(item, found, field);
    rows.push({ field, label, current, found: f, same, checked: !same && current === "" });
  }
  return rows;
}

/** The largest image Replace artwork sends (the server's limit, `CoverUpload.MAX_BYTES`). */
export const ARTWORK_MAX_BYTES = 20 * 1024 * 1024;

/** The image types Replace artwork takes (the file picker's `accept`). */
export const ARTWORK_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;

/**
 * Why a picked file can't be the new artwork, or undefined when it can be sent. An empty type (some
 * pickers give none) is left to the server, which checks the bytes anyway.
 */
export function artworkProblem(file: { type: string; size: number }): string | undefined {
  if (file.size === 0) return "The file is empty.";
  if (file.size > ARTWORK_MAX_BYTES) return "The image is larger than 20 MB.";
  if (file.type !== "" && (ARTWORK_TYPES as readonly string[]).indexOf(file.type) < 0) {
    return "Pick a JPEG, PNG or WebP image.";
  }
  return undefined;
}

/** Whether the found cover starts checked: only when the item has none. */
export function coverChecked(itemHasCover: boolean): boolean {
  return !itemHasCover;
}

/**
 * The `PATCH /api/items/{id}` body that applies the checked fields (with the lookup's token, so the
 * server keeps where they came from); null when nothing is checked. The artists set both the list
 * and the one-string artist, as found ("A feat. B" stays as it is).
 */
export function applyBody(found: LookupDetails, checked: readonly ReviewField[], lookupToken: string):
  Record<string, unknown> | null {
  const body: Record<string, unknown> = {};
  for (const field of checked) {
    switch (field) {
      case "artists":
        if (found.artists !== undefined && found.artists.length > 1) body.artists = found.artists.slice();
        if (found.artist !== undefined) body.artist = found.artist;
        else if (found.artists !== undefined) body.artist = found.artists.join(ARTIST_SEPARATOR);
        break;
      case "track":
        if (found.track !== undefined) body.track = found.track;
        if (found.trackTotal !== undefined) body.trackTotal = found.trackTotal;
        break;
      case "disc":
        if (found.disc !== undefined) body.disc = found.disc;
        if (found.discTotal !== undefined) body.discTotal = found.discTotal;
        break;
      default:
        if (found[field] !== undefined) body[field] = found[field];
    }
  }
  if (Object.keys(body).length === 0) return null;
  body.lookupToken = lookupToken;
  return body;
}

/** The year of a release date ("2009-02-24" → "2009"), or "". */
export function yearOf(date: string | undefined): string {
  const m = date === undefined ? null : /^(\d{4})/.exec(date);
  return m === null ? "" : (m[1] as string);
}

/**
 * How the album picker lists an album: its name, then "album artist · year · track 7 of 18" with
 * the parts that are known.
 */
export function candidateText(c: AlbumCandidate): { name: string; detail: string } {
  const parts: string[] = [];
  if (c.albumArtist !== undefined && c.albumArtist !== "") parts.push(c.albumArtist);
  const year = yearOf(c.date);
  if (year !== "") parts.push(year);
  const track = numberText(c.track, c.trackTotal);
  if (track !== "") parts.push("track " + track);
  return { name: c.album, detail: parts.join(" · ") };
}

/** The body of the lookup again with a picked album. */
export function pickBody(c: AlbumCandidate): { album: string; albumArtist?: string } {
  return c.albumArtist !== undefined ? { album: c.album, albumArtist: c.albumArtist } : { album: c.album };
}

/** "1600 × 1600" for a cover's size, "" when unknown. */
export function sizeText(width: number | undefined, height: number | undefined): string {
  return width !== undefined && height !== undefined ? width + " × " + height : "";
}

/** What the page says when nothing was found. */
export const NOTHING_FOUND = "No details found";
