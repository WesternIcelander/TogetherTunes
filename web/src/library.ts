// The library page (/library/): the caller's library (`GET /api/library`) with search, kind
// filter and paging, items still being ingested with their job status, received shares, and the
// "Add media" panel (uploads and YouTube imports). "Search the whole server" switches the list to
// every item on the server (`GET /api/items/search`): item metadata only, never whose libraries
// hold an item, with Add to Library on the ones not in the caller's library.
import { api } from "./api.js";
import { actorLabel } from "./actor.js";
import { renderAddMedia } from "./addmedia.js";
import { button, formatDuration, formatTime, h } from "./dom.js";
import {
  ingestBadge, inProgress, itemTitleCell, kindLabel, playlistPicker, watchItem, type JobView, type LibraryEntry,
  type Page, type ItemSummary,
} from "./media.js";
import { element, message, onReady, renderNav, requireUser } from "./session.js";
import { rememberedWholeServer, rememberWholeServer } from "./searchscope.js";

interface Share {
  id: string;
  item: ItemSummary;
  from: { kind: "user" | "guest"; id: string; name: string; isGuest: boolean };
  message?: string;
  createdAt: number;
  seen?: boolean;
}

interface SharePage extends Page<Share> { unseenCount: number }

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

const VIA: Record<string, string> = { upload: "Uploaded", youtube: "YouTube", share: "Shared", manual: "Added" };

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
  if (entry.sharedBy !== undefined) via.append(" by ", actorLabel(entry.sharedBy));
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
      await api("PUT", "/api/library/" + encodeURIComponent(item.id));
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
  if (kind !== "") params.set("kind", kind);
  if (cursor !== undefined) params.set("cursor", cursor);
  let next: string | undefined;
  if (wholeServer) {
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

async function loadShares(): Promise<void> {
  const page = await api<SharePage>("GET", "/api/shares?direction=in&limit=20");
  const section = element("shares");
  const list = element("share-rows");
  const unseen = page.items.filter((s) => s.seen === false);
  section.hidden = unseen.length === 0;
  element("share-count").textContent = page.unseenCount > 0 ? String(page.unseenCount) : "";
  list.replaceChildren(...unseen.map((s) => h("li", {},
    itemTitleCell(s.item),
    h("div", { class: "muted small" }, "from ", actorLabel(s.from), " · " + formatTime(s.createdAt)),
    s.message !== undefined ? h("blockquote", { class: "share-message", text: s.message }) : null)));
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
  element<HTMLButtonElement>("shares-seen").addEventListener("click", () => {
    api("POST", "/api/shares/seen", { all: true }).then(() => loadShares()).catch(showError);
  });

  renderAddMedia(element("add-media"), {
    canUpload: me.permissions.includes("library.upload"),
    canYoutube: me.permissions.includes("library.youtube"),
    onItemChanged: reloadSoon,
  });

  try {
    await Promise.all([load(true), loadShares()]);
  } catch (e) {
    showError(e);
  }
  status.hidden = true;
  element("library-body").hidden = false;
}

onReady(start);
