// Song details on the item page (docs/library-and-ingest.md#song-details): DOM-free, so it can be
// unit tested. The Artists list (one field edited as a list of names, never separate Artist and
// Artists fields), the form's validation (the server's rules, shown inline before saving) and how
// the details read on the page. The server checks everything again.

/** The song details of an item page (`GET /api/items/{id}`); each one absent when unknown. */
export interface SongDetails {
  artist?: string;
  /** Each of the track's artists; only with two or more names. */
  artists?: string[];
  albumArtist?: string;
  track?: number;
  trackTotal?: number;
  disc?: number;
  discTotal?: number;
  isrc?: string;
  date?: string;
  genre?: string;
  copyright?: string;
  label?: string;
  barcode?: string;
}

/** The form's text fields besides the artists (as typed). */
export interface DetailsInput {
  albumArtist: string;
  track: string;
  trackTotal: string;
  disc: string;
  discTotal: string;
  isrc: string;
  date: string;
  genre: string;
  copyright: string;
  label: string;
  barcode: string;
}

/** The longest text the server keeps (UploadMetadata.MAX_CHARS), in code points. */
export const MAX_CHARS = 300;
/** Largest track or disc number and total (SongDetails.MAX_NUMBER). */
export const MAX_NUMBER = 9999;
/** Most artists in the list (SongDetails.MAX_ARTISTS). */
export const MAX_ARTISTS = 50;
/** Between the names in the item's one-string artist. */
export const ARTIST_SEPARATOR = ", ";

/** Whitespace and control characters become one space, trimmed (like the server's clean). */
export function cleanText(s: string): string {
  // eslint-disable-next-line no-control-regex
  return s.replace(/[\s\u0000-\u001f\u007f-\u009f]+/g, " ").trim();
}

function codePoints(s: string): number {
  return Array.from(s).length;
}

/**
 * The names the Artists field starts with: the item's list when it has one, else its artist as a
 * single name. Never split on commas by guesswork ("Earth, Wind & Fire" is one name).
 */
export function initialArtists(item: SongDetails): string[] {
  if (item.artists !== undefined && item.artists.length > 0) return item.artists.slice();
  return item.artist !== undefined && item.artist !== "" ? [item.artist] : [];
}

/** The names cleaned, blanks and repeats dropped, in order. */
export function cleanNames(names: readonly string[]): string[] {
  const out: string[] = [];
  for (const n of names) {
    const c = cleanText(n);
    if (c !== "" && out.indexOf(c) < 0) out.push(c);
  }
  return out;
}

/** The names as the item's one-string artist ("A, B"); "" for none. */
export function joinArtists(names: readonly string[]): string {
  return cleanNames(names).join(ARTIST_SEPARATOR);
}

/** Whether the edited names differ from what the field started with (only then are they saved). */
export function artistsChanged(item: SongDetails, names: readonly string[]): boolean {
  const before = cleanNames(initialArtists(item));
  const after = cleanNames(names);
  return before.length !== after.length || before.some((n, i) => n !== after[i]);
}

/** The list with a new empty name at the end. */
export function addName(names: readonly string[]): string[] {
  return names.concat([""]);
}

/** The list without the name at `index`. */
export function removeName(names: readonly string[], index: number): string[] {
  return names.filter((_, i) => i !== index);
}

/** The list with the name at `index` moved by `delta` places (kept within the list). */
export function moveName(names: readonly string[], index: number, delta: number): string[] {
  const to = Math.max(0, Math.min(names.length - 1, index + delta));
  if (index < 0 || index >= names.length || to === index) return names.slice();
  const out = names.slice();
  const [moved] = out.splice(index, 1);
  out.splice(to, 0, moved as string);
  return out;
}

/** An ISRC upper case without hyphens or spaces, or undefined if it isn't one. */
export function normalizeIsrc(s: string): string | undefined {
  const v = s.replace(/[\s-]/g, "").toUpperCase();
  return /^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/.test(v) ? v : undefined;
}

/** A barcode (UPC/EAN) of 8 to 14 digits without spaces or hyphens, or undefined. */
export function normalizeBarcode(s: string): string | undefined {
  const v = s.replace(/[\s-]/g, "");
  return /^[0-9]{8,14}$/.test(v) ? v : undefined;
}

/** Whether `s` is a release date YYYY, YYYY-MM or YYYY-MM-DD (a real day). */
export function validDate(s: string): boolean {
  const m = /^(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?$/.exec(s);
  if (m === null) return false;
  const year = Number(m[1]);
  if (year < 1) return false;
  if (m[2] === undefined) return true;
  const month = Number(m[2]);
  if (month < 1 || month > 12) return false;
  if (m[3] === undefined) return true;
  const day = Number(m[3]);
  const days = new Date(Date.UTC(2000, month, 0)).getUTCDate(); // a leap year: Feb has 29
  if (day < 1 || day > days) return false;
  if (month === 2 && day === 29) return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  return true;
}

/** A track/disc number or total as typed: undefined for blank, a number, or an error message. */
function parseNumber(s: string): { value: number | null } | { error: string } {
  const v = s.trim();
  if (v === "") return { value: null };
  if (!/^[0-9]+$/.test(v) || Number(v) < 1 || Number(v) > MAX_NUMBER) {
    return { error: "A whole number from 1 to " + MAX_NUMBER };
  }
  return { value: Number(v) };
}

/** Field name → message, for the fields with a problem. */
export type FieldErrors = Partial<Record<keyof DetailsInput | "artists", string>>;

/**
 * The PATCH body's song details from the form (blank = null, which clears), or the problems to
 * show beside the fields. `artists` goes in only when the names were edited (then the server sets
 * the item's artist to them joined); otherwise the item keeps its artist as it is.
 */
export function detailsBody(item: SongDetails, names: readonly string[], input: DetailsInput):
  { body: Record<string, unknown> } | { errors: FieldErrors } {
  const errors: FieldErrors = {};
  const body: Record<string, unknown> = {};
  const cleaned = cleanNames(names);
  if (cleaned.length > MAX_ARTISTS) {
    errors.artists = "At most " + MAX_ARTISTS + " artists";
  } else if (cleaned.some((n) => codePoints(n) > MAX_CHARS) || codePoints(cleaned.join(ARTIST_SEPARATOR)) > MAX_CHARS) {
    errors.artists = "The names together can have at most " + MAX_CHARS + " characters";
  } else if (artistsChanged(item, cleaned)) {
    body.artists = cleaned;
  }
  for (const f of ["albumArtist", "genre", "copyright", "label"] as const) {
    const v = cleanText(input[f]);
    if (codePoints(v) > MAX_CHARS) errors[f] = "At most " + MAX_CHARS + " characters";
    else body[f] = v === "" ? null : v;
  }
  for (const f of ["track", "trackTotal", "disc", "discTotal"] as const) {
    const n = parseNumber(input[f]);
    if ("error" in n) errors[f] = n.error;
    else body[f] = n.value;
  }
  const isrc = input.isrc.trim();
  if (isrc === "") body.isrc = null;
  else if (normalizeIsrc(isrc) === undefined) errors.isrc = "12 letters and digits, like JPVI02201530";
  else body.isrc = normalizeIsrc(isrc);
  const date = input.date.trim();
  if (date === "") body.date = null;
  else if (!validDate(date)) errors.date = "YYYY, YYYY-MM or YYYY-MM-DD";
  else body.date = date;
  const barcode = input.barcode.trim();
  if (barcode === "") body.barcode = null;
  else if (normalizeBarcode(barcode) === undefined) errors.barcode = "8 to 14 digits";
  else body.barcode = normalizeBarcode(barcode);
  return Object.keys(errors).length > 0 ? { errors } : { body };
}

/** The form's starting values for an item. */
export function detailsInput(item: SongDetails): DetailsInput {
  const n = (v: number | undefined): string => (v === undefined ? "" : String(v));
  return {
    albumArtist: item.albumArtist ?? "", track: n(item.track), trackTotal: n(item.trackTotal), disc: n(item.disc),
    discTotal: n(item.discTotal), isrc: item.isrc ?? "", date: item.date ?? "", genre: item.genre ?? "",
    copyright: item.copyright ?? "", label: item.label ?? "", barcode: item.barcode ?? "",
  };
}

/**
 * A track or disc number with its total as a label and value: ["Track", "1 of 2"], ["Track", "1"],
 * or with only the total ["Tracks", "2"]; undefined when neither is known.
 */
export function countRow(one: string, many: string, n: number | undefined, total: number | undefined):
  [string, string] | undefined {
  if (n !== undefined) return [one, total !== undefined ? n + " of " + total : String(n)];
  return total !== undefined ? [many, String(total)] : undefined;
}

/**
 * The details as label/value rows for the item page, in order, only the known ones. Artists shows
 * the names joined, and only when that isn't already the artist line (e.g. "A feat. B").
 */
export function detailRows(item: SongDetails): [string, string][] {
  const rows: [string, string][] = [];
  const add = (label: string, value: string | undefined): void => {
    if (value !== undefined && value !== "") rows.push([label, value]);
  };
  if (item.artists !== undefined && item.artists.length > 1) {
    const joined = item.artists.join(ARTIST_SEPARATOR);
    if (joined !== item.artist) add("Artists", joined);
  }
  add("Album artist", item.albumArtist);
  for (const row of [countRow("Track", "Tracks", item.track, item.trackTotal),
    countRow("Disc", "Discs", item.disc, item.discTotal)]) {
    if (row !== undefined) rows.push(row);
  }
  add("Released", item.date);
  add("Genre", item.genre);
  add("Label", item.label);
  add("Copyright", item.copyright);
  add("ISRC", item.isrc);
  add("Barcode", item.barcode);
  return rows;
}

/** The form fields the server's `400 invalid_<field>` codes name. */
const FORM_FIELDS = ["artists", "albumArtist", "track", "trackTotal", "disc", "discTotal", "isrc", "date", "genre",
  "copyright", "label", "barcode"];

/**
 * The song details field a server error code is about (`invalid_track_total` → `trackTotal`), so
 * the page can show it beside that field; undefined for other codes.
 */
export function fieldOfError(code: string): keyof FieldErrors | undefined {
  if (code.indexOf("invalid_") !== 0) return undefined;
  const name = code.slice("invalid_".length).replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
  return FORM_FIELDS.indexOf(name) >= 0 ? name as keyof FieldErrors : undefined;
}
