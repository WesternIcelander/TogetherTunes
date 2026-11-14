// The desktop layout's middle lists (docs/ui.md#layout-desktop), the `list` view of the router:
// - All My Songs (/songs): the caller's library, newest first, with "+ Add from link" (library only);
// - Movies/Shows (/shows): movies, then shows grouped by show and season;
// - Liked Songs (/liked) and a playlist (/playlists/{id}; another person's playlist selected for
//   autoplay in the room with ?room=): cover, name, song count and total time, "+ Add from link"
//   (own playlists) and "Add All: In Order / Shuffled" into the current room's queue;
// - Uncategorized Videos (/uncategorized) and the search results (/search?q=, the whole server,
//   the caller's own library first).
// Rows: #, cover, title and artist, album, date added, time. Double-click, multi-select, drag and
// drop and the ⋯ menus come with the interaction steps; the server enforces every rule.
import { api } from "./api.js";
import { withClassification } from "./classifyprompt.js";
import { formatDuration, h } from "./dom.js";
import { itemPath, mediaPath, type ItemSummary, type LibraryEntry, type Page, type PlaylistDetail } from "./media.js";
import { currentRoom, refreshLibrary } from "./nav.js";
import { formatTotal, songCount, totalDuration } from "./panels.js";
import { routeOf, type ListName } from "./router.js";
import { element, message, onReady, renderNav, requireUser } from "./session.js";

/** A row of the table: the item, and when it was added here (absent for search results). */
interface Row {
  item: ItemSummary;
  addedAt?: number;
}

const PAGE_SIZE = 100;

let cursor: string | undefined;
let loadMore: (() => Promise<void>) | undefined;
let shownRows = 0;

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

function rowElement(r: Row, n: number): HTMLTableRowElement {
  const item = r.item;
  const status = item.status === "ready" ? null
    : h("span", { class: "muted small", text: " · " + (item.status === "failed" ? "Failed" : item.status) });
  return h("tr", { "data-item": item.id },
    h("td", { class: "num muted", text: String(n) }),
    h("td", {}, coverCell(item)),
    h("td", {},
      h("a", { href: itemPath(item.id), class: "row-title", text: item.title }),
      h("div", { class: "muted small" }, item.artist ?? "", status)),
    h("td", { class: "small", text: item.album ?? "" }),
    h("td", { class: "small muted", text: dateText(r.addedAt) }),
    h("td", { class: "num small", text: formatDuration(item.durationMs) }));
}

function appendRows(rows: Row[]): void {
  const tbody = element("list-rows");
  for (const r of rows) tbody.append(rowElement(r, ++shownRows));
  element("list-table").hidden = shownRows === 0;
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

/** The modal "Enter a link" dialog; adds to the library and, with `playlistId`, to that playlist. */
function addFromLinkButton(playlistId: string | undefined, onAdded: () => void): HTMLButtonElement {
  const b = h("button", { type: "button", class: "pill", text: "+ Add from link" });
  b.addEventListener("click", () => openAddLink(playlistId, onAdded));
  return b;
}

function openAddLink(playlistId: string | undefined, onAdded: () => void): void {
  const input = h("input", { type: "url", name: "url", required: true, placeholder: "https://…",
    "aria-label": "Link", class: "grow" });
  const error = h("p", { class: "error", role: "alert" });
  const status = h("p", { class: "muted small", role: "status" });
  const add = h("button", { type: "submit", text: "Add" });
  const cancel = h("button", { type: "button", text: "Cancel" });
  const form = h("form", { class: "form", role: "dialog", "aria-modal": "true", "aria-label": "Add from link" },
    h("label", {}, "Enter a link:", input), status, error, h("div", { class: "row" }, add, cancel));
  const overlay = h("div", { class: "classify-prompt" }, form);
  const close = (): void => overlay.remove();
  cancel.addEventListener("click", close);
  overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(); });
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    const url = input.value.trim();
    if (url === "") return;
    add.disabled = true;
    error.textContent = "";
    status.textContent = "Looking up the link…";
    const body: Record<string, unknown> = { url, addToLibrary: true };
    if (playlistId !== undefined) body["playlistId"] = playlistId;
    withClassification((c) => api<{ itemId: string; status: string }>("POST", "/api/imports/youtube",
      c === undefined ? body : { ...body, ...c }), url)
      .then((r) => {
        if (r === null) {
          status.textContent = "";
          return;
        }
        close();
        notice(r.status === "ready" ? "Added." : "Added; it is being fetched and will be ready shortly.");
        onAdded();
        refreshLibrary();
      })
      .catch((e: unknown) => {
        status.textContent = "";
        error.textContent = message(e);
      })
      .finally(() => { add.disabled = false; });
  });
  document.body.append(overlay);
  input.focus();
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

async function showShows(): Promise<void> {
  setHead("Movies/Shows", "");
  const [movies, shows] = await Promise.all([
    api<Page<LibraryEntry>>("GET", "/api/library?type=movie&limit=200"),
    api<ShowsResponse>("GET", "/api/library/shows"),
  ]);
  appendRows(movies.items.map((e) => ({ item: e.item, addedAt: e.addedAt })));
  const groups = element("list-groups");
  const parts: HTMLElement[] = [];
  if (movies.items.length > 0) element("list-table").before(h("h2", { text: "Movies" }));
  for (const show of shows.shows) {
    const seasons = show.seasons.map((s) => h("details", { class: "season", open: show.seasons.length === 1 },
      h("summary", { text: s.season === undefined ? "Episodes" : "Season " + s.season }),
      h("ol", { class: "episodes" }, ...s.episodes.map((e) => h("li", {},
        h("a", { href: itemPath(e.item.id), text: e.item.title }),
        h("span", { class: "muted small num", text: " " + formatDuration(e.item.durationMs) }))))));
    parts.push(h("section", { class: "show" }, h("h2", { text: show.name }), ...seasons));
  }
  groups.replaceChildren(...parts);
  groups.hidden = parts.length === 0;
  element("list-summary").textContent = songCount(movies.items.length).replace("song", "movie") + " · "
    + shows.shows.length + (shows.shows.length === 1 ? " show" : " shows");
  setEmpty(parts.length === 0 ? "No movies or shows in your library yet." : "");
}

/** Liked Songs or a playlist: header with cover, count and time; Add from link; Add All. */
async function showPlaylist(p: PlaylistDetail & { owner?: { name: string } }, own: boolean): Promise<void> {
  const rows = p.entries.map((e) => ({ item: e.item, addedAt: e.addedAt }));
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
function restart(): void {
  shownRows = 0;
  cursor = undefined;
  element("list-rows").replaceChildren();
  element("list-table").hidden = true;
  show().catch(showError);
}

async function start(): Promise<void> {
  const me = await requireUser();
  if (me === null) return;
  renderNav(me);
  element("list-more").addEventListener("click", () => {
    const more = loadMore;
    if (more !== undefined) more().catch(showError);
  });
  try {
    await show();
  } catch (e) {
    showError(e);
  } finally {
    element("list-status").hidden = true;
  }
}

onReady(start);
