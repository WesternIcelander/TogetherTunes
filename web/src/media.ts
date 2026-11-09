// Items, library entries, playlists and ingest jobs as the API returns them (docs/api.md#items-libraries-playlists-sharing),
// plus the pieces several pages share: the item status badge, job polling and the
// "add to playlist" picker.
import { api } from "./api.js";
import type { ActorRef } from "./actor.js";
import { h } from "./dom.js";

export type ItemStatus = "pending" | "processing" | "ready" | "failed" | "purged";

/** An item as listed in a library, playlist or share. */
export interface ItemSummary {
  id: string;
  kind: "audio" | "video";
  title: string;
  artist?: string;
  album?: string;
  durationMs?: number;
  source: "upload" | "youtube";
  youtubeId?: string;
  status: ItemStatus;
  presentAsAudio: boolean;
}

/** `GET /api/items/{id}`. */
export interface ItemPage extends ItemSummary {
  addedAt: number;
  error?: string;
  you: { inLibrary: boolean; canEdit: boolean; canPurge: boolean };
}

export interface LibraryEntry {
  item: ItemSummary;
  addedAt: number;
  via: "upload" | "youtube" | "share" | "manual" | string;
  sharedBy?: ActorRef;
}

export interface Page<T> { items: T[]; nextCursor?: string }

/** `GET /api/items/{id}/job`. */
export interface JobView {
  id: string;
  kind: "upload" | "youtube" | "reencode" | "refetch";
  state: "queued" | "running" | "done" | "failed";
  progress: number;
  attempts: number;
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  log?: string;
}

export interface Playlist {
  id: string;
  name: string;
  description?: string;
  entryCount: number;
  createdAt: number;
  updatedAt: number;
}

export interface PlaylistEntry { id: string; item: ItemSummary; addedAt: number }

export interface PlaylistDetail extends Playlist { entries: PlaylistEntry[] }

export function itemPath(id: string): string {
  return "/items/" + encodeURIComponent(id);
}

export function mediaPath(id: string, file: "audio.m4a" | "mezz.mp4" | "original" | "cover.jpg"): string {
  return "/media/" + encodeURIComponent(id) + "/" + file;
}

const STATUS_TEXT: Record<ItemStatus, string> = {
  pending: "Waiting",
  processing: "Processing",
  ready: "Ready",
  failed: "Failed",
  purged: "Media expired",
};

/** Whether ingest may still change the item's status. */
export function inProgress(status: ItemStatus): boolean {
  return status === "pending" || status === "processing";
}

/** A badge for an item's status; nothing for a ready item. */
export function statusBadge(status: ItemStatus, progress?: number): HTMLElement | null {
  if (status === "ready") return null;
  let text = STATUS_TEXT[status] ?? status;
  if (status === "processing" && progress !== undefined && progress > 0) {
    text += " " + Math.floor(progress * 100) + "%";
  }
  return h("span", { class: "badge badge-status badge-" + status, text });
}

/** The item title as a link to its page, with artist and album below it. */
export function itemTitleCell(item: ItemSummary): HTMLElement {
  const sub = [item.artist, item.album].filter((s) => s !== undefined && s !== "").join(" · ");
  return h("div", { class: "item-title" },
    h("a", { href: itemPath(item.id), text: item.title }),
    sub !== "" ? h("div", { class: "muted small", text: sub }) : null);
}

export function kindLabel(item: ItemSummary): string {
  if (item.kind === "video") return item.presentAsAudio ? "Video (as audio)" : "Video";
  return "Audio";
}

// ------------------------------------------------------------------ job polling

const POLL_MS = 2000;

export interface Watched { item: ItemPage; job?: JobView }

/**
 * Polls an item and its latest job every 2 s while ingest is in progress, calling `onUpdate`
 * after each poll (also the first). Stops by itself once the item is ready, failed or purged
 * (after reporting that), or when the returned function is called. Network errors are retried.
 */
export function watchItem(itemId: string, onUpdate: (w: Watched) => void,
                          onError?: (e: unknown) => void): () => void {
  let stopped = false;
  let timer: number | undefined;
  const poll = async (): Promise<void> => {
    if (stopped) return;
    let again = true;
    try {
      const item = await api<ItemPage>("GET", "/api/items/" + encodeURIComponent(itemId));
      let job: JobView | undefined;
      try {
        job = await api<JobView>("GET", "/api/items/" + encodeURIComponent(itemId) + "/job");
      } catch {
        job = undefined; // job_not_found: nothing to show
      }
      if (stopped) return;
      onUpdate({ item, job });
      again = inProgress(item.status) || (job !== undefined && (job.state === "queued" || job.state === "running"));
    } catch (e) {
      if (onError !== undefined) onError(e);
    }
    if (again && !stopped) timer = window.setTimeout(() => void poll(), POLL_MS);
  };
  void poll();
  return () => {
    stopped = true;
    if (timer !== undefined) window.clearTimeout(timer);
  };
}

/** A short line about a job: "Processing 42%", "Queued", "Failed after 2 attempts"… */
export function jobSummary(job: JobView): string {
  const kind = { upload: "Upload", youtube: "YouTube download", reencode: "Re-encode", refetch: "Refetch" }[job.kind]
    ?? job.kind;
  switch (job.state) {
    case "queued": return kind + ": queued";
    case "running": return kind + ": running, " + Math.floor(job.progress * 100) + "%";
    case "done": return kind + ": done";
    case "failed": return kind + ": failed" + (job.attempts > 1 ? " after " + job.attempts + " attempts" : "");
    default: return kind + ": " + String(job.state);
  }
}

// ------------------------------------------------------------------ playlists

let playlistCache: Promise<Playlist[]> | undefined;

/** The caller's playlists (first 200, most recently changed first), fetched once per page load. */
export function myPlaylists(refresh = false): Promise<Playlist[]> {
  if (playlistCache === undefined || refresh) {
    playlistCache = api<Page<Playlist>>("GET", "/api/playlists?limit=200").then((p) => p.items);
    playlistCache.catch(() => { playlistCache = undefined; });
  }
  return playlistCache;
}

/**
 * A "Add to playlist…" select for one item. Picking a playlist adds the item at its end;
 * `onDone` gets a message for the user (or the error).
 */
export function playlistPicker(itemId: string, onDone: (message: string, error?: unknown) => void): HTMLSelectElement {
  const select = h("select", { "aria-label": "Add to playlist", class: "playlist-picker" },
    h("option", { value: "", text: "Add to playlist…" }));
  // Loaded right away (one cached request per page), so the native picker is complete when it opens.
  myPlaylists().then((lists) => {
    for (const p of lists) select.append(h("option", { value: p.id, text: p.name }));
    if (lists.length === 0) select.append(h("option", { value: "", disabled: true, text: "(no playlists yet)" }));
  }).catch((e: unknown) => onDone("", e));
  select.addEventListener("change", () => {
    const id = select.value;
    if (id === "") return;
    const name = select.selectedOptions[0]?.textContent ?? "";
    select.disabled = true;
    api("POST", "/api/playlists/" + encodeURIComponent(id) + "/entries", { itemId })
      .then(() => { onDone("Added to " + name + "."); myPlaylists(true).catch(() => undefined); })
      .catch((e: unknown) => onDone("", e))
      .finally(() => { select.disabled = false; select.value = ""; });
  });
  return select;
}
