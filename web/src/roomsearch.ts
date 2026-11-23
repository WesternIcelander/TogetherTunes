// The room view's **Request / Suggest from the library** search (docs/ui.md#leftovers-removed-end-of-m9): which
// buttons show (Search for the library, Search YouTube for people who may request YouTube links in
// the room), the heading, the room's YouTube search path, and which answer may still be shown: any
// change to the text box clears the results (and the More button and status) at once, and an
// answer that arrives after that (or after a newer search) is dropped. Pure logic (no DOM), run
// under Node in the tests (src/test/resources/web-tests/roomsearch.test.mjs); room.ts draws it.
// The server enforces every rule (GET /api/rooms/{id}/search and /youtube-search).

/** The parts of the room state's `you` the search cares about. */
export interface RoomSearchYou {
  identified: boolean;
  canYoutube: boolean;
  canRequestLibrary?: boolean;
  restriction?: unknown;
}

/** YouTube results the room search asks for. */
export const ROOM_YOUTUBE_LIMIT = 10;
export const SEARCH_LIBRARY_LABEL = "Search";
export const SEARCH_YOUTUBE_LABEL = "Search YouTube";

/**
 * Which search buttons the panel shows: the library search for those who may request from the
 * library, **Search YouTube** for those who may request YouTube links here (`you.canYoutube`).
 * Nothing for signed-in users (they use the header search), anonymous visitors (they join as a
 * guest first) and anyone banned or timed out here.
 */
export function roomSearchButtons(meKind: string, you: RoomSearchYou): { library: boolean; youtube: boolean } {
  const show = meKind !== "user" && you.identified && you.restriction === undefined;
  return { library: show && you.canRequestLibrary === true, youtube: show && you.canYoutube };
}

/** The panel's heading: from the library when it searches the library, else from YouTube. */
export function roomSearchHeading(buttons: { library: boolean }, suggest: boolean): string {
  return (suggest ? "Suggest" : "Request") + (buttons.library ? " from the library" : " from YouTube");
}

/** `GET /api/rooms/{id}/youtube-search` for `q`. */
export function roomYoutubeSearchPath(roomId: string, q: string, limit: number = ROOM_YOUTUBE_LIMIT): string {
  return "/api/rooms/" + encodeURIComponent(roomId) + "/youtube-search?"
    + new URLSearchParams({ q: q.trim(), limit: String(limit) }).toString();
}

export type RoomSearchMode = "library" | "youtube";

/**
 * What the results area shows and which answer may fill it. `start` begins a search (a new
 * generation); `input` (any change to the text box) clears what is shown and drops the answers
 * still on their way; `accepts` says whether an answer of a generation may still be shown.
 */
export class RoomSearchResults {
  /** What is shown: `none` after a change to the text box, until the next search. */
  shown: RoomSearchMode | "none" = "none";
  private generation = 0;

  /** A new search in `mode`: its answer replaces what is shown. Returns its generation. */
  start(mode: RoomSearchMode): number {
    this.shown = mode;
    return ++this.generation;
  }

  /**
   * The text box changed: clear the results, the More button and the status. Returns whether
   * anything was shown or on its way (the caller then empties the list).
   */
  input(): boolean {
    const had = this.shown !== "none";
    this.shown = "none";
    this.generation++;
    return had;
  }

  /** Whether the answer of search `generation` may still be shown. */
  accepts(generation: number): boolean {
    return generation === this.generation;
  }

  /** Whether "More" may show (library results only). */
  moreAllowed(): boolean {
    return this.shown === "library";
  }
}

/** A YouTube request's notice, after the room's ack (`state` = `suggested` for a suggestion). */
export function youtubeRequestedText(title: string, state: unknown): string {
  return title + ": " + (state === "suggested" ? "Sent as a suggestion." : "Added to the queue.")
    + " New videos play once they have been downloaded.";
}

/** A room YouTube search's error. */
export function roomYoutubeErrorText(code: string | undefined, fallback: string): string {
  switch (code) {
    case "too_many_searches": return "Too many YouTube searches in the past hour. Try again later.";
    case "feature_disabled": return "Adding videos from links is turned off on this server.";
    case "guest_youtube_disabled": return "Guests can't request YouTube links in this room.";
    case "identity_required": return "Join as a guest first.";
    default: return fallback;
  }
}
