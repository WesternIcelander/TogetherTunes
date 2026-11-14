// The desktop layout around the views (docs/ui.md#layout-desktop): the header's search, the left
// sidebar (Library | Rooms), the right panel (Queue | Recents | Room) with the video panel, the
// player bar and the Now Playing View. The view in the middle (#app) is the shell's (shell.ts).
//
// The frame follows the room the room view has open (shown, or playing in the background while
// other views are shown): it reads that view's room state and sends actions through its socket
// (nav.ts `ShellRoom`); it has no socket of its own. Without a room the right panel is hidden and
// the player bar says so. The rules (who gets which button) are in the DOM-free panels.ts; the
// server enforces them all.
import { api } from "./api.js";
import { actorLabel } from "./actor.js";
import { withClassification } from "./classifyprompt.js";
import { formatDuration, h } from "./dom.js";
import { ingestText } from "./jobtext.js";
import { itemPath, type Page, type Playlist } from "./media.js";
import { navigate, setFrame, type ShellRoom } from "./nav.js";
import {
  canHeart, canSkipNow, entryButtons, LIBRARY_LINKS, MuteState, mySelectedPlaylists, othersAutoplay, parseVolume,
  personModeText, playerControls, presenceText, queueSections, suggestionsPlace, volumeGain, voteSkip,
  type AutoplaySelectionLike,
} from "./panels.js";
import type { CurrentEntry, HistoryEntry, RoomItem, RoomState, WaitingEntry } from "./roomclient.js";
import type { RoomListEntry } from "./roomlist.js";
import { playlistPath } from "./router.js";
import { loadMe, message, type Me } from "./session.js";

/** Where the player bar's volume is remembered (this browser only). */
const VOLUME_KEY = "togethertunes.volume";

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Blocked storage: not remembered.
  }
}

function byId<T extends HTMLElement = HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (el === null) throw new Error("missing #" + id);
  return el as T;
}

function cover(item: { id: string; coverUrl?: string } | undefined, className: string): HTMLElement {
  if (item === undefined || item.coverUrl === undefined) {
    return h("div", { class: className + " cover-none", "aria-hidden": "true" });
  }
  return h("img", { class: className, src: item.coverUrl, alt: "", loading: "lazy" });
}

/** What the shell offers the frame. */
export interface FrameHost {
  /** The room the room view has open (shown or in the background), if any. */
  room(): ShellRoom | undefined;
  /** Whether the room view is the view shown. */
  roomShown(): boolean;
  /** Stops the room (leaves it). */
  leave(): void;
}

interface LikesCache {
  [itemId: string]: boolean | undefined;
}

export class Frame {
  readonly #host: FrameHost;
  #me: Me | undefined;
  #playlists: Playlist[] = [];
  #uncategorized = 0;
  #selections: AutoplaySelectionLike[] = [];
  /** The room the autoplay selections were loaded for. */
  #selectionsRoom = "";
  #libraryLoading = false;
  readonly #likes: LikesCache = {};
  readonly #mute = new MuteState();
  #volume = parseVolume(storageGet(VOLUME_KEY));
  #seeking = false;
  /** The room id the panel was last drawn for (a new room resets the tab state). */
  #roomId = "";
  #roomsTimer: number | undefined;
  #roomsQuery = "";

  constructor(host: FrameHost) {
    this.#host = host;
    setFrame({ refreshLibrary: () => { void this.#loadLibrary(); }, room: () => host.room() });
    this.#tabs("app-sidebar", (tab) => {
      if (tab === "rooms") void this.#loadRooms();
    });
    this.#tabs("app-panel");
    this.#wirePlayerBar();
    byId<HTMLInputElement>("app-room-search").addEventListener("input", () => {
      window.clearTimeout(this.#roomsTimer);
      this.#roomsTimer = window.setTimeout(() => { void this.#loadRooms(); }, 250);
    });
    // The video panel shows while the player shows a video (listen.ts hides its container otherwise).
    const container = byId("room-player");
    new MutationObserver(() => this.#drawVideo()).observe(container, { attributes: true, attributeFilter: ["hidden"] });
    window.setInterval(() => this.#tick(), 250);
    document.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape" && document.body.classList.contains("now-view-open")) this.#setNowView(false);
    });
    document.addEventListener("click", (ev) => this.#closeMenus(ev));
  }

  /** Loads who is here and draws the frame. */
  async start(): Promise<void> {
    await this.#reloadMe();
  }

  /** The shell showed another view (`path`): mark it in the sidebar; pick up a login or logout. */
  navigated(path: string): void {
    this.#markCurrent(path);
    if (this.#me?.kind !== "user") void this.#reloadMe();
  }

  async #reloadMe(): Promise<void> {
    try {
      this.#me = await loadMe();
    } catch {
      return;
    }
    const user = this.#me.kind === "user";
    document.body.classList.toggle("signed-in", user);
    byId("app-sidebar").hidden = !user;
    byId("app-search").hidden = !user;
    if (user) {
      await this.#loadLibrary();
    }
    this.update();
  }

  // ---------------------------------------------------------------- tabs

  /** Tab buttons in `rootId` switch its panes (`app-<tab>`). */
  #tabs(rootId: string, onShow?: (tab: string) => void): void {
    const root = byId(rootId);
    const buttons = Array.from(root.querySelectorAll<HTMLButtonElement>(".tabs [data-tab]"));
    for (const b of buttons) {
      b.addEventListener("click", () => {
        for (const other of buttons) {
          const on = other === b;
          other.setAttribute("aria-selected", on ? "true" : "false");
          byId("app-" + (other.dataset["tab"] ?? "")).hidden = !on;
        }
        onShow?.(b.dataset["tab"] ?? "");
      });
    }
  }

  // ---------------------------------------------------------------- left sidebar

  async #loadLibrary(): Promise<void> {
    if (this.#me?.kind !== "user" || this.#libraryLoading) return;
    this.#libraryLoading = true;
    try {
      const [playlists, uncategorized] = await Promise.all([
        api<Page<Playlist>>("GET", "/api/playlists?limit=200"),
        api<{ total: number }>("GET", "/api/library/uncategorized?limit=1"),
      ]);
      this.#playlists = playlists.items;
      this.#uncategorized = uncategorized.total;
    } catch {
      // Shown as it was; the next navigation tries again.
    } finally {
      this.#libraryLoading = false;
    }
    await this.#loadSelections(true);
    this.#drawLibrary();
  }

  /** The current room's autoplay selections (for the dots and the others' playlists). */
  async #loadSelections(force: boolean): Promise<void> {
    const room = this.#host.room();
    const id = room?.id() ?? "";
    if (!force && id === this.#selectionsRoom) return;
    this.#selectionsRoom = id;
    if (id === "") {
      this.#selections = [];
      return;
    }
    try {
      const r = await api<{ selections: AutoplaySelectionLike[] }>("GET",
        "/api/rooms/" + encodeURIComponent(id) + "/autoplay");
      this.#selections = r.selections;
    } catch {
      this.#selections = [];
    }
  }

  #drawLibrary(): void {
    const me = this.#me;
    const pane = byId("app-library");
    if (me?.kind !== "user") {
      pane.replaceChildren();
      return;
    }
    const links: HTMLElement[] = LIBRARY_LINKS.map((l) => h("a", { class: "side-link", href: l.path, text: l.label }));
    const newPlaylist = h("button", { type: "button", class: "side-link side-new", id: "app-new-playlist" },
      "+ New Playlist");
    newPlaylist.addEventListener("click", () => { void this.#newPlaylist(); });
    links.push(newPlaylist);
    if (this.#uncategorized > 0) {
      links.push(h("a", { class: "side-link", href: "/uncategorized" }, "Uncategorized Videos ",
        h("span", { class: "muted small num", text: String(this.#uncategorized) })));
    }
    const selected = mySelectedPlaylists(this.#selections, me.id);
    const list = h("ul", { class: "side-list playlists-list" });
    for (const p of this.#playlists) {
      if (p.builtin === "likes") continue;
      const dot = selected.has(p.id);
      list.append(h("li", {},
        h("a", { class: "side-link", href: playlistPath(p.id), title: dot ? "Selected for autoplay in this room" : "" },
          h("span", { class: "autoplay-dot" + (dot ? " on" : ""), "aria-hidden": "true", text: dot ? "●" : "" }),
          p.name)));
    }
    const parts: HTMLElement[] = [h("nav", { class: "side-links" }, ...links), list];
    const room = this.#host.room();
    const others = othersAutoplay(this.#selections, me.id);
    if (room !== undefined && others.length > 0) {
      const ul = h("ul", { class: "side-list" });
      for (const o of others) {
        ul.append(h("li", {},
          h("a", { class: "side-link", href: playlistPath(o.id) + "?room=" + encodeURIComponent(room.id()) },
            h("span", { class: "autoplay-dot on", "aria-hidden": "true", text: "●" }), o.name,
            h("span", { class: "muted small", text: " · " + o.owner }))));
      }
      parts.push(h("section", { class: "side-others" }, h("h3", { text: "Autoplay from others" }), ul));
    }
    pane.replaceChildren(...parts);
    this.#markCurrent(location.pathname + location.search);
  }

  async #newPlaylist(): Promise<void> {
    const name = window.prompt("Name of the new playlist");
    if (name === null || name.trim() === "") return;
    try {
      const p = await api<Playlist>("POST", "/api/playlists", { name: name.trim() });
      await this.#loadLibrary();
      navigate(playlistPath(p.id));
    } catch (e) {
      window.alert(message(e));
    }
  }

  /** Marks the sidebar link of the view shown. */
  #markCurrent(path: string): void {
    const pathname = path.split("?")[0] ?? path;
    document.querySelectorAll<HTMLAnchorElement>("#app-sidebar a.side-link").forEach((a) => {
      const href = a.getAttribute("href") ?? "";
      const on = href === path || href === pathname;
      if (on) a.setAttribute("aria-current", "page");
      else a.removeAttribute("aria-current");
    });
  }

  async #loadRooms(): Promise<void> {
    const q = byId<HTMLInputElement>("app-room-search").value.trim();
    this.#roomsQuery = q;
    const status = byId("app-rooms-status");
    try {
      const params = new URLSearchParams({ limit: "100" });
      if (q !== "") params.set("q", q);
      const page = await api<Page<RoomListEntry & { listeners?: number }>>("GET", "/api/rooms?" + params.toString());
      if (q !== this.#roomsQuery) return; // overtaken by a newer search
      const list = byId("app-room-list");
      list.replaceChildren(...page.items.map((r) => h("li", {},
        h("a", { class: "side-link room-link", href: "/rooms/" + encodeURIComponent(r.slug) },
          h("span", { class: "grow", text: r.name }),
          h("span", { class: "muted small num", title: "In the room now", text: String(r.listeners ?? 0) })))));
      status.textContent = page.items.length === 0 ? (q === "" ? "No rooms yet." : "No room matches.") : "";
    } catch (e) {
      status.textContent = message(e);
    }
  }

  // ---------------------------------------------------------------- room parts

  /** Draws the parts that follow the room (the shell calls it on every room state and navigation). */
  update(): void {
    const room = this.#host.room();
    const s = room?.state();
    const user = this.#me?.kind === "user";
    byId("app-panel").hidden = room === undefined;
    byId("player-bar").hidden = room === undefined && !user;
    document.body.classList.toggle("has-panel", room !== undefined);
    document.body.classList.toggle("has-player-bar", room !== undefined || user);
    const id = room?.id() ?? "";
    if (id !== this.#roomId) {
      this.#roomId = id;
      void this.#loadSelections(false).then(() => this.#drawLibrary());
    }
    if (room !== undefined) {
      const gain = volumeGain(this.#volume);
      const muted = this.#mute.current(s?.current?.entryId);
      room.setVolume(gain, muted);
    }
    this.#drawPanel(room, s);
    this.#drawPlayerBar(room, s);
    this.#drawVideo();
  }

  #showError(e: unknown): void {
    byId("app-panel-error").textContent = e === "" ? "" : message(e);
  }

  #act(room: ShellRoom, msg: Record<string, unknown>): void {
    this.#showError("");
    room.request(msg).catch((e: unknown) => this.#showError(e));
  }

  #actButton(room: ShellRoom, text: string, title: string, msg: Record<string, unknown>, className = "icon"):
      HTMLButtonElement {
    const b = h("button", { type: "button", class: className, text, title, "aria-label": title });
    b.addEventListener("click", () => this.#act(room, msg));
    return b;
  }

  #heart(itemId: string): HTMLButtonElement | null {
    if (!canHeart(this.#me?.kind ?? "anonymous")) return null;
    const liked = this.#likes[itemId];
    const b = h("button", { type: "button", class: "icon heart" + (liked === true ? " on" : ""),
      text: liked === true ? "♥" : "♡", title: liked === true ? "Remove from Liked Songs" : "Add to Liked Songs",
      "aria-pressed": liked === true ? "true" : "false", "data-heart": itemId });
    b.addEventListener("click", () => { void this.#toggleLike(itemId); });
    if (liked === undefined) void this.#loadLike(itemId);
    return b;
  }

  async #loadLike(itemId: string): Promise<void> {
    if (this.#likes[itemId] !== undefined) return;
    this.#likes[itemId] = false; // asked once
    try {
      const r = await api<{ liked: boolean }>("GET", "/api/me/likes/" + encodeURIComponent(itemId));
      this.#likes[itemId] = r.liked;
      if (r.liked) this.#redrawHearts(itemId);
    } catch {
      // unknown: shown as not liked
    }
  }

  async #toggleLike(itemId: string): Promise<void> {
    const liked = this.#likes[itemId] === true;
    const path = "/api/me/likes/" + encodeURIComponent(itemId);
    try {
      if (liked) {
        await api("DELETE", path);
        this.#likes[itemId] = false;
      } else {
        const r = await withClassification((c) => api("PUT", path, c));
        if (r === null) return;
        this.#likes[itemId] = true;
      }
      this.#redrawHearts(itemId);
      void this.#loadLibrary();
    } catch (e) {
      this.#showError(e);
    }
  }

  #redrawHearts(itemId: string): void {
    const liked = this.#likes[itemId] === true;
    document.querySelectorAll<HTMLButtonElement>("[data-heart]").forEach((b) => {
      if (b.dataset["heart"] !== itemId) return;
      b.textContent = liked ? "♥" : "♡";
      b.classList.toggle("on", liked);
      b.setAttribute("aria-pressed", liked ? "true" : "false");
      b.title = liked ? "Remove from Liked Songs" : "Add to Liked Songs";
    });
    const pb = byId<HTMLButtonElement>("pb-heart");
    if (pb.dataset["item"] === itemId) {
      pb.textContent = liked ? "♥" : "♡";
      pb.classList.toggle("on", liked);
      pb.setAttribute("aria-pressed", liked ? "true" : "false");
    }
  }

  /** The third line under title and artist: what ingest is doing, or Failed (+ Retry). */
  #statusLine(room: ShellRoom, e: CurrentEntry | WaitingEntry, retry: boolean): HTMLElement | null {
    if (e.status === "ready") return null;
    if (e.status === "failed") {
      const line = h("div", { class: "entry-status small" }, h("span", { class: "failed", text: "Failed" }));
      if (retry) line.append(" ", this.#actButton(room, "Retry", "Fetch it again",
        { type: "queue.retry", entryId: e.entryId }, "link small"));
      return line;
    }
    if (e.status === "purged") return h("div", { class: "entry-status small muted", text: "Media deleted" });
    const known = room.ingest()[e.item.id];
    return h("div", { class: "entry-status small muted", text: ingestText(known?.state === "failed" ? undefined : known) });
  }

  #entryText(item: RoomItem): HTMLElement {
    return h("div", { class: "entry-text" },
      h("a", { class: "entry-title", href: itemPath(item.id), text: item.title ?? "(unknown item)" }),
      item.artist !== undefined && item.artist !== "" ? h("div", { class: "muted small", text: item.artist }) : null);
  }

  #waitingRow(room: ShellRoom, s: RoomState, e: WaitingEntry, suggestion: boolean): HTMLLIElement {
    const actor = room.actor();
    const b = entryButtons(s, e, actor);
    const actions = h("span", { class: "entry-actions" });
    if (suggestion) {
      if (s.room.you.canModerate) {
        actions.append(this.#actButton(room, "Accept", "Accept", { type: "suggest.accept", entryId: e.entryId }, "small"),
          this.#actButton(room, "Reject", "Reject", { type: "suggest.reject", entryId: e.entryId }, "small"));
      } else if (b.remove) {
        actions.append(this.#actButton(room, "×", "Withdraw", { type: "queue.remove", entryId: e.entryId }));
      }
    } else {
      if (b.vote) {
        const voted = e.yourVote === 1;
        const up = h("button", { type: "button", class: "icon vote" + (voted ? " voted" : ""),
          title: voted ? "Take back your vote" : "Vote up", "aria-pressed": voted ? "true" : "false" },
          h("span", { class: "num", text: String(e.votes) }), " ↑");
        up.disabled = !b.canVote;
        up.addEventListener("click", () => this.#act(room, { type: "vote", entryId: e.entryId, value: voted ? 0 : 1 }));
        actions.append(up);
      }
      if (b.request) {
        actions.append(this.#actButton(room, "↑", "Request it (moves it to Requests)",
          { type: "queue.request", entryId: e.entryId }));
      }
      if (b.remove) actions.append(this.#actButton(room, "×", "Remove", { type: "queue.remove", entryId: e.entryId }));
    }
    return h("li", { class: "entry", "data-entry": e.entryId },
      cover(e.item, "cover entry-cover"),
      h("div", { class: "grow" }, this.#entryText(e.item), this.#statusLine(room, e, b.retry)),
      actions);
  }

  #nowPlayingBlock(room: ShellRoom, s: RoomState): HTMLElement[] {
    const c = s.current;
    const out: HTMLElement[] = [h("h3", { text: "Now Playing" })];
    if (c === undefined) {
      out.push(h("p", { class: "muted small", text: "Nothing is playing." }));
      return out;
    }
    const actions = h("span", { class: "entry-actions" });
    const heart = this.#heart(c.item.id);
    if (heart !== null) actions.append(heart);
    if (canSkipNow(s.room.you)) actions.append(this.#actButton(room, "×", "Skip", { type: "next" }));
    out.push(h("ul", { class: "entries" }, h("li", { class: "entry now-entry" },
      cover(c.item, "cover entry-cover"),
      h("div", { class: "grow" }, this.#entryText(c.item), this.#statusLine(room, c, false)),
      actions)));
    const vs = voteSkip(s, s.current !== undefined);
    if (vs.show) {
      const b = this.#actButton(room, vs.label, "Vote to skip this song", { type: "voteSkip" }, "small");
      b.disabled = !vs.enabled;
      out.push(h("div", { class: "vote-skip" }, b));
    }
    return out;
  }

  #suggestionsBlock(room: ShellRoom, s: RoomState): HTMLElement {
    const ul = h("ul", { class: "entries" });
    for (const e of s.suggestions) ul.append(this.#waitingRow(room, s, e, true));
    return h("section", { class: "panel-suggestions" }, h("h3", { text: "Suggestions (" + s.suggestions.length + ")" }), ul);
  }

  #drawPanel(room: ShellRoom | undefined, s: RoomState | undefined): void {
    const head = byId("app-panel-room");
    if (room === undefined) {
      head.replaceChildren();
      return;
    }
    const roomLink = h("a", { href: room.path(), text: room.name(), title: "Open the room view" });
    const leave = h("button", { type: "button", class: "link small", text: "Leave", title: "Stop and leave the room" });
    leave.addEventListener("click", () => this.#host.leave());
    head.replaceChildren(roomLink, " ", leave);
    const queue = byId("app-queue");
    const recents = byId("app-recents");
    const people = byId("app-room");
    if (s === undefined) {
      const wait = (): HTMLElement => h("p", { class: "muted small", text: room.stopped() ?? "Connecting…" });
      queue.replaceChildren(wait());
      recents.replaceChildren(wait());
      people.replaceChildren(wait());
      return;
    }
    // Queue: suggestions above (people who may edit it) or below, Now Playing, Requests, From Playlist.
    const place = suggestionsPlace(s);
    const parts: HTMLElement[] = [];
    if (place === "above") parts.push(this.#suggestionsBlock(room, s));
    parts.push(...this.#nowPlayingBlock(room, s));
    const { requests, fromPlaylist } = queueSections(s.queue);
    const reqList = h("ul", { class: "entries" });
    for (const e of requests) reqList.append(this.#waitingRow(room, s, e, false));
    parts.push(h("h3", { text: "Requests" }),
      requests.length > 0 ? reqList : h("p", { class: "muted small", text: "No requests." }));
    const apList = h("ul", { class: "entries" });
    for (const e of fromPlaylist) apList.append(this.#waitingRow(room, s, e, false));
    parts.push(h("h3", { text: "From Playlist" }),
      fromPlaylist.length > 0 ? apList : h("p", { class: "muted small", text: "Nothing from playlists." }));
    if (place === "below") parts.push(this.#suggestionsBlock(room, s));
    queue.replaceChildren(...parts);

    // Recents: Now Playing, then Earlier (the room's history), each with a heart.
    const earlier = h("ul", { class: "entries" });
    for (const r of s.history) earlier.append(this.#historyRow(r));
    const nowList = h("ul", { class: "entries" });
    if (s.current !== undefined) {
      nowList.append(h("li", { class: "entry" }, cover(s.current.item, "cover entry-cover"),
        h("div", { class: "grow" }, this.#entryText(s.current.item)),
        h("span", { class: "entry-actions" }, this.#heart(s.current.item.id))));
    }
    recents.replaceChildren(h("h3", { text: "Now Playing" }),
      s.current !== undefined ? nowList : h("p", { class: "muted small", text: "Nothing is playing." }),
      h("h3", { text: "Earlier" }),
      s.history.length > 0 ? earlier : h("p", { class: "muted small", text: "Nothing played yet." }));

    // Room: Manage Room (owner and moderators), who is here.
    const roomParts: HTMLElement[] = [];
    if (s.room.you.canModerate) {
      roomParts.push(h("p", {}, h("a", { class: "button-link", href: room.path() + "/manage", text: "Manage Room" })));
    }
    roomParts.push(h("p", { class: "muted small", text: presenceText(s.presence) }));
    const ul = h("ul", { class: "people" });
    for (const p of s.presence.people ?? []) {
      ul.append(h("li", { class: "person", "data-actor": p.actor.kind + ":" + p.actor.id },
        h("span", { class: "person-icon", "aria-hidden": "true", text: "👤" }),
        h("div", { class: "grow" }, actorLabel(p.actor), h("div", { class: "muted small", text: personModeText(p) }))));
    }
    roomParts.push(ul);
    people.replaceChildren(...roomParts);
  }

  #historyRow(r: HistoryEntry): HTMLLIElement {
    return h("li", { class: "entry" + (r.state === "skipped" ? " skipped" : "") },
      cover(r.item, "cover entry-cover"),
      h("div", { class: "grow" }, this.#entryText(r.item)),
      h("span", { class: "entry-actions" }, this.#heart(r.item.id)));
  }

  // ---------------------------------------------------------------- player bar

  #wirePlayerBar(): void {
    const send = (msg: Record<string, unknown>): void => {
      const room = this.#host.room();
      if (room !== undefined) this.#act(room, msg);
    };
    byId("pb-prev").addEventListener("click", () => send({ type: "previous" }));
    byId("pb-next").addEventListener("click", () => send({ type: "next" }));
    byId("pb-play").addEventListener("click", () => {
      const s = this.#host.room()?.state();
      send({ type: s?.timeline.state === "playing" ? "pause" : "play" });
    });
    const seek = byId<HTMLInputElement>("pb-seek");
    seek.addEventListener("input", () => { this.#seeking = true; });
    seek.addEventListener("change", () => {
      this.#seeking = false;
      send({ type: "seek", positionMs: Math.round(Number(seek.value)) });
    });
    byId("pb-heart").addEventListener("click", () => {
      const id = byId("pb-heart").dataset["item"];
      if (id !== undefined && id !== "") void this.#toggleLike(id);
    });
    const volume = byId<HTMLInputElement>("pb-volume");
    volume.value = String(this.#volume);
    volume.addEventListener("input", () => {
      this.#volume = parseVolume(volume.value);
      storageSet(VOLUME_KEY, String(this.#volume));
      if (this.#volume > 0 && this.#mute.muted) this.#mute.unmute();
      this.update();
    });
    const muteMenu = byId("pb-mute-menu");
    for (const id of ["pb-mute", "pb-mute-big"]) {
      const b = byId(id);
      b.addEventListener("click", () => {
        this.#mute.toggle();
        this.update();
      });
      // Right-click: Mute player / Mute only until end of song.
      b.addEventListener("contextmenu", (ev) => {
        ev.preventDefault();
        muteMenu.hidden = false;
      });
    }
    byId("pb-mute-player").addEventListener("click", () => {
      this.#mute.mute();
      muteMenu.hidden = true;
      this.update();
    });
    byId("pb-mute-song").addEventListener("click", () => {
      this.#mute.muteUntilEndOf(this.#host.room()?.state()?.current?.entryId);
      muteMenu.hidden = true;
      this.update();
    });
    const menuButton = byId("pb-menu-button");
    const menu = byId("pb-menu");
    menuButton.addEventListener("click", () => {
      menu.hidden = !menu.hidden;
      menuButton.setAttribute("aria-expanded", menu.hidden ? "false" : "true");
    });
    byId<HTMLInputElement>("pb-audio-only").addEventListener("change", (ev) => {
      this.#host.room()?.setAudioOnly((ev.target as HTMLInputElement).checked);
      this.update();
    });
    byId<HTMLInputElement>("pb-requests-only").addEventListener("change", (ev) => {
      this.#host.room()?.setRequestsOnly((ev.target as HTMLInputElement).checked);
      this.update();
    });
    byId("pb-now-view").addEventListener("click", () => {
      menu.hidden = true;
      this.#setNowView(!document.body.classList.contains("now-view-open"));
    });
    byId("app-now-close").addEventListener("click", () => this.#setNowView(false));
    byId("pb-fullscreen").addEventListener("click", () => {
      menu.hidden = true;
      this.#toggleFullscreen();
    });
  }

  /** Closes the open menus on a click elsewhere. */
  #closeMenus(ev: MouseEvent): void {
    const t = ev.target;
    if (!(t instanceof Element)) return;
    const menu = byId("pb-menu");
    if (!menu.hidden && t.closest("#pb-menu, #pb-menu-button") === null) {
      menu.hidden = true;
      byId("pb-menu-button").setAttribute("aria-expanded", "false");
    }
    const mute = byId("pb-mute-menu");
    if (!mute.hidden && t.closest("#pb-mute-menu") === null) mute.hidden = true;
    const account = document.querySelector<HTMLDetailsElement>(".account-menu");
    if (account !== null && account.open && t.closest(".account-menu") === null) account.open = false;
  }

  #drawPlayerBar(room: ShellRoom | undefined, s: RoomState | undefined): void {
    const c = s?.current;
    const title = byId<HTMLAnchorElement>("pb-title");
    const artist = byId("pb-artist");
    const coverEl = byId("pb-cover");
    if (c === undefined) {
      title.textContent = room === undefined ? "Not in a room" : room.stopped() ?? "Nothing playing";
      title.href = room === undefined ? "/rooms/" : room.path();
      artist.textContent = room === undefined ? "Join a room from the Rooms tab" : room.name();
      coverEl.replaceWith(h("div", { id: "pb-cover", class: "cover pb-cover cover-none", "aria-hidden": "true" }));
    } else {
      title.textContent = c.item.title ?? "(unknown item)";
      title.href = itemPath(c.item.id);
      artist.textContent = c.item.artist ?? "";
      if (coverEl.getAttribute("src") !== (c.item.coverUrl ?? null) || c.item.coverUrl === undefined) {
        const next = cover(c.item, "cover pb-cover");
        next.id = "pb-cover";
        coverEl.replaceWith(next);
      }
    }
    const you = s?.room.you;
    const kind = playerControls(you);
    const controls = kind === "controls";
    for (const id of ["pb-prev", "pb-play", "pb-next"]) byId(id).hidden = !controls;
    byId("pb-mute-big").hidden = controls || room === undefined;
    const play = byId("pb-play");
    const playing = s?.timeline.state === "playing";
    play.textContent = playing ? "⏸" : "▶";
    play.title = playing ? "Pause" : "Play";
    play.setAttribute("aria-label", play.title);
    const seek = byId<HTMLInputElement>("pb-seek");
    seek.disabled = !controls || c === undefined;
    const heart = byId<HTMLButtonElement>("pb-heart");
    const heartOk = c !== undefined && canHeart(this.#me?.kind ?? "anonymous");
    heart.hidden = !heartOk;
    if (heartOk && c !== undefined) {
      heart.dataset["item"] = c.item.id;
      const liked = this.#likes[c.item.id];
      if (liked === undefined) void this.#loadLike(c.item.id);
      heart.textContent = liked === true ? "♥" : "♡";
      heart.classList.toggle("on", liked === true);
      heart.setAttribute("aria-pressed", liked === true ? "true" : "false");
    }
    // Mute buttons show the state; "until end of song" is said in the title.
    const muted = this.#mute.muted;
    for (const id of ["pb-mute", "pb-mute-big"]) {
      const b = byId(id);
      b.textContent = muted ? "🔇" : "🔊";
      b.classList.toggle("on", muted);
      b.title = muted ? (this.#mute.untilEndOfSong ? "Muted until the end of this song (click to unmute)" : "Unmute")
        : "Mute (right-click for more)";
      b.setAttribute("aria-label", muted ? "Unmute" : "Mute");
    }
    byId<HTMLInputElement>("pb-volume").value = String(this.#volume);
    // The menu: Audio Only (not in an audio-only room, not in requests-only mode), Requests-only mode.
    const audioOnly = byId<HTMLInputElement>("pb-audio-only");
    const requestsOnly = byId<HTMLInputElement>("pb-requests-only");
    const remote = room?.mode() === "remote";
    audioOnly.checked = room?.audioOnly() ?? false;
    audioOnly.disabled = room === undefined || remote;
    (audioOnly.parentElement as HTMLElement).hidden = room?.audioOnlyRoom?.() === true;
    requestsOnly.checked = remote;
    requestsOnly.disabled = room === undefined || room.forcedRequestsOnly();
    (requestsOnly.parentElement as HTMLElement).title = room?.forcedRequestsOnly() === true
      ? "Set by the room: nothing plays on this page" : "Play nothing here; just request and vote";
    this.#tick();
  }

  /** Moves the seek bar and the times along. */
  #tick(): void {
    const room = this.#host.room();
    const s = room?.state();
    const c = s?.current;
    const pos = byId("pb-pos");
    const dur = byId("pb-dur");
    const seek = byId<HTMLInputElement>("pb-seek");
    if (room === undefined || s === undefined || c === undefined) {
      pos.textContent = "0:00";
      dur.textContent = "0:00";
      seek.max = "1";
      seek.value = "0";
      return;
    }
    const duration = c.item.durationMs ?? 0;
    const t = s.timeline;
    let p = t.state === "playing" ? Math.max(0, t.anchorPositionMs + (room.serverNow() - t.anchorServerMs))
      : t.anchorPositionMs;
    if (duration > 0) p = Math.min(p, duration);
    pos.textContent = formatDuration(p);
    dur.textContent = formatDuration(duration);
    seek.max = String(Math.max(duration, 1));
    if (!this.#seeking) seek.value = String(p);
    // "Mute only until end of song" ends when the song does.
    const wasMuted = this.#mute.muted;
    if (this.#mute.current(c.entryId) !== wasMuted) this.update();
  }

  // ---------------------------------------------------------------- video panel, Now Playing View

  #drawVideo(): void {
    const room = this.#host.room();
    const container = byId("room-player");
    const panel = byId("app-video");
    const card = byId("app-video-card");
    const nowView = document.body.classList.contains("now-view-open");
    const video = room !== undefined && room.mode() === "listen" && !container.hidden;
    // Collapsed for audio items (and Audio Only); the Now Playing View shows a card instead.
    panel.hidden = room === undefined || (!video && !nowView);
    card.hidden = video || !nowView;
    if (!card.hidden) {
      const c = room?.state()?.current;
      card.replaceChildren(cover(c?.item, "cover card-cover"),
        h("div", { class: "card-title", text: c?.item.title ?? (room?.name() ?? "") }),
        h("div", { class: "card-artist", text: c !== undefined ? c.item.artist ?? "" : "Nothing is playing" }));
    }
  }

  #setNowView(on: boolean): void {
    if (on && this.#host.room() === undefined) return;
    document.body.classList.toggle("now-view-open", on);
    byId("app-now-close").hidden = !on;
    this.#drawVideo();
  }

  #toggleFullscreen(): void {
    const doc = document as Document & { webkitFullscreenElement?: Element | null; webkitExitFullscreen?: () => void };
    const el = document.documentElement as HTMLElement & { webkitRequestFullscreen?: () => void };
    try {
      if ((doc.fullscreenElement ?? doc.webkitFullscreenElement ?? null) !== null) {
        if (typeof doc.exitFullscreen === "function") void doc.exitFullscreen();
        else doc.webkitExitFullscreen?.();
      } else if (typeof el.requestFullscreen === "function") {
        void el.requestFullscreen();
      } else {
        el.webkitRequestFullscreen?.();
      }
    } catch {
      // Not allowed here: nothing happens.
    }
  }
}
