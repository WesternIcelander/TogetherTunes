// The desktop layout's middle lists (docs/ui.md#layout-desktop), the `list` view of the router:
// - All My Songs (/songs): the caller's library, newest first, with "+ Add from link" (library only);
// - Movies/Shows (/shows): movies, then shows grouped by show and season;
// - Liked Songs (/liked) and a playlist (/playlists/{id}; another person's playlist selected for
//   autoplay in the room with ?room=): cover, name, song count and total time, "+ Add from link"
//   (own playlists) and "Add All: In Order / Shuffled" into the current room's queue;
// - Uncategorized Videos (/uncategorized) and the search results (/search?q=, the whole server,
//   the caller's own library first).
// Rows: #, cover, title and artist, album, date added, time and a ⋯ menu (also on right-click and
// long press). On narrow windows (docs/ui.md#mobile-narrow-windows) only #, cover, title and artist
// show, and **Select** turns on the Select mode: checkboxes on the rows and a bar with Add to Queue
// (the selected songs, in the list's order). Interactions (docs/ui.md#interaction-rules-all-layouts,
// rules in the DOM-free interactions.ts): double-click (double-tap) a row requests the song in the
// room open here, or alone does what the user setting `doubleClickAlone` says (play it now or queue
// it); on the desktop a click selects a row, Ctrl/Cmd-click toggles one and Shift-click selects a
// range, and the selection bar's Add to Queue adds them in list order. The title link opens the item
// page after a moment (a double-click cancels it). Drag and drop (desktop; rules in the DOM-free
// dragdrop.ts): rows drag out (the selected rows together when the dragged one is selected) to a
// playlist, the queue, …; an own playlist's (or Liked Songs') rows take drops at a position: its
// own entries move, songs from elsewhere are added, files are uploaded into it (filedrop.ts) and a
// link opens Add from link (addlink.ts). The server enforces every rule.
import { prefs } from "./prefsclient.js";
import { openAddLink } from "./addlink.js";
import { api } from "./api.js";
import { withClassification } from "./classifyprompt.js";
import { draggable, dropZone } from "./dnd.js";
import { draggedKeys, moveIsNoop, playlistDrop, type DragPayload } from "./dragdrop.js";
import { dropFiles } from "./filedrop.js";
import { formatDuration, h } from "./dom.js";
import { itemPath, mediaPath, type ItemSummary, type LibraryEntry, type Page, type PlaylistDetail } from "./media.js";
import { clickSelect, doubleClickAction, playNowBefore, selectionText, SONG_ACTION_LABELS, songMenu, toggleKey,
  type DoubleClickAction, type SongList } from "./interactions.js";
import { MOBILE_QUERY, Selection, selectedText } from "./mobile.js";
import { currentRoom, navigate, pageSignal, refreshLibrary, type ShellRoom } from "./nav.js";
import { copyText } from "./outputspanel.js";
import { formatTotal, songCount, totalDuration } from "./panels.js";
import { routeOf, type ListName } from "./router.js";
import { seasonLabel } from "./shows.js";
import { element, message, onReady, renderNav, requireUser } from "./session.js";
import { onLongPress, openMenu, type MenuItem } from "./touch.js";

/** A row of the table: the item, and when it was added here (absent for search results). */
interface Row {
  item: ItemSummary;
  addedAt?: number;
  /** The playlist entry's id (playlists and Liked Songs). */
  entryId?: string;
}

const PAGE_SIZE = 100;

let cursor: string | undefined;
let loadMore: (() => Promise<void>) | undefined;
let shownRows = 0;
/** The items of the rows shown, by row key (its number). */
const rowItems: Record<string, ItemSummary> = {};
/** The playlist entry ids of the rows shown, by row key (playlists and Liked Songs). */
const rowEntries: Record<string, string> = {};
/** The Select mode (touch): on; and what is selected (row keys; on the desktop by click, too). */
let selecting = false;
const selection = new Selection();
/** The desktop multi-select's anchor (the last row clicked without Shift). */
let anchor: string | undefined;
/** Which kind of list is shown (for the song menu), and the playlist's id. */
let songList: SongList = "library";
let listPlaylistId: string | undefined;
/** The own playlist shown, as a drop target, and the view its drop zone was set up for. */
let dropPlaylist: PlaylistDetail | undefined;
let dropZoneSignal: AbortSignal | undefined;
/** The title link waiting to be followed (a double-click on the row cancels it). */
let linkTimer: number | undefined;

function mobile(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia(MOBILE_QUERY).matches;
}

/** The user setting `doubleClickAlone` (`/api/me/settings`, from the page's shared preferences store). */
function aloneSetting(): Promise<string | undefined> {
  return prefs.load().then((p) => p.doubleClickAlone, () => undefined);
}

function showError(e: unknown): void {
  element("list-error").textContent = e === "" ? "" : message(e);
}

function notice(text: string): void {
  const n = element("list-notice");
  n.textContent = text;
  n.hidden = text === "";
}

function dateText(ms: number | undefined): string {
  if (ms === undefined) return "";
  return new Date(ms).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

/** An item's cover: its cover.jpg, a placeholder while there is none (or it fails to load). */
function coverCell(item: ItemSummary, className = "cover row-cover", id?: string): HTMLElement {
  const none = (): HTMLElement => h("div", { class: className + " cover-none", "aria-hidden": "true", id });
  if (item.status !== "ready") return none();
  const img = h("img", { class: className, src: mediaPath(item.id, "cover.jpg"), alt: "", loading: "lazy", id });
  img.addEventListener("error", () => img.replaceWith(none()));
  return img;
}

// ------------------------------------------------------------------ queueing, the row menu, Select mode

/** Adds items to the current room's queue, in order; says how it went. */
async function addToQueue(items: readonly ItemSummary[]): Promise<boolean> {
  const room = currentRoom();
  if (room === undefined) {
    showError("Open a room first: Add to Queue adds the songs to the room's queue.");
    return false;
  }
  showError("");
  let added = 0;
  let suggested = 0;
  const failed: string[] = [];
  for (const item of items) {
    try {
      const ack = await room.request({ type: "queue.add", itemId: item.id });
      if (ack["state"] === "suggested") suggested++;
      else added++;
    } catch (e) {
      failed.push(item.title + ": " + message(e));
    }
  }
  const parts: string[] = [];
  if (added > 0) parts.push(songCount(added) + " added to " + room.name());
  if (suggested > 0) parts.push(songCount(suggested) + " suggested to " + room.name());
  notice(parts.length > 0 ? parts.join("; ") + "." : "");
  if (failed.length > 0) showError(failed.join("; "));
  return failed.length === 0;
}

/** Whether the room open here is the user's alone room. */
function isAlone(room: ShellRoom | undefined): boolean {
  return room?.state()?.room.alone === true;
}

/**
 * Double-click on a song: request it in the room open here; alone, play it now or queue it (the
 * user setting), unless `how` says which.
 */
async function doubleClick(item: ItemSummary, how?: DoubleClickAction): Promise<void> {
  const room = currentRoom();
  if (room === undefined) {
    showError("Open a room first: double-click adds the song to the room's queue.");
    return;
  }
  const alone = isAlone(room);
  const action = how ?? doubleClickAction(alone, alone ? await aloneSetting() : undefined);
  if (action !== "playNow") {
    await addToQueue([item]);
    return;
  }
  // Play immediately (alone): add it, move it to the front of the queue and skip to it.
  showError("");
  const s = room.state();
  const before = s !== undefined ? playNowBefore(s.queue) : undefined;
  const hadCurrent = s?.current !== undefined;
  try {
    const ack = await room.request({ type: "queue.add", itemId: item.id });
    const entryId = typeof ack["entryId"] === "string" ? ack["entryId"] : undefined;
    if (hadCurrent && entryId !== undefined) {
      if (before !== undefined) await room.request({ type: "queue.move", entryIds: [entryId], beforeEntryId: before });
      await room.request({ type: "next" });
      await room.request({ type: "play" });
    }
    notice("Playing " + item.title + ".");
  } catch (e) {
    showError(item.title + ": " + message(e));
  }
}

async function removeRow(key: string, item: ItemSummary, what: "removeFromPlaylist" | "unlike" | "removeFromLibrary"):
    Promise<void> {
  showError("");
  try {
    if (what === "removeFromPlaylist") {
      const entryId = rowEntries[key];
      if (listPlaylistId === undefined || entryId === undefined) return;
      await api("DELETE", "/api/playlists/" + encodeURIComponent(listPlaylistId) + "/entries/"
        + encodeURIComponent(entryId));
    } else if (what === "unlike") {
      await api("DELETE", "/api/me/likes/" + encodeURIComponent(item.id));
    } else {
      const holding = await api<{ playlists: { name: string }[] }>("GET",
        "/api/library/" + encodeURIComponent(item.id) + "/playlists");
      const names = holding.playlists.map((p) => p.name);
      if (!window.confirm("Remove \"" + item.title + "\" from your library?"
        + (names.length > 0 ? " It is also removed from " + names.join(", ") + "." : ""))) return;
      await api("DELETE", "/api/library/" + encodeURIComponent(item.id));
    }
    refreshLibrary();
    restart();
  } catch (e) {
    showError(e);
  }
}

/** The row's ⋯ menu (also on right-click and long press). */
function openRowMenu(key: string, anchorEl: HTMLElement): void {
  const item = rowItems[key];
  if (item === undefined) return;
  const room = currentRoom();
  const items: MenuItem[] = songMenu({ room: room !== undefined, alone: isAlone(room), list: songList })
    .filter((a) => a !== "removeFromPlaylist" || rowEntries[key] !== undefined)
    .map((a) => ({ label: SONG_ACTION_LABELS[a], action: () => {
      switch (a) {
        case "request": void addToQueue([item]); return;
        case "playNow": void doubleClick(item, "playNow"); return;
        case "queue": void doubleClick(item, "queue"); return;
        case "open": navigate(itemPath(item.id)); return;
        case "copyLink": {
          const n = element("list-notice");
          n.hidden = false;
          copyText(location.origin + itemPath(item.id), n);
          return;
        }
        case "removeFromPlaylist":
        case "unlike":
        case "removeFromLibrary":
          void removeRow(key, item, a);
          return;
        case "select":
          if (mobile()) setSelecting(true);
          toggleSelected(key);
          return;
      }
    } }));
  openMenu(anchorEl, items, item.title);
}

function rowKeys(): string[] {
  return Array.from(element("list-rows").querySelectorAll<HTMLTableRowElement>("tr[data-key]"))
    .map((tr) => tr.dataset["key"] ?? "");
}

function drawSelection(): void {
  element("list-select-count").textContent = selecting ? selectedText(selection.size) : selectionText(selection.size);
  element<HTMLButtonElement>("list-select-queue").disabled = selection.size === 0;
  // The desktop's selection (click, Ctrl/Cmd-click, Shift-click) shows the bar too.
  const bar = selecting || selection.size > 0;
  element("list-select-bar").hidden = !bar;
  document.body.classList.toggle("has-select-bar", bar);
  element("list-rows").querySelectorAll<HTMLTableRowElement>("tr[data-key]").forEach((tr) => {
    const on = selection.has(tr.dataset["key"] ?? "");
    tr.classList.toggle("selected", on);
    const box = tr.querySelector<HTMLInputElement>("input.row-check");
    if (box !== null) box.checked = on;
  });
}

function setSelecting(on: boolean): void {
  selecting = on;
  if (!on) selection.clear();
  anchor = undefined;
  element("list-table").classList.toggle("selecting", on);
  const b = document.getElementById("list-select");
  if (b !== null) b.setAttribute("aria-pressed", on ? "true" : "false");
  drawSelection();
}

function toggleSelected(key: string): void {
  selection.toggle(key);
  drawSelection();
}

function selectButton(): HTMLButtonElement {
  const b = h("button", { type: "button", id: "list-select", class: "pill m-only", text: "Select",
    "aria-pressed": "false", title: "Select several songs" });
  b.addEventListener("click", () => setSelecting(!selecting));
  return b;
}

function rowElement(r: Row, n: number): HTMLTableRowElement {
  const item = r.item;
  const key = String(n);
  rowItems[key] = item;
  if (r.entryId !== undefined) rowEntries[key] = r.entryId;
  const status = item.status === "ready" ? null
    : h("span", { class: "muted small", text: " · " + (item.status === "failed" ? "Failed" : item.status) });
  const check = h("input", { type: "checkbox", class: "row-check", "aria-label": "Select " + item.title, tabindex: "-1" });
  const more = h("button", { type: "button", class: "icon row-more", text: "⋯", title: "More",
    "aria-label": "More for " + item.title, "aria-haspopup": "true" });
  more.addEventListener("click", () => openRowMenu(key, more));
  const tr = h("tr", { "data-item": item.id, "data-key": key, "data-entry": r.entryId },
    h("td", { class: "num muted" }, h("span", { class: "row-num", text: key }), check),
    h("td", {}, coverCell(item)),
    h("td", {},
      h("a", { href: itemPath(item.id), class: "row-title", text: item.title }),
      h("div", { class: "muted small" }, item.artist ?? "", status)),
    h("td", { class: "small col-album", text: item.album ?? "" }),
    h("td", { class: "small muted col-added", text: dateText(r.addedAt) }),
    h("td", { class: "num small col-time", text: formatDuration(item.durationMs) }),
    h("td", { class: "col-more" }, more));
  tr.addEventListener("click", (ev) => {
    const t = ev.target as Element;
    if (t.closest(".row-more") !== null) return;
    // Select mode (touch): a tap anywhere on the row toggles it (and doesn't follow the title link).
    if (selecting) {
      ev.preventDefault();
      toggleSelected(key);
      return;
    }
    // Shift / Ctrl / Cmd-click: multi-select (also on the title link).
    if (ev.shiftKey || toggleKey(ev)) {
      ev.preventDefault();
      anchor = clickSelect(selection, rowKeys(), key, ev, anchor);
      drawSelection();
      return;
    }
    const link = t.closest("a");
    if (link instanceof HTMLAnchorElement && ev.button === 0) {
      // The title opens the item page after a moment, unless this is the start of a double-click.
      ev.preventDefault();
      window.clearTimeout(linkTimer);
      if (ev.detail > 1) return;
      const href = link.getAttribute("href") ?? "";
      linkTimer = window.setTimeout(() => navigate(href), 300);
      return;
    }
    // A plain click on the desktop selects just this row.
    if (!mobile()) {
      anchor = clickSelect(selection, rowKeys(), key, ev, anchor);
      drawSelection();
    }
  });
  tr.addEventListener("dblclick", (ev) => {
    if (selecting || (ev.target as Element).closest("button, input") !== null) return;
    ev.preventDefault();
    window.clearTimeout(linkTimer);
    window.getSelection()?.removeAllRanges();
    void doubleClick(item);
  });
  onLongPress(tr, () => {
    if (!selecting) openRowMenu(key, more);
  }, pageSignal());
  if (!mobile()) draggable(tr, () => rowDrag(key), () => rowDragLinks(key), pageSignal());
  return tr;
}

/** The rows a drag from `key` carries: the selection when `key` is in it, else that row. */
function dragRows(key: string): string[] {
  return draggedKeys((k) => selection.has(k), rowKeys(), key);
}

/** What dragging the row `key` (with the rows selected along with it) carries. */
function rowDrag(key: string): DragPayload | undefined {
  if (selecting) return undefined;
  const keys = dragRows(key);
  const itemIds = keys.map((k) => rowItems[k]?.id).filter((id): id is string => id !== undefined);
  if (itemIds.length !== keys.length || itemIds.length === 0) return undefined;
  const entryIds = keys.map((k) => rowEntries[k]).filter((id): id is string => id !== undefined);
  const own = listPlaylistId !== undefined && entryIds.length === keys.length;
  return { kind: "items", source: songList, itemIds, ...(own ? { playlistId: listPlaylistId, entryIds } : {}) };
}

function rowDragLinks(key: string): string[] {
  return dragRows(key).map((k) => rowItems[k]).filter((i): i is ItemSummary => i !== undefined)
    .map((i) => location.origin + itemPath(i.id));
}

/** The open playlist's entry ids, in order. */
function entryOrder(): string[] {
  return Array.from(element("list-rows").querySelectorAll<HTMLTableRowElement>("tr[data-entry]"))
    .map((tr) => tr.dataset["entry"] ?? "");
}

/** Adds songs to the open playlist before `before` (null: the end), in order; asks for types where needed. */
async function addToPlaylist(playlistId: string, itemIds: readonly string[], before: string | null): Promise<void> {
  const path = "/api/playlists/" + encodeURIComponent(playlistId) + "/entries";
  let added = 0;
  const failed: string[] = [];
  for (const itemId of itemIds) {
    const body: Record<string, unknown> = { itemId };
    if (before !== null) body["beforeEntryId"] = before;
    try {
      const r = await withClassification((c) => api("POST", path, c === undefined ? body : { ...body, ...c }),
        undefined, true);
      if (r !== null) added++;
    } catch (e) {
      failed.push(message(e));
    }
  }
  notice(added > 0 ? songCount(added) + " added." : "");
  showError(failed.join("; "));
}

/**
 * An own playlist's view takes drops at a position (docs/ui.md#library-rules): its own entries
 * move there, songs from elsewhere are added there, files are uploaded into it, a link opens Add
 * from link for it.
 */
function playlistDropZone(shown: PlaylistDetail): void {
  dropPlaylist = shown;
  // Once per view: a restart (after a change) shows the list again in the same view.
  const signal = pageSignal();
  if (dropZoneSignal === signal) return;
  dropZoneSignal = signal;
  signal.addEventListener("abort", () => { dropPlaylist = undefined; });
  dropZone(element("app"), {
    accepts: (content, payload) => dropPlaylist !== undefined && (content === "files" || content === "link"
      || (content === "internal" && playlistDrop({ playlistId: dropPlaylist.id, inView: true }, payload).kind !== "none")),
    rows: { selector: "#list-rows tr[data-entry]", key: "data-entry" },
    onDrop: (d) => {
      const p = dropPlaylist;
      if (p === undefined) return;
      const name = p.builtin === "likes" ? "Liked Songs" : p.name;
      showError("");
      if (d.content === "files") {
        dropFiles(d.files, { playlistId: p.id, playlistName: name, beforeEntryId: d.before },
          (text, error) => (error === true ? showError(text) : notice(text)), () => restart());
        return;
      }
      if (d.content === "link") {
        openAddLink({ playlistId: p.id, beforeEntryId: d.before ?? undefined, url: d.link,
          onAdded: (text) => { notice(text); restart(); } });
        return;
      }
      const what = playlistDrop({ playlistId: p.id, inView: true }, d.payload);
      if (what.kind === "move") {
        if (moveIsNoop(entryOrder(), what.entryIds, d.before)) return;
        api("POST", "/api/playlists/" + encodeURIComponent(p.id) + "/entries/move",
          { entryIds: what.entryIds, beforeEntryId: d.before })
          .then(() => restart(), showError);
      } else if (what.kind === "add") {
        void addToPlaylist(p.id, what.itemIds, d.before).then(() => { restart(); refreshLibrary(); });
      }
    },
  }, signal);
}

function appendRows(rows: Row[]): void {
  const tbody = element("list-rows");
  for (const r of rows) tbody.append(rowElement(r, ++shownRows));
  element("list-table").hidden = shownRows === 0;
  if (shownRows > 0 && document.getElementById("list-select") === null) element("list-actions").append(selectButton());
  drawSelection();
}

function setHead(title: string, summary: string): void {
  element("list-title").textContent = title;
  element("list-summary").textContent = summary;
  document.title = title + " · TogetherTunes";
}

function setEmpty(text: string): void {
  const empty = element("list-empty");
  empty.textContent = text;
  empty.hidden = shownRows > 0 || text === "";
}

function setMore(next: string | undefined, load: (() => Promise<void>) | undefined): void {
  cursor = next;
  loadMore = load;
  element("list-more").hidden = next === undefined;
}

// ------------------------------------------------------------------ "+ Add from link"

/** "+ Add from link": adds to the library and, with `playlistId`, to that playlist (addlink.ts). */
function addFromLinkButton(playlistId: string | undefined, onAdded: () => void): HTMLButtonElement {
  const b = h("button", { type: "button", class: "pill", text: "+ Add from link" });
  b.addEventListener("click", () => openAddLink({ playlistId, onAdded: (text) => { notice(text); onAdded(); } }));
  return b;
}

// ------------------------------------------------------------------ "Add All: In Order / Shuffled"

function addAllButtons(playlistId: string): HTMLElement {
  const box = h("span", { class: "row add-all" }, h("span", { class: "muted small", text: "Add All:" }));
  for (const [label, shuffle] of [["In Order", false], ["Shuffled", true]] as [string, boolean][]) {
    const b = h("button", { type: "button", text: label,
      title: "Add every song of this playlist to the room's queue" + (shuffle ? ", shuffled" : "") });
    b.addEventListener("click", () => {
      const room = currentRoom();
      if (room === undefined) {
        showError("Open a room first: Add All adds the songs to the room's queue.");
        return;
      }
      showError("");
      b.disabled = true;
      room.request({ type: "queue.addPlaylist", playlistId, shuffle })
        .then((ack) => {
          const added = Array.isArray(ack["entryIds"]) ? (ack["entryIds"] as unknown[]).length : 0;
          const left = Number(ack["overLimit"] ?? 0) + Number(ack["recentlyPlayed"] ?? 0) + Number(ack["skipped"] ?? 0);
          notice(songCount(added) + (ack["state"] === "suggested" ? " suggested to " : " added to ") + room.name()
            + (left > 0 ? " (" + left + " left out by the room's rules)" : "") + ".");
        })
        .catch(showError)
        .finally(() => { b.disabled = false; });
    });
    box.append(b);
  }
  return box;
}

// ------------------------------------------------------------------ the lists

async function loadEntries(path: string, query: Record<string, string>): Promise<Page<LibraryEntry>> {
  const params = new URLSearchParams({ ...query, limit: String(PAGE_SIZE) });
  if (cursor !== undefined) params.set("cursor", cursor);
  return api<Page<LibraryEntry>>("GET", path + "?" + params.toString());
}

/** A library list (All My Songs, Uncategorized Videos), paged. */
async function showLibrary(path: string, query: Record<string, string>, empty: string): Promise<void> {
  const page = await loadEntries(path, query);
  appendRows(page.items.map((e) => ({ item: e.item, addedAt: e.addedAt })));
  setEmpty(empty);
  setMore(page.nextCursor, () => showLibrary(path, query, empty));
}

async function showSongs(): Promise<void> {
  setHead("All My Songs", "");
  element("list-actions").replaceChildren(addFromLinkButton(undefined, () => restart()));
  await showLibrary("/api/library", {}, "Your library is empty. Add songs from a link, or from Manage library.");
}

async function showUncategorized(): Promise<void> {
  setHead("Uncategorized Videos", "Videos in your library that have no type yet: open one to give it one.");
  await showLibrary("/api/library/uncategorized", {}, "No uncategorized videos.");
}

async function showSearch(): Promise<void> {
  songList = "search";
  const q = (new URLSearchParams(location.search).get("q") ?? "").trim();
  const box = document.querySelector<HTMLInputElement>("#app-search input[name=q]");
  if (box !== null && box.value !== q) box.value = q;
  setHead(q === "" ? "Search" : "Search: " + q, "The whole server; your own library first.");
  document.title = "Search · TogetherTunes";
  if (q === "") {
    setEmpty("Type in the search box at the top.");
    return;
  }
  const more = async (): Promise<void> => {
    const params = new URLSearchParams({ q, limit: String(PAGE_SIZE) });
    if (cursor !== undefined) params.set("cursor", cursor);
    const page = await api<Page<{ item: ItemSummary; inYourLibrary: boolean }>>("GET", "/api/search?" + params.toString());
    appendRows(page.items.map((r) => ({ item: r.item })));
    setEmpty("Nothing found.");
    setMore(page.nextCursor, more);
  };
  await more();
}

interface ShowsResponse {
  shows: { name: string; seasons: { season?: number; episodes: LibraryEntry[] }[] }[];
}

/** Movies/Shows: movies, then the shows (each opens its seasons and episodes: `/shows?show=<name>`). */
async function showShows(): Promise<void> {
  const name = new URLSearchParams(location.search).get("show");
  if (name !== null && name.trim() !== "") {
    await showOneShow(name);
    return;
  }
  setHead("Movies/Shows", "");
  const [movies, shows] = await Promise.all([
    api<Page<LibraryEntry>>("GET", "/api/library?type=movie&limit=200"),
    api<ShowsResponse>("GET", "/api/library/shows"),
  ]);
  appendRows(movies.items.map((e) => ({ item: e.item, addedAt: e.addedAt })));
  const groups = element("list-groups");
  const parts: HTMLElement[] = [];
  if (movies.items.length > 0) element("list-table").before(h("h2", { text: "Movies" }));
  if (shows.shows.length > 0) {
    parts.push(h("h2", { text: "Shows" }), h("ul", { class: "show-list" }, ...shows.shows.map((show) => {
      const episodes = show.seasons.reduce((n, season) => n + season.episodes.length, 0);
      const seasons = show.seasons.filter((season) => season.season !== undefined).length;
      return h("li", {},
        h("a", { href: "/shows?show=" + encodeURIComponent(show.name), text: show.name }),
        h("span", { class: "muted small", text: " " + (seasons > 0 ? seasons + (seasons === 1 ? " season · " : " seasons · ") : "")
          + episodes + (episodes === 1 ? " episode" : " episodes") }));
    })));
  }
  groups.replaceChildren(...parts);
  groups.hidden = parts.length === 0;
  element("list-summary").textContent = songCount(movies.items.length).replace("song", "movie") + " · "
    + shows.shows.length + (shows.shows.length === 1 ? " show" : " shows");
  setEmpty(movies.items.length === 0 && shows.shows.length === 0 ? "No movies or shows in your library yet." : "");
}

/** One show: its seasons in order, each with its episodes as list rows (play, queue, ⋯ like songs). */
async function showOneShow(name: string): Promise<void> {
  const r = await api<ShowsResponse>("GET", "/api/library/shows?name=" + encodeURIComponent(name));
  const show = r.shows[0];
  element("list-actions").replaceChildren(h("a", { class: "small", href: "/shows", text: "← Movies/Shows" }));
  if (show === undefined) {
    setHead(name, "");
    setEmpty("No episodes of this show in your library.");
    return;
  }
  const episodes = show.seasons.reduce<LibraryEntry[]>((all, season) => all.concat(season.episodes), []);
  setHead(show.name, episodes.length + (episodes.length === 1 ? " episode" : " episodes") + " · "
    + formatTotal(totalDuration(episodes.map((e) => e.item))));
  const tbody = element("list-rows");
  for (const season of show.seasons) {
    if (show.seasons.length > 1 || season.season !== undefined) {
      tbody.append(h("tr", { class: "season-row" }, h("th", { colspan: "7", text: seasonLabel(season.season) })));
    }
    appendRows(season.episodes.map((e) => ({ item: e.item, addedAt: e.addedAt })));
  }
  setEmpty("No episodes of this show in your library.");
}

/** Liked Songs or a playlist: header with cover, count and time; Add from link; Add All. */
async function showPlaylist(p: PlaylistDetail & { owner?: { name: string } }, own: boolean): Promise<void> {
  songList = !own ? "othersPlaylist" : p.builtin === "likes" ? "liked" : "playlist";
  listPlaylistId = own ? p.id : undefined;
  const rows = p.entries.map((e) => ({ item: e.item, addedAt: e.addedAt, entryId: e.id }));
  setHead(p.name, songCount(p.entries.length) + " · " + formatTotal(totalDuration(p.entries.map((e) => e.item)))
    + (p.owner !== undefined ? " · " + p.owner.name + "'s playlist" : ""));
  const first = p.entries[0];
  if (first !== undefined && first.item.status === "ready") {
    element("list-cover").replaceWith(coverCell(first.item, "cover list-cover", "list-cover"));
  }
  const actions: HTMLElement[] = [];
  if (own) actions.push(addFromLinkButton(p.id, () => restart()));
  if (own && p.entries.length > 0) actions.push(addAllButtons(p.id));
  if (own && p.builtin === undefined) {
    actions.push(h("a", { class: "small", href: "/playlists/?id=" + encodeURIComponent(p.id), text: "Edit playlist" }));
  }
  element("list-actions").replaceChildren(...actions);
  if (own && !mobile()) playlistDropZone(p);
  appendRows(rows);
  setEmpty(own ? "This playlist is empty. Add songs from a link." : "This playlist is empty.");
}

async function showLiked(): Promise<void> {
  setHead("Liked Songs", "");
  await showPlaylist(await api<PlaylistDetail>("GET", "/api/me/likes"), true);
}

async function showOnePlaylist(id: string): Promise<void> {
  const room = new URLSearchParams(location.search).get("room");
  if (room !== null && room !== "") {
    // Another person's playlist, readable while it is selected for autoplay in the room.
    const r = await api<{ owner: { name: string }; playlist: PlaylistDetail }>("GET",
      "/api/rooms/" + encodeURIComponent(room) + "/autoplay/playlists/" + encodeURIComponent(id));
    await showPlaylist({ ...r.playlist, owner: r.owner }, false);
    return;
  }
  await showPlaylist(await api<PlaylistDetail>("GET", "/api/playlists/" + encodeURIComponent(id)), true);
}

function listName(): { list: ListName; id?: string } {
  const route = routeOf(location.pathname);
  const list = (route.kind === "view" ? route.params["list"] : undefined) as ListName | undefined;
  const id = route.kind === "view" ? route.params["id"] : undefined;
  return { list: list ?? "songs", id };
}

async function show(): Promise<void> {
  const { list, id } = listName();
  switch (list) {
    case "songs": return showSongs();
    case "shows": return showShows();
    case "liked": return showLiked();
    case "uncategorized": return showUncategorized();
    case "search": return showSearch();
    case "playlist": return id !== undefined ? showOnePlaylist(id) : showSongs();
  }
}

/** Shows the list again from the top (after an add). */
function restart(retrying = false): void {
  shownRows = 0;
  for (const k of Object.keys(rowEntries)) delete rowEntries[k];
  setSelecting(false);
  cursor = undefined;
  element("list-rows").replaceChildren();
  element("list-table").hidden = true;
  show().catch(retrying ? loadFailed : showError).finally(() => { element("list-status").hidden = true; });
}

async function start(): Promise<void> {
  const me = await requireUser();
  if (me === null) return;
  renderNav(me);
  element("list-select-cancel").addEventListener("click", () => setSelecting(false));
  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape" && selection.size > 0 && document.querySelector(".popup-menu") === null) setSelecting(false);
  }, { signal: pageSignal() });
  pageSignal().addEventListener("abort", () => window.clearTimeout(linkTimer));
  element("list-select-queue").addEventListener("click", () => {
    const items = selection.inOrder(rowKeys()).map((k) => rowItems[k]).filter((i): i is ItemSummary => i !== undefined);
    if (items.length === 0) return;
    void addToQueue(items).then((ok) => { if (ok) setSelecting(false); });
  });
  pageSignal().addEventListener("abort", () => document.body.classList.remove("has-select-bar"));
  element("list-more").addEventListener("click", () => {
    const more = loadMore;
    if (more !== undefined) more().catch(showError);
  });
  try {
    await show();
  } catch (e) {
    loadFailed(e);
  } finally {
    element("list-status").hidden = true;
  }
}

/** The list couldn't be loaded: the error with "Try again". */
function loadFailed(e: unknown): void {
  const retry = h("button", { type: "button", class: "link", text: "Try again" });
  retry.addEventListener("click", () => {
    element("list-error").textContent = "";
    element("list-status").hidden = false;
    restart(true);
  });
  element("list-error").replaceChildren("Couldn't load this list: " + message(e) + " ", retry);
}

onReady(start);
