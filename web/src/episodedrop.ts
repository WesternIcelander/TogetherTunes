// Video files dropped on Shows (docs/ui.md#library-rules, "Drag and drop"): the dialog that asks for
// each file's show name, season and episode, without the DOM (filedrop.ts draws it). Each row is
// prefilled from the file name (`Name S01E02`, the server's suggestion rules in Classify.fromFileName);
// the dialog asks for what is missing (a show name and an episode number per file; the season may stay
// empty), and one show name field above the rows can fill them all. The server checks the values
// again (`invalid_show`, `show_required`).
import { mediaTypeBody, type ShowInfo } from "./mediatypes.js";

/** The largest season or episode number the server takes (MediaType.Show.MAX_NUMBER). */
const MAX_NUMBER = 1_000_000;

/**
 * `S01E02`, `s1e2`, `S01.E02`, `S01 E02`, not inside a longer word (a capture group instead of a
 * lookbehind: Safari before 16.4 has none).
 */
const SXXEYY = /(^|[^A-Za-z0-9])S(\d{1,4})[ ._-]?E(\d{1,5})(?![0-9])/i;

/** What a file name says about the episode (any part may be missing), or undefined without `SxxEyy`. */
export interface EpisodeGuess { name?: string; season?: number; episode?: number }

function number(digits: string | undefined): number | undefined {
  if (digits === undefined) return undefined;
  const n = Number(digits);
  return n <= MAX_NUMBER ? n : undefined;
}

/**
 * The show, season and episode in a file name: with `SxxEyy`, the season and episode from it and
 * the show name from what comes before (dots and underscores become spaces; trailing separators
 * and brackets go). Undefined when the name has no `SxxEyy`.
 */
export function guessEpisode(fileName: string): EpisodeGuess | undefined {
  const m = SXXEYY.exec(fileName);
  if (m === null) return undefined;
  const start = m.index + (m[1] ?? "").length;
  const name = fileName.substring(0, start).replace(/[._]+/g, " ").replace(/[\s\-[(]+$/, "")
    .replace(/\s+/g, " ").trim();
  const out: EpisodeGuess = {};
  if (name !== "") out.name = name;
  const season = number(m[2]);
  const episode = number(m[3]);
  if (season !== undefined) out.season = season;
  if (episode !== undefined) out.episode = episode;
  return out;
}

/** One row of the dialog: the file and the form's values (strings, as typed). */
export interface EpisodeRow { fileName: string; showName: string; season: string; episode: string }

/**
 * The dialog's rows, prefilled from each file name; `fallbackName` (the show open in the list, if
 * any) fills the show name where the file name has none.
 */
export function episodeRows(fileNames: readonly string[], fallbackName = ""): EpisodeRow[] {
  return fileNames.map((fileName) => {
    const g = guessEpisode(fileName);
    return {
      fileName,
      showName: g?.name ?? fallbackName.trim(),
      season: g?.season === undefined ? "" : String(g.season),
      episode: g?.episode === undefined ? "" : String(g.episode),
    };
  });
}

/** The "show name for all" field's starting value: the name every row has, else empty. */
export function commonShowName(rows: readonly EpisodeRow[]): string {
  const first = rows[0]?.showName.trim() ?? "";
  if (first === "") return "";
  return rows.every((r) => r.showName.trim().toLowerCase() === first.toLowerCase()) ? first : "";
}

/**
 * The rows after typing `name` into the "show name for all" field: every row's show name becomes
 * it (a blank field changes nothing, so names typed per row aren't wiped by clearing it).
 */
export function fillShowName(rows: readonly EpisodeRow[], name: string): EpisodeRow[] {
  if (name.trim() === "") return rows.slice();
  return rows.map((r) => ({ ...r, showName: name }));
}

/** What a row is missing or has wrong, as short texts ("show name", "episode number", …); empty when fine. */
export function rowProblems(r: EpisodeRow): string[] {
  const out: string[] = [];
  if (r.showName.trim() === "") out.push("the show's name");
  const season = r.season.trim();
  if (season !== "" && !validNumber(season)) out.push("a season number (0 or more, or empty)");
  const episode = r.episode.trim();
  if (episode === "") out.push("an episode number");
  else if (!validNumber(episode)) out.push("an episode number (0 or more)");
  return out;
}

function validNumber(s: string): boolean {
  return /^\d{1,7}$/.test(s) && Number(s) <= MAX_NUMBER;
}

/** The first problem of the form as one line ("ep2.mp4 needs the show's name and an episode number."), or "". */
export function formProblem(rows: readonly EpisodeRow[]): string {
  for (const r of rows) {
    const p = rowProblems(r);
    if (p.length === 0) continue;
    const list = p.length === 1 ? p[0] as string : p.slice(0, -1).join(", ") + " and " + (p[p.length - 1] as string);
    return r.fileName + " needs " + list + ".";
  }
  return "";
}

/**
 * The classify bodies (`{mediaType: "show", show}`) for the rows, in order, or the problem text when
 * one is missing something.
 */
export function episodeBodies(rows: readonly EpisodeRow[]):
    { bodies: { mediaType: "show"; show: ShowInfo }[] } | { error: string } {
  const problem = formProblem(rows);
  if (problem !== "") return { error: problem };
  const bodies: { mediaType: "show"; show: ShowInfo }[] = [];
  for (const r of rows) {
    const b = mediaTypeBody("show", r.showName, r.season, r.episode);
    if ("error" in b) return { error: r.fileName + ": " + b.error };
    const show = b.body.show;
    if (show === undefined) return { error: r.fileName + ": " + "A show episode needs the show's name." };
    bodies.push({ mediaType: "show", show });
  }
  return { bodies };
}
