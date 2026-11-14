// Shows (docs/media-types.md#next-episode), without the DOM so a new UI can reuse it: the episode
// order the server uses (season, none first, then episode), the next episode, labels, the grouped
// `GET /api/library/shows` response and when a room offers "Next episode". The server decides
// what is queued (`queue.nextEpisode`); this mirrors its rules.

/** One season of a grouped show; `season` is absent for episodes without a season number. */
export interface ShowSeason<E> { season?: number; episodes: E[] }

/** One show of `GET /api/library/shows`. */
export interface ShowGroup<E> { name: string; seasons: ShowSeason<E>[] }

/** Where an episode sits. */
export interface EpisodePlace { season?: number; episode: number }

/** Episodes in order: no season first, then seasons 0, 1, ..., then episode numbers. */
export function compareEpisodes(a: EpisodePlace, b: EpisodePlace): number {
  const sa = a.season === undefined ? -1 : a.season;
  const sb = b.season === undefined ? -1 : b.season;
  if (sa !== sb) return sa - sb;
  return a.episode - b.episode;
}

/** The key show names match by: trimmed, lower case. */
export function showKey(name: string): string {
  return name.trim().toLowerCase();
}

/**
 * The episode after `current` among `candidates` (same show assumed): the lowest higher episode
 * of the same season (gaps skipped), else the lowest episode of the next season that exists;
 * `undefined` when there's none.
 */
export function nextEpisode<E extends EpisodePlace>(candidates: readonly E[], current: EpisodePlace): E | undefined {
  let best: E | undefined;
  for (const c of candidates) {
    if (compareEpisodes(c, current) <= 0) continue;
    if (best === undefined || compareEpisodes(c, best) < 0) best = c;
  }
  return best;
}

/** "Season 2", "Specials" for season 0, "No season" when there is none. */
export function seasonLabel(season: number | undefined): string {
  if (season === undefined) return "No season";
  return season === 0 ? "Specials" : "Season " + season;
}

/**
 * Whether a room shows **Next episode**: to the owner and moderators, when the current entry is
 * a show episode, or when nothing is current (the server then uses the entry that played last).
 */
export function offersNextEpisode(canModerate: boolean, current: { mediaType?: string } | undefined): boolean {
  return canModerate && (current === undefined || current.mediaType === "show");
}
