// Views and pins (docs/media-types.md#views-auto-delete-and-pins), DOM-free. A view is any playback
// at all: in a room the server counts an entry becoming current; outside a room the client reports
// it (`POST /api/items/{id}/view`) when local playback of an item starts. Fetching a media file is
// never a view. The server records at most one view per actor and item a minute; the reporter
// below sends at most one report per item in that time, so pausing and resuming doesn't send more.

/** At most one report per item in this many ms (the server's interval). */
export const VIEW_REPORT_GAP_MS = 60_000;

/** `POST` here to report a view of `itemId`. */
export function viewPath(itemId: string): string {
  return "/api/items/" + encodeURIComponent(itemId) + "/view";
}

/** `PUT` (pin) or `DELETE` (unpin) here. */
export function pinPath(itemId: string): string {
  return "/api/items/" + encodeURIComponent(itemId) + "/pin";
}

/**
 * A function to call whenever local playback of an item starts (e.g. a media element's `play`
 * event); it calls `send` at most once per item per `gapMs`. A failed report is dropped: it only
 * makes an auto-delete timer run from an earlier view, and there is nothing to show.
 */
export function viewReporter(send: (itemId: string) => Promise<unknown>, now: () => number = Date.now,
  gapMs: number = VIEW_REPORT_GAP_MS): (itemId: string) => void {
  const last = new Map<string, number>();
  return (itemId: string): void => {
    const t = now();
    const prev = last.get(itemId);
    if (prev !== undefined && t - prev < gapMs && t >= prev) return;
    last.set(itemId, t);
    try {
      send(itemId).catch(() => undefined);
    } catch {
      // a sender that throws at once: same as a failed report
    }
  };
}

/** The item page's line about a pin: what it means, or `undefined` when the item isn't pinned. */
export function pinText(pinned: { pinnedAt: number } | undefined, formatTime: (ms: number) => string): string | undefined {
  if (pinned === undefined) return undefined;
  return formatTime(pinned.pinnedAt) + " (its media is never deleted automatically)";
}
