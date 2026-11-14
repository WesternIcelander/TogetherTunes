// The library page (/library/): the caller's library (`GET /api/library`) with search, kind
// filter and paging, items still being ingested with their job status, and the
// "Add media" panel (uploads and YouTube imports). "Search the whole server" switches the list to
// every item on the server (`GET /api/items/search`): item metadata only, never whose libraries
// hold an item, with Add to Library on the ones not in the caller's library.
import { withClassification } from "./classifyprompt.js";
import { api } from "./api.js";
import { renderAddMedia } from "./addmedia.js";
import { button, formatDuration, formatTime, h } from "./dom.js";
import {
  ingestBadge, inProgress, itemTitleCell, kindLabel, playlistPicker, watchItem, type JobView, type LibraryEntry,
  type Page, type ItemSummary,
} from "./media.js";
import { element, message, onReady, renderNav, requireUser } from "./session.js";
import { rememberedWholeServer, rememberWholeServer } from "./searchscope.js";
import { episodeCode, filterShows, seasonLabel, type ShowGroup } from "./shows.js";

let cursor: string | undefined;
let query = "";
let kind = "";
/** Search the whole server instead of the caller's library. */
let wholeServer = false;
/** Stops the job pollers of the rows on screen (when the list is reloaded). */
let stopWatchers: (() => void)[] = [];
let reloadTimer: number | undefined;

function showError(e: unknown): void {
  element("library-error").textContent = message(e);
}

function showNotice(text: string): void {
  const notice = element("library-notice");
  notice.textContent = text;
  notice.hidden = text === "";
}

const VIA: Record<string, string> = { upload: "Uploaded", youtube: "YouTube", manual: "Added" };

function entryRow(entry: LibraryEntry): HTMLTableRowElement {
  const item = entry.item;
  const statusCell = h("td");
  const setStatus = (status: ItemSummary["status"], job?: JobView): void => {
    statusCell.replaceChildren(ingestBadge(status, job) ?? "");
  };
  setStatus(item.status);
  if (inProgress(item.status)) {
    stopWatchers.push(watchItem(item.id, ({ item: now, job }) => {
      setStatus(now.status, job);
    }));
  }
  const via = h("td", { class: "muted small" }, VIA[entry.via] ?? entry.via);
  via.title = formatTime(entry.addedAt);

  const tr = h("tr");
  const remove = button("Remove", async () => {
    await api("DELETE", "/api/library/" + encodeURIComponent(item.id));
    tr.remove();
  }, showError);
  remove.title = "Remove from my library";
  const picker = playlistPicker(item.id, (text, e) => {
    if (e !== undefined) showError(e);
    else showNotice(text);
  });
  tr.append(
    h("td", {}, itemTitleCell(item)),
    h("td", { class: "small", text: kindLabel(item) }),
    h("td", { class: "small num", text: formatDuration(item.durationMs) }),
    statusCell,
    via,
    h("td", { class: "actions" }, h("span", { class: "row" }, picker, remove)));
  return tr;
}

/** A whole-server search result (`GET /api/items/search`). */
interface ServerHit { item: ItemSummary; inYourLibrary: boolean }

function serverRow(hit: ServerHit): HTMLTableRowElement {
  const item = hit.item;
  const action = h("td", { class: "actions" });
  const inLibrary = (): void => {
    action.replaceChildren(h("span", { class: "muted small", text: "In your library" }));
  };
  if (hit.inYourLibrary) {
    inLibrary();
  } else {
    action.append(button("Add to Library", async () => {
      if (await withClassification((c) => api("PUT", "/api/library/" + encodeURIComponent(item.id), c),
        item.title) === null) return;
      inLibrary();
    }, showError));
  }
  return h("tr", {},
    h("td", {}, itemTitleCell(item)),
    h("td", { class: "small", text: kindLabel(item) }),
    h("td", { class: "small num", text: formatDuration(item.durationMs) }),
    h("td", {}, ingestBadge(item.status, undefined) ?? ""),
    h("td"),
    action);
}

async function load(reset: boolean): Promise<void> {
  const tbody = element<HTMLTableSectionElement>("library-rows");
  if (reset) {
    cursor = undefined;
    stopWatchers.forEach((stop) => stop());
    stopWatchers = [];
  }
  const params = new URLSearchParams({ limit: "50" });
  if (query !== "") params.set("q", query);
  if (kind !== "") params.set(kind === "shows" ? "type" : "kind", kind === "shows" ? "show" : kind);
  if (cursor !== undefined) params.set("cursor", cursor);
  let next: string | undefined;
  if (kind === "shows" && !wholeServer) {
    // The library's shows, grouped by show and season (GET /api/library/shows); no paging.
    const res = await api<{ shows: ShowGroup<LibraryEntry>[] }>("GET", "/api/library/shows");
    tbody.replaceChildren();
    for (const show of filterShows(res.shows, query)) {
      tbody.append(h("tr", { class: "group" }, h("th", { colspan: "6", text: show.name })));
      for (const season of show.seasons) {
        tbody.append(h("tr", { class: "group" }, h("td", { colspan: "6", class: "muted small",
          text: seasonLabel(season.season) })));
        for (const entry of season.episodes) {
          const row = entryRow(entry);
          const kindCell = row.cells.item(1);
          if (entry.item.show !== undefined && kindCell !== null) kindCell.textContent = episodeCode(entry.item.show);
          tbody.append(row);
        }
      }
    }
  } else if (wholeServer) {
    const page = await api<Page<ServerHit>>("GET", "/api/items/search?" + params.toString());
    if (reset) tbody.replaceChildren();
    for (const hit of page.items) tbody.append(serverRow(hit));
    next = page.nextCursor;
  } else {
    const page = await api<Page<LibraryEntry>>("GET", "/api/library?" + params.toString());
    if (reset) tbody.replaceChildren();
    for (const entry of page.items) tbody.append(entryRow(entry));
    next = page.nextCursor;
  }
  cursor = next;
  element("library-more").hidden = cursor === undefined;
  const empty = element("library-empty");
  empty.hidden = tbody.childElementCount > 0;
  empty.textContent = query !== "" || kind !== "" || wholeServer ? "Nothing matches."
    : "Your library is empty. Add media below.";
}

/** Reloads the list soon (several changes in a row cause one reload). */
function reloadSoon(): void {
  if (reloadTimer !== undefined) window.clearTimeout(reloadTimer);
  reloadTimer = window.setTimeout(() => {
    reloadTimer = undefined;
    load(true).catch(showError);
  }, 500);
}

async function start(): Promise<void> {
  const status = element("library-status");
  const me = await requireUser().catch((e: unknown) => {
    status.textContent = message(e);
    return null;
  });
  if (me === null) return;
  renderNav(me);

  const search = element<HTMLFormElement>("library-search");
  const applySearch = (): void => {
    const data = new FormData(search);
    query = String(data.get("q") ?? "").trim();
    kind = String(data.get("kind") ?? "");
    wholeServer = data.get("all") !== null;
    rememberWholeServer(wholeServer);
    element("library-error").textContent = "";
    load(true).catch(showError);
  };
  search.addEventListener("submit", (ev) => {
    ev.preventDefault();
    applySearch();
  });
  // (form.requestSubmit needs Safari 16)
  element<HTMLSelectElement>("library-kind").addEventListener("change", applySearch);
  const all = element<HTMLInputElement>("library-all");
  all.checked = rememberedWholeServer();
  wholeServer = all.checked;
  all.addEventListener("change", applySearch);
  const more = element<HTMLButtonElement>("library-more");
  more.addEventListener("click", () => {
    more.disabled = true;
    load(false).catch(showError).finally(() => { more.disabled = false; });
  });

  renderAddMedia(element("add-media"), {
    canUpload: me.permissions.includes("library.upload"),
    canYoutube: me.permissions.includes("library.youtube"),
    onItemChanged: reloadSoon,
  });

  try {
    await load(true);
  } catch (e) {
    showError(e);
  }
  status.hidden = true;
  element("library-body").hidden = false;
}

onReady(start);
