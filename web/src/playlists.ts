// The playlists page (/playlists/): the caller's playlists with "New playlist", and one playlist
// (/playlists/?id=…) with its entries: reorder (move up/down, which the API does with
// `beforeEntryId`), remove, rename, change the description, delete.
import { api } from "./api.js";
import { button, formatDuration, formatTime, h } from "./dom.js";
import {
  itemTitleCell, kindLabel, statusBadge, type Page, type Playlist, type PlaylistDetail, type PlaylistEntry,
} from "./media.js";
import { navigate } from "./nav.js";
import { element, message, onReady, renderNav, requireUser } from "./session.js";

let cursor: string | undefined;

function showError(e: unknown): void {
  element("playlists-error").textContent = message(e);
}

function playlistHref(id: string): string {
  return "/playlists/?id=" + encodeURIComponent(id);
}

// ------------------------------------------------------------------ list

function playlistRow(p: Playlist): HTMLTableRowElement {
  const tr = h("tr", {},
    h("td", {}, h("a", { href: playlistHref(p.id), text: p.name }),
      p.description !== undefined ? h("div", { class: "muted small clamp", text: p.description }) : null),
    h("td", { class: "num", text: String(p.entryCount) }),
    h("td", { class: "small muted", text: formatTime(p.updatedAt) }));
  tr.addEventListener("click", (ev) => {
    if ((ev.target as HTMLElement).closest("a") === null) navigate(playlistHref(p.id));
  });
  return tr;
}

async function loadList(reset: boolean): Promise<void> {
  const tbody = element<HTMLTableSectionElement>("playlist-rows");
  if (reset) cursor = undefined;
  let path = "/api/playlists?limit=50";
  if (cursor !== undefined) path += "&cursor=" + encodeURIComponent(cursor);
  const page = await api<Page<Playlist>>("GET", path);
  if (reset) tbody.replaceChildren();
  for (const p of page.items) tbody.append(playlistRow(p));
  cursor = page.nextCursor;
  element("playlist-more").hidden = cursor === undefined;
  element("playlist-empty").hidden = tbody.childElementCount > 0;
}

function startList(): void {
  element("playlist-list").hidden = false;
  const form = element<HTMLFormElement>("playlist-create");
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    element("playlists-error").textContent = "";
    const data = new FormData(form);
    const body: Record<string, string> = { name: String(data.get("name") ?? "") };
    const description = String(data.get("description") ?? "").trim();
    if (description !== "") body["description"] = description;
    api<Playlist>("POST", "/api/playlists", body)
      .then((p) => navigate(playlistHref(p.id)))
      .catch(showError);
  });
  const more = element<HTMLButtonElement>("playlist-more");
  more.addEventListener("click", () => {
    more.disabled = true;
    loadList(false).catch(showError).finally(() => { more.disabled = false; });
  });
  loadList(true).catch(showError);
}

// ------------------------------------------------------------------ one playlist

let current: PlaylistDetail;

function totalDuration(entries: PlaylistEntry[]): string {
  let ms = 0;
  for (const e of entries) ms += e.item.durationMs ?? 0;
  return formatDuration(ms);
}

function renderDetail(): void {
  const p = current;
  document.title = p.name + " · TogetherTunes";
  element("playlist-name").textContent = p.name;
  const desc = element("playlist-description");
  desc.textContent = p.description ?? "";
  desc.hidden = p.description === undefined;
  element("playlist-summary").textContent = p.entries.length + (p.entries.length === 1 ? " entry" : " entries")
    + (p.entries.length > 0 ? " · " + totalDuration(p.entries) : "");
  const tbody = element<HTMLTableSectionElement>("entry-rows");
  tbody.replaceChildren(...p.entries.map((entry, i) => entryRow(entry, i)));
  element("entry-empty").hidden = p.entries.length > 0;
}

/** Moves entry `i` so that it ends up at index `to`. */
async function move(i: number, to: number): Promise<void> {
  const entries = current.entries;
  const entry = entries[i];
  if (entry === undefined || to < 0 || to >= entries.length || to === i) return;
  // The entry that will follow it after the move (null = the end).
  const after = to > i ? entries[to + 1] : entries[to];
  await api("PATCH", "/api/playlists/" + encodeURIComponent(current.id) + "/entries/" + encodeURIComponent(entry.id),
    { beforeEntryId: after === undefined ? null : after.id });
  entries.splice(i, 1);
  entries.splice(to, 0, entry);
  renderDetail();
}

function entryRow(entry: PlaylistEntry, i: number): HTMLTableRowElement {
  const n = current.entries.length;
  const up = button("↑", () => move(i, i - 1), showError, "icon");
  up.title = "Move up";
  up.setAttribute("aria-label", "Move up");
  up.disabled = i === 0;
  const down = button("↓", () => move(i, i + 1), showError, "icon");
  down.title = "Move down";
  down.setAttribute("aria-label", "Move down");
  down.disabled = i === n - 1;
  const remove = button("Remove", async () => {
    await api("DELETE", "/api/playlists/" + encodeURIComponent(current.id) + "/entries/" + encodeURIComponent(entry.id));
    current.entries.splice(current.entries.indexOf(entry), 1);
    renderDetail();
  }, showError);
  return h("tr", {},
    h("td", { class: "num muted", text: String(i + 1) }),
    h("td", {}, itemTitleCell(entry.item)),
    h("td", { class: "small", text: kindLabel(entry.item) }),
    h("td", { class: "small num", text: formatDuration(entry.item.durationMs) }),
    h("td", {}, statusBadge(entry.item.status) ?? ""),
    h("td", { class: "actions" }, h("span", { class: "row" }, up, down, remove)));
}

async function startDetail(id: string): Promise<void> {
  current = await api<PlaylistDetail>("GET", "/api/playlists/" + encodeURIComponent(id));
  element("playlist-detail").hidden = false;
  renderDetail();

  const form = element<HTMLFormElement>("playlist-edit");
  // Liked Songs can't be renamed or deleted.
  const edit = form.closest("details");
  if (current.builtin !== undefined && edit !== null) edit.hidden = true;
  (form.elements.namedItem("name") as HTMLInputElement).value = current.name;
  (form.elements.namedItem("description") as HTMLTextAreaElement).value = current.description ?? "";
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    element("playlists-error").textContent = "";
    const data = new FormData(form);
    api<Playlist>("PATCH", "/api/playlists/" + encodeURIComponent(current.id), {
      name: String(data.get("name") ?? ""),
      description: String(data.get("description") ?? ""),
    }).then((p) => {
      current = { ...current, ...p, entries: current.entries };
      renderDetail();
      (form.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open");
    }).catch(showError);
  });
  element<HTMLButtonElement>("playlist-delete").addEventListener("click", () => {
    if (!confirm("Delete the playlist \"" + current.name + "\"? The items stay in your library.")) return;
    api("DELETE", "/api/playlists/" + encodeURIComponent(current.id))
      .then(() => navigate("/playlists/"))
      .catch(showError);
  });
}

async function start(): Promise<void> {
  const status = element("playlists-status");
  const me = await requireUser().catch((e: unknown) => {
    status.textContent = message(e);
    return null;
  });
  if (me === null) return;
  renderNav(me);
  status.hidden = true;
  const id = new URLSearchParams(location.search).get("id");
  if (id === null || id === "") {
    startList();
  } else {
    await startDetail(id).catch(showError);
  }
}

onReady(start);
