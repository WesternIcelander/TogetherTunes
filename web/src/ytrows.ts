// YouTube search result rows (docs/ui.md#header-search, the search page), shared by the header
// search's dropdown (searchbox.ts) and the search page (libview.ts): thumbnail; title; uploader ·
// length; Preview on YT (the watch page in a new tab) and the actions: Add to Queue (the room open
// here, `queue.add {youtubeUrl}`; alone the double-click setting's action) and Add to library… (the
// Add from link dialog with the link). On the search page the rows drag out as links (onto
// playlists, the built-in lists, the queue). The rules are in the DOM-free ytsearch.ts; the server
// enforces every rule.
import { openAddLink } from "./addlink.js";
import { h } from "./dom.js";
import { doubleClickAction } from "./interactions.js";
import { currentRoom } from "./nav.js";
import { prefs } from "./prefsclient.js";
import { message } from "./session.js";
import { queueItem } from "./songmenu.js";
import { queuedText, youtubeActions, YOUTUBE_ACTION_LABELS, youtubeRow, type YoutubeResult } from "./ytsearch.js";

export interface YoutubeRowOptions {
  /** Shows what an action did (or its error). */
  notice(text: string, error?: boolean): void;
  /** The rows drag out as links (the search page, desktop). */
  draggable?: boolean;
  /** Called before an action starts (the dropdown closes for Add to library…). */
  acting?(action: "addToQueue" | "addToLibrary"): void;
}

/** Add to Queue: the room's request path; alone the double-click setting's action (play now or queue). */
async function addToQueue(r: YoutubeResult, o: YoutubeRowOptions): Promise<void> {
  const room = currentRoom();
  if (room === undefined) {
    o.notice("Open a room first: Add to Queue adds the video to the room's queue.", true);
    return;
  }
  const alone = room.state()?.room.alone === true;
  const setting = alone ? await prefs.load().then((p) => p.doubleClickAlone, () => undefined) : undefined;
  const how = doubleClickAction(alone, setting);
  o.notice("Adding " + r.title + "…");
  try {
    const outcome = await queueItem(room, { youtubeUrl: youtubeRow(r).url }, how);
    o.notice(queuedText(outcome, r.title, room.name()));
  } catch (e) {
    o.notice(r.title + ": " + message(e), true);
  }
}

/** The rows for `results`, as `li` elements. */
export function youtubeRows(results: readonly YoutubeResult[], o: YoutubeRowOptions): HTMLLIElement[] {
  const room = currentRoom()?.state()?.room;
  const actions = youtubeActions(room);
  return results.map((r) => {
    const row = youtubeRow(r);
    const img = h("img", { src: row.thumbnail, loading: "lazy", referrerpolicy: "no-referrer", alt: "",
      draggable: "false" });
    // a failed thumbnail leaves the plain box
    img.addEventListener("error", () => { img.remove(); }, { once: true });
    const preview = h("a", { class: "button-link", href: row.url, target: "_blank", rel: "noopener noreferrer",
      text: row.preview, draggable: "false" });
    const buttons = actions.map((a) => {
      const b = h("button", { type: "button", class: "small", text: YOUTUBE_ACTION_LABELS[a], "data-action": a });
      b.addEventListener("click", (ev) => {
        ev.stopPropagation();
        o.acting?.(a);
        if (a === "addToQueue") {
          b.disabled = true;
          void addToQueue(r, o).finally(() => { b.disabled = false; });
        } else {
          openAddLink({ url: row.url, onAdded: (text) => o.notice(text) });
        }
      });
      return b;
    });
    const li = h("li", { class: "video-candidate yt-result", "data-video": r.videoId },
      h("div", { class: "video-thumb" }, img),
      h("div", { class: "video-candidate-text" },
        h("strong", { text: row.title }),
        row.detail !== "" ? h("div", { class: "muted small", text: row.detail }) : null,
        h("div", { class: "row" }, preview, ...buttons)));
    if (o.draggable === true) {
      li.draggable = true;
      li.addEventListener("dragstart", (ev) => {
        const dt = ev.dataTransfer;
        if (dt === null) return;
        dt.effectAllowed = "copy";
        dt.setData("text/uri-list", row.url);
        dt.setData("text/plain", row.url);
      });
    }
    return li;
  });
}
