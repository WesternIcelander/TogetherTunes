// The song menu's linked media (docs/library-and-ingest.md#linked-media), shared by the lists
// (libview.ts) and the item page (item.ts): a song's links for the menu, adding an item to the room
// open here (a request, or alone Play now / Add to Queue), and the **Find a music video…** dialog
// (search, pick → imported and linked to the song by the server). The rules are in the DOM-free
// interactions.ts (songMenuEntries), links.ts and videosearch.ts; the server enforces every rule.
import { api, ApiError } from "./api.js";
import { h } from "./dom.js";
import { playNowBefore } from "./interactions.js";
import type { ItemLink } from "./links.js";
import type { ShellRoom } from "./nav.js";
import { message } from "./session.js";
import {
  candidateDetail, NO_VIDEO_FOUND, pickBody, searchErrorText, type PickResult, type VideoCandidate,
} from "./videosearch.js";

/** A song's links, for its menu (`GET /api/items/{id}`); none when that fails. */
export async function itemLinks(itemId: string): Promise<ItemLink[]> {
  try {
    const page = await api<{ links?: ItemLink[] }>("GET", "/api/items/" + encodeURIComponent(itemId));
    return page.links ?? [];
  } catch {
    return [];
  }
}

/** What adding an item did: added to the queue, suggested (owner mode), or playing now (alone). */
export type QueueOutcome = "added" | "suggested" | "playing";

/**
 * Adds `itemId` to `room` through the room's normal request path (`queue.add`): a request (or Add
 * to Queue alone), or alone Play now (added, moved before the first manually added entry, skipped to).
 */
export async function queueItem(room: ShellRoom, itemId: string, how: "request" | "queue" | "playNow"):
    Promise<QueueOutcome> {
  if (how !== "playNow") {
    const ack = await room.request({ type: "queue.add", itemId });
    return ack["state"] === "suggested" ? "suggested" : "added";
  }
  const s = room.state();
  const before = s !== undefined ? playNowBefore(s.queue) : undefined;
  const hadCurrent = s?.current !== undefined;
  const ack = await room.request({ type: "queue.add", itemId });
  const entryId = typeof ack["entryId"] === "string" ? ack["entryId"] : undefined;
  if (hadCurrent && entryId !== undefined) {
    if (before !== undefined) await room.request({ type: "queue.move", entryIds: [entryId], beforeEntryId: before });
    await room.request({ type: "next" });
    await room.request({ type: "play" });
  }
  return "playing";
}

/** The picked video and what the server did with it. */
export interface FoundVideo extends PickResult {
  title: string;
}

/**
 * **Find a music video…** for `song`: a modal dialog that searches (`POST …/video-search`), lists
 * the candidates (title; channel · length · type) and, on a pick, has the server import the video
 * and link it to the song (`POST …/video-search/pick`). Resolves with the picked video, or null when
 * cancelled or nothing was picked.
 */
export function findVideo(song: { id: string; title: string }): Promise<FoundVideo | null> {
  return new Promise((resolve) => {
    const base = "/api/items/" + encodeURIComponent(song.id) + "/video-search";
    const status = h("p", { class: "muted", role: "status", text: "Searching…" });
    const list = h("ul", { class: "link-results video-results" });
    const error = h("p", { class: "error", role: "alert" });
    const cancel = h("button", { type: "button", text: "Cancel" });
    const box = h("div", { class: "form", role: "dialog", "aria-modal": "true", "aria-label": "Find a music video" },
      h("h2", { text: "Find a music video" }),
      h("p", { class: "muted", text: song.title }),
      status, list, error,
      h("div", { class: "row" }, cancel));
    const overlay = h("div", { class: "classify-prompt" }, box);
    let done = false;
    const close = (value: FoundVideo | null): void => {
      if (done) return;
      done = true;
      overlay.remove();
      resolve(value);
    };
    const fail = (e: unknown): void => {
      status.textContent = "";
      error.textContent = e instanceof ApiError ? searchErrorText(e.code, e.message) : message(e);
    };
    cancel.addEventListener("click", () => close(null));
    overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(null); });
    const pick = async (c: VideoCandidate, buttons: HTMLButtonElement[]): Promise<void> => {
      buttons.forEach((b) => { b.disabled = true; });
      error.textContent = "";
      status.textContent = "Adding " + c.title + "…";
      try {
        const r = await api<PickResult>("POST", base + "/pick", pickBody(c));
        close({ ...r, title: c.title });
      } catch (e) {
        buttons.forEach((b) => { b.disabled = false; });
        fail(e);
      }
    };
    api<{ candidates: VideoCandidate[] }>("POST", base)
      .then((r) => {
        if (done) return;
        status.textContent = r.candidates.length === 0 ? NO_VIDEO_FOUND : "Pick one:";
        const buttons: HTMLButtonElement[] = [];
        list.replaceChildren(...r.candidates.map((c) => {
          const detail = candidateDetail(c);
          const b = h("button", { type: "button", class: "link-pick" }, h("strong", { text: c.title }),
            detail !== "" ? h("div", { class: "muted small", text: detail }) : null);
          b.addEventListener("click", () => { void pick(c, buttons); });
          buttons.push(b);
          return h("li", {}, b);
        }));
        buttons[0]?.focus();
      })
      .catch(fail);
    document.body.append(overlay);
    cancel.focus();
  });
}
