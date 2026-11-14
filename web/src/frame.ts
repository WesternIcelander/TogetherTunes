// The desktop layout around the views (docs/ui.md#layout-desktop): the header's search, the left
// sidebar (Library | Rooms), the right panel (Queue | Recents | Room) with the video panel, the
// player bar and the Now Playing View. The view in the middle (#app) is the shell's (shell.ts).
//
// The frame follows the room the room view has open (shown, or playing in the background while
// other views are shown): it reads that view's room state and sends actions through its socket
// (nav.ts `ShellRoom`); it has no socket of its own. Without a room the right panel is hidden and
// the player bar says so. The rules (who gets which button) are in the DOM-free panels.ts; the
// server enforces them all.
//
// Below 800 px it is the mobile layout (docs/ui.md#mobile-narrow-windows; rules in the DOM-free
// mobile.ts, layout in main.css): the header becomes a top bar with a back button and the page's title
// once its own large title scrolled away, bottom tabs (Library, Now Playing, Room, Search, Account)
// and the player bar as a mini now-playing bar above them. Library, Now Playing, Rooms and Account
// are "panes" the frame shows instead of the view (the sidebar's Library or Rooms tab, the right
// panel with the video on top, the account menu); each is a history entry of the view's URL with
// `mPane` in history.state, so the browser's back button closes it.
//
// Drag and drop (desktop; rules in the DOM-free dragdrop.ts, page side in dnd.ts): the user's
// playlists drag up and down in the sidebar (PUT /api/playlists/order), each playlist takes songs,
// files and links dropped on it; queue and Recents rows drag out (the selected ones together:
// Shift / Ctrl / Cmd-click) and the queue's two sections take moves (queue.move, by rights, never
// in vote mode) and songs from elsewhere (queue.add).
import { openAddLink } from "./addlink.js";
import { historyPlace, historyRoomPath, leaveKind, recentsFromHistory, type PlayHistoryRow } from "./alone.js";
import { api } from "./api.js";
import { actorLabel } from "./actor.js";
import { withClassification } from "./classifyprompt.js";
import { draggable, dropZone } from "./dnd.js";
import { draggedKeys, moveIsNoop, playlistDrop, playlistOrderBody, queueDrop, queueShowsPosition, type DragPayload,
  type QueueSection } from "./dragdrop.js";
import { dropFiles } from "./filedrop.js";
import { formatDuration, h } from "./dom.js";
import { ingestText } from "./jobtext.js";
import { itemPath, type Page, type Playlist } from "./media.js";
import { isPane, MOBILE_QUERY, MOBILE_TABS, mobileBack, mobileTab, PANE_TITLES, roomSubTab, Selection, showBarTitle,
  tabAction, type MobilePane, type MobilePlace, type MobileTab } from "./mobile.js";
import { navigate, reloadPage, setFrame, type ShellRoom } from "./nav.js";
import { buildReport, recordEvent, stripQuery } from "./diagnostics.js";
import { prefs } from "./prefsclient.js";
import { offersNextEpisode } from "./shows.js";
import { clickSelect, ENTRY_ACTION_LABELS, entryMenu, entryMessage, playlistClick, PLAYLIST_ACTION_LABELS, playlistMenu,
  playlistNameInput, toggleKey, type EntryAction } from "./interactions.js";
import {
  canHeart, canSkipNow, entryButtons, LIBRARY_LINKS, MuteState, mySelectedPlaylists, othersAutoplay, parseDurationInput,
  parseVolume, PERSON_ACTION_LABELS, personActions, personModeText, playerControls, presenceText, queueSections, songCount,
  suggestionsPlace, volumeGain, voteSkip, type AutoplaySelectionLike, type PersonAction, type PersonLike,
  type PersonViewer,
} from "./panels.js";
import type { CurrentEntry, HistoryEntry, RoomItem, RoomState, WaitingEntry } from "./roomclient.js";
import type { RoomListEntry } from "./roomlist.js";
import { playlistPath, routeOf } from "./router.js";
import { ACCOUNT_PAGE, ADMIN_PAGE, canInvite, ENCODES_PAGE, INVITES_PAGE, isAdmin, LIBRARY_PAGE, loadMe, logout, message,
  PLAYLISTS_PAGE, ROOMS_PAGE, type Me } from "./session.js";
import { lastPressWasTouch, onLongPress, openMenu } from "./touch.js";

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

/** The bottom tabs' icons (mobile). */
const TAB_ICONS: Readonly<Record<MobileTab, string>> = {
  library: "♫",
  now: "▶",
  room: "👥",
  search: "🔍",
  account: "👤",
};

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
  /** The right panel's multi-select (queue and Recents rows, desktop) and its anchor. */
  readonly #panelSel = new Selection();
  #panelAnchor: string | undefined;
  readonly #mute = new MuteState();
  #volume = parseVolume(storageGet(VOLUME_KEY));
  #seeking = false;
  /** The room id the panel was last drawn for (a new room resets the tab state). */
  #roomId = "";
  #roomsTimer: number | undefined;
  #roomsQuery = "";
  /** Recents alone: the user's own play history, and the current entry it was loaded for. */
  #playHistory: PlayHistoryRow[] | undefined;
  #playHistoryKey: string | undefined;
  /** The mobile layout's media query (below 800 px). */
  readonly #mobileQuery: MediaQueryList | undefined = typeof window.matchMedia === "function"
    ? window.matchMedia(MOBILE_QUERY) : undefined;
  /** The mobile pane shown instead of the view ("" = the view); always "" in the desktop layout. */
  #pane: MobilePane = "";

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
    // The video panel shows while the player shows a video (listen.ts hides its container otherwise),
    // and while the "Click to enable sound" layer over it is up.
    const container = byId("room-player");
    const observer = new MutationObserver(() => this.#drawVideo());
    observer.observe(container, { attributes: true, attributeFilter: ["hidden"] });
    observer.observe(byId("room-listen-overlay"), { attributes: true, attributeFilter: ["hidden"] });
    // The room view: ☰ shows the left sidebar (pushing the page aside); double-clicking Queue, Recents
    // or Room in the right panel opens the room view.
    const toggle = byId("app-sidebar-toggle");
    toggle.addEventListener("click", () => {
      const open = !document.body.classList.contains("sidebar-open");
      document.body.classList.toggle("sidebar-open", open);
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
    byId("app-panel").querySelectorAll<HTMLButtonElement>(".tabs [data-tab]").forEach((b) => {
      b.title = "Double-click to open the room view";
      b.addEventListener("dblclick", () => {
        const room = this.#host.room();
        if (room !== undefined) navigate(room.path());
      });
    });
    window.setInterval(() => this.#tick(), 250);
    // Debug mode (a user preference): "Send diagnostics" at the top of the page.
    prefs.subscribe((p, changed) => { if (changed.indexOf("debug") >= 0) byId("app-debug").hidden = !p.debug; });
    byId("app-diagnostics").addEventListener("click", () => { void this.#sendDiagnostics(); });
    document.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape" && document.body.classList.contains("now-view-open")) this.#setNowView(false);
    });
    document.addEventListener("click", (ev) => this.#closeMenus(ev));
    // A plain click (or Escape) ends the panel's multi-select.
    const clearSelection = (): void => {
      if (this.#panelSel.size === 0) return;
      this.#panelSel.clear();
      this.#panelAnchor = undefined;
      this.#drawPanelSelection();
    };
    document.addEventListener("click", (ev) => {
      if (!ev.shiftKey && !toggleKey(ev)) clearSelection();
    });
    document.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape" && document.querySelector(".popup-menu") === null) clearSelection();
    });
    this.#wireMobile();
  }

  // ---------------------------------------------------------------- mobile layout

  #mobile(): boolean {
    return this.#mobileQuery?.matches === true;
  }

  /** Where the user is, for the mobile rules. */
  #placeNow(): MobilePlace {
    const r = routeOf(location.pathname);
    return r.kind === "view" ? { pane: this.#pane, view: r.view, list: r.params["list"] }
      : { pane: this.#pane, view: "notFound" };
  }

  #wireMobile(): void {
    const tabs = byId("m-tabs");
    for (const t of MOBILE_TABS) {
      const b = h("button", { type: "button", "data-m-tab": t.tab },
        h("span", { class: "m-tab-icon", "aria-hidden": "true", text: TAB_ICONS[t.tab] }),
        h("span", { class: "m-tab-label", text: t.label }));
      b.addEventListener("click", () => this.#tapTab(t.tab));
      tabs.append(b);
    }
    byId("m-back").addEventListener("click", () => {
      const back = mobileBack(this.#placeNow());
      if (back === undefined) return;
      if (back.pane !== undefined) this.#openPane(back.pane);
      else history.back();
    });
    byId("m-tab-rooms").addEventListener("click", () => this.#openPane("rooms"));
    byId("m-tab-this-room").addEventListener("click", () => {
      const room = this.#host.room();
      if (room === undefined) return;
      navigate(room.path());
      // The room view may be the view under the pane already (the shell then shows nothing new).
      this.#paneFromHistory();
    });
    // The mini bar: tapping the song opens Now Playing (on the desktop the title links to the item).
    byId("player-bar").querySelector(".pb-now")?.addEventListener("click", (ev) => {
      if (!this.#mobile() || this.#me?.kind !== "user") return;
      ev.preventDefault();
      this.#openPane("now");
    });
    // A pane is a history entry of the view's URL with mPane in its state (the shell ignores a
    // popstate that keeps the view's URL; one that changes it calls navigated(), which reads the state too).
    window.addEventListener("popstate", () => this.#paneFromHistory());
    const onChange = (): void => {
      if (!this.#mobile()) this.#pane = "";
      this.update();
    };
    if (this.#mobileQuery !== undefined) {
      if (typeof this.#mobileQuery.addEventListener === "function") this.#mobileQuery.addEventListener("change", onChange);
      else this.#mobileQuery.addListener(onChange);
    }
    window.addEventListener("scroll", () => this.#drawBarTitle(), { passive: true });
    window.addEventListener("resize", () => this.#drawBarTitle(), { passive: true });
  }

  #paneFromHistory(): void {
    const state = (history.state ?? {}) as { mPane?: unknown };
    const pane = isPane(state.mPane) && this.#mobile() ? state.mPane : "";
    if (pane === this.#pane) return;
    this.#pane = pane;
    this.update();
  }

  /** Shows a pane instead of the view: a new history entry (same URL), so Back closes it. */
  #openPane(pane: Exclude<MobilePane, "">): void {
    if (this.#pane === pane) {
      window.scrollTo(0, 0);
      return;
    }
    const state = (history.state ?? {}) as Record<string, unknown>;
    try {
      history.replaceState({ ...state, scrollY: window.scrollY }, "");
      history.pushState({ mPane: pane }, "");
    } catch {
      // Not allowed (a file:// page): the pane shows, Back doesn't close it.
    }
    this.#pane = pane;
    if (pane === "rooms") void this.#loadRooms();
    this.update();
    window.scrollTo(0, 0);
  }

  #tapTab(tab: MobileTab): void {
    const room = this.#host.room();
    const a = tabAction(tab, this.#placeNow(), room?.path());
    if (a.pane !== undefined) this.#openPane(a.pane);
    else if (a.path !== undefined) {
      navigate(a.path);
      this.#paneFromHistory();
    }
    if (tab === "search") document.querySelector<HTMLInputElement>("#app-search input[name=q]")?.focus();
  }

  /** The mobile parts: tabs, top bar, panes. In the desktop layout they are all hidden (main.css). */
  #drawMobile(): void {
    const mobile = this.#mobile();
    const user = this.#me?.kind === "user";
    if (!mobile) this.#pane = "";
    const pane = this.#pane;
    const place = this.#placeNow();
    document.body.classList.toggle("mobile", mobile);
    document.body.classList.toggle("m-tabs-on", mobile && user);
    document.body.dataset["mPane"] = pane;
    document.body.dataset["mTab"] = mobile ? mobileTab(place) : "";
    byId("m-tabs").hidden = !user;
    const current = mobileTab(place);
    byId("m-tabs").querySelectorAll<HTMLButtonElement>("[data-m-tab]").forEach((b) => {
      const on = b.dataset["mTab"] === current;
      if (on) b.setAttribute("aria-current", "page");
      else b.removeAttribute("aria-current");
    });
    const back = user && mobile ? mobileBack(place) : undefined;
    byId("m-back").hidden = back === undefined;
    byId("m-back-label").textContent = back?.label ?? "";
    const paneTitle = byId("m-pane-title");
    // Now Playing starts with the video; its title is in the top bar from the start.
    paneTitle.hidden = pane === "" || pane === "now";
    paneTitle.textContent = pane === "" ? "" : PANE_TITLES[pane];
    // The Room tab's sub-tabs: Rooms | This Room.
    const sub = user ? roomSubTab(place) : undefined;
    byId("m-room-tabs").hidden = sub === undefined;
    byId("m-tab-rooms").setAttribute("aria-selected", sub === "rooms" ? "true" : "false");
    const thisRoom = byId<HTMLButtonElement>("m-tab-this-room");
    thisRoom.setAttribute("aria-selected", sub === "this" ? "true" : "false");
    thisRoom.disabled = this.#host.room() === undefined;
    byId("m-now-empty").hidden = pane !== "now" || this.#host.room() !== undefined;
    // The sidebar's tab follows the pane (Library or Rooms); Now Playing has no Room tab (This Room has the people).
    const sideTab = pane === "rooms" ? "rooms" : pane === "library" ? "library" : "";
    if (sideTab !== "") {
      const b = byId("app-tab-" + sideTab);
      if (b.getAttribute("aria-selected") !== "true") b.click();
    }
    if (pane === "now" && byId("app-tab-room").getAttribute("aria-selected") === "true") byId("app-tab-queue").click();
    const account = byId("m-account");
    account.hidden = pane !== "account";
    if (pane === "account" && account.childElementCount === 0) this.#drawAccount();
    this.#drawBarTitle();
  }

  #drawAccount(): void {
    const me = this.#me;
    if (me?.kind !== "user") return;
    const link = (href: string, text: string): HTMLAnchorElement => h("a", { class: "side-link", href, text });
    const out = h("button", { type: "button", class: "side-link", text: "Log out" });
    out.addEventListener("click", () => {
      out.disabled = true;
      logout().catch(() => { out.disabled = false; });
    });
    const parts: HTMLElement[] = [h("p", { class: "muted", text: "Signed in as " + me.name }),
      link(ACCOUNT_PAGE, "Account settings"), link(LIBRARY_PAGE, "Manage library"), link(PLAYLISTS_PAGE, "Playlists"),
      link(ROOMS_PAGE, "Rooms"), link(ENCODES_PAGE, "Encodes")];
    if (canInvite(me)) parts.push(link(INVITES_PAGE, "Invites"));
    if (isAdmin(me)) parts.push(link(ADMIN_PAGE, "Administration"));
    parts.push(out);
    byId("m-account").replaceChildren(...parts);
  }

  /** The top bar's title: the page's title once its own large title has scrolled under the bar. */
  #drawBarTitle(): void {
    const title = byId("m-title");
    if (!this.#mobile()) {
      title.textContent = "";
      return;
    }
    const pane = this.#pane;
    let large: HTMLElement | null;
    let text: string;
    if (pane !== "") {
      large = pane === "now" ? null : byId("m-pane-title");
      text = PANE_TITLES[pane];
    } else {
      large = document.querySelector<HTMLElement>("#app > .view:not([hidden]) h1");
      text = large?.textContent?.trim() ?? document.title.replace(/ · TogetherTunes$/, "");
    }
    const bar = document.querySelector(".app-header");
    const barBottom = bar !== null ? bar.getBoundingClientRect().bottom : 0;
    const show = showBarTitle(large !== null ? large.getBoundingClientRect().bottom : undefined, barBottom);
    title.textContent = text;
    title.classList.toggle("shown", show);
  }

  /**
   * A view's element is about to leave the document (or be hidden): the video panel goes back to the
   * right panel first if the room view had it (moved in the same task, so the media keep playing).
   */
  release(el: HTMLElement): void {
    const video = byId("app-video");
    if (el.contains(video)) byId("app-panel").append(video);
  }

  /** The room view is shown: the video panel lives in its video area; otherwise in the right panel. */
  #placeVideo(roomView: boolean): void {
    const video = byId("app-video");
    const slot = roomView ? document.getElementById("rv-video-slot") : null;
    const home = slot ?? byId("app-panel");
    if (video.parentElement !== home) home.append(video);
  }

  /** Loads who is here and draws the frame. */
  async start(): Promise<void> {
    await this.#reloadMe();
  }

  /** The shell showed another view (`path`): mark it in the sidebar; pick up a login or logout. */
  navigated(path: string): void {
    const state = (history.state ?? {}) as { mPane?: unknown };
    const pane = isPane(state.mPane) && this.#mobile() ? state.mPane : "";
    if (pane !== this.#pane) {
      this.#pane = pane;
      this.update();
    }
    this.#markCurrent(path);
    if (this.#me?.kind !== "user") void this.#reloadMe();
  }

  /** Who is using the page changed (a login, nav.ts `identityChanged`): load and draw it again. */
  identityChanged(): Promise<void> {
    return this.#reloadMe();
  }

  /**
   * Debug mode's "Send diagnostics": what the room playing here did (its report, diagnostics.ts
   * `setReportSource`), or without a room the page's own events and errors, to the server log.
   */
  async #sendDiagnostics(): Promise<void> {
    const status = byId("app-diagnostics-status");
    const ref = Math.random().toString(36).slice(2, 8);
    status.textContent = "Sending…";
    try {
      recordEvent("diagnostics", { ref });
      await api("POST", "/api/diagnostics", buildReport(ref, { userAgent: navigator.userAgent,
        page: stripQuery(location.pathname), epochMs: Date.now(), visibility: document.visibilityState }));
      status.textContent = "Sent (ref " + ref + ").";
    } catch (e) {
      status.textContent = "Not sent: " + message(e);
    }
  }

  async #reloadMe(): Promise<void> {
    const before = this.#me;
    try {
      this.#me = await loadMe();
    } catch {
      return;
    }
    const user = this.#me.kind === "user";
    // The user's preferences (debug mode, Web Audio, double-click): another user's are forgotten.
    const now = this.#me;
    if (now.kind !== "user" || before?.kind !== "user" || before.id !== now.id) prefs.reset();
    if (user) prefs.load().catch(() => undefined);
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
    const selected = mySelectedPlaylists(this.#selections, me.id);
    const likes = this.#playlists.find((p) => p.builtin === "likes");
    const links: HTMLElement[] = LIBRARY_LINKS.map((l) => l.path === "/liked" && likes !== undefined
      ? this.#playlistRow(likes, selected.has(likes.id), "/liked", "div")
      : h("a", { class: "side-link", href: l.path, text: l.label }));
    const newPlaylist = h("button", { type: "button", class: "side-link side-new", id: "app-new-playlist" },
      "+ New Playlist");
    newPlaylist.addEventListener("click", () => { void this.#newPlaylist(); });
    links.push(newPlaylist);
    if (this.#uncategorized > 0) {
      links.push(h("a", { class: "side-link", href: "/uncategorized" }, "Uncategorized Videos ",
        h("span", { class: "muted small num", text: String(this.#uncategorized) })));
    }
    const list = h("ul", { class: "side-list playlists-list" });
    for (const p of this.#playlists) {
      if (p.builtin === "likes") continue;
      list.append(this.#playlistRow(p, selected.has(p.id), playlistPath(p.id), "li"));
    }
    // Drag the playlists up and down to reorder them (the built-in entries above stay in place).
    dropZone(list, {
      accepts: (content, payload) => content === "internal" && payload?.kind === "playlist",
      rows: { selector: "li[data-playlist-row]", key: "data-playlist-row" },
      onDrop: (d) => {
        if (d.payload?.kind !== "playlist") return;
        const order = this.#playlists.filter((p) => p.builtin === undefined).map((p) => p.id);
        if (moveIsNoop(order, [d.payload.playlistId], d.before)) return;
        this.#libraryError("");
        api("PUT", "/api/playlists/order", playlistOrderBody(d.payload.playlistId, d.before))
          .then(() => this.#loadLibrary(), (e: unknown) => this.#libraryError(e));
      },
    });
    const parts: HTMLElement[] = [h("nav", { class: "side-links" }, ...links), list,
      h("p", { id: "app-library-error", class: "error small", role: "alert" })];
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

  /**
   * One of the user's playlists in the sidebar (docs/ui.md#autoplay-from-playlists): with a room open,
   * the dot in front toggles it for autoplay there, Ctrl-click (Cmd-click) toggles it, double-click
   * makes it the only one (the first click has opened it); a plain click opens it. Right-click (long
   * press on touch, ⋯ on narrow windows) opens its menu: the autoplay choices, Open, Rename, Delete.
   */
  #playlistRow(p: Playlist, dot: boolean, href: string, tag: "li" | "div"): HTMLElement {
    const roomOpen = this.#host.room() !== undefined;
    const name = p.builtin === "likes" ? "Liked Songs" : p.name;
    const a = h("a", { class: "side-link grow", href, "data-playlist": p.id,
      title: roomOpen ? (dot ? "Selected for autoplay in this room. " : "")
        + "Double-click: the only one for autoplay; Ctrl/Cmd-click: toggle for autoplay" : "" }, name);
    const dotEl = h(roomOpen ? "button" : "span", { class: "autoplay-dot" + (dot ? " on" : "") + (roomOpen ? " dot-button" : ""),
      text: dot ? "●" : roomOpen ? "○" : "" });
    if (roomOpen) {
      dotEl.setAttribute("type", "button");
      dotEl.setAttribute("aria-pressed", dot ? "true" : "false");
      dotEl.setAttribute("aria-label", (dot ? "Stop using " : "Use ") + name + " for autoplay");
      dotEl.title = dot ? "Stop using for autoplay" : "Use for autoplay";
      dotEl.addEventListener("click", (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        void this.#autoplay(p.id, dot ? "off" : "on");
      });
    } else {
      dotEl.setAttribute("aria-hidden", "true");
    }
    a.addEventListener("click", (ev) => {
      if (playlistClick(ev, this.#host.room() !== undefined) !== "toggle") return;
      ev.preventDefault();
      void this.#autoplay(p.id, dot ? "off" : "on");
    });
    a.addEventListener("dblclick", (ev) => {
      if (this.#host.room() === undefined) return;
      ev.preventDefault();
      window.getSelection()?.removeAllRanges();
      void this.#autoplay(p.id, "only");
    });
    const row = h(tag, { class: "playlist-row" }, dotEl, a);
    const more = h("button", { type: "button", class: "icon playlist-more m-only", text: "⋯",
      title: "More for " + name, "aria-label": "More for " + name, "aria-haspopup": "true" });
    const menu = (anchor: HTMLElement): void => this.#openPlaylistMenu(p, dot, anchor);
    more.addEventListener("click", () => menu(more));
    // Touch: a long press toggles the playlist for autoplay in the current room (docs/ui.md); the
    // right mouse button opens the menu (onLongPress gets both: tell them apart by the event).
    onLongPress(a, (ev) => {
      if ((ev.type !== "contextmenu" || lastPressWasTouch()) && this.#host.room() !== undefined) {
        void this.#autoplay(p.id, dot ? "off" : "on");
      }
      else menu(a);
    });
    row.append(more);
    if (tag === "li" && p.builtin === undefined) {
      row.setAttribute("data-playlist-row", p.id);
      draggable(row, () => ({ kind: "playlist", playlistId: p.id }));
    }
    this.#playlistDropTarget(row, p, href);
    return row;
  }

  /**
   * A playlist in the sidebar takes songs (added at its end: from other playlists, the queue,
   * Recents, search results), files (uploaded into it) and links (Add from link for it).
   */
  #playlistDropTarget(row: HTMLElement, p: Playlist, href: string): void {
    const name = p.builtin === "likes" ? "Liked Songs" : p.name;
    const changed = (): void => {
      if (location.pathname === href) reloadPage();
    };
    dropZone(row, {
      accepts: (content, payload) => content === "files" || content === "link"
        || (content === "internal" && playlistDrop({ playlistId: p.id, inView: false }, payload).kind !== "none"),
      onDrop: (d) => {
        this.#libraryError("");
        if (d.content === "files") {
          dropFiles(d.files, { playlistId: p.id, playlistName: name },
            (text, error) => this.#libraryNotice(text, error === true), changed);
          return;
        }
        if (d.content === "link") {
          openAddLink({ playlistId: p.id, url: d.link, onAdded: (text) => { this.#libraryNotice(text, false); changed(); } });
          return;
        }
        const what = playlistDrop({ playlistId: p.id, inView: false }, d.payload);
        if (what.kind !== "add") return;
        void this.#addToPlaylist(p.id, name, what.itemIds).then(changed);
      },
    });
  }

  /** Adds songs to the end of a playlist, in order (asking for types where needed). */
  async #addToPlaylist(playlistId: string, name: string, itemIds: readonly string[]): Promise<void> {
    const path = "/api/playlists/" + encodeURIComponent(playlistId) + "/entries";
    let added = 0;
    const failed: string[] = [];
    for (const itemId of itemIds) {
      try {
        const r = await withClassification((c) => api("POST", path, c === undefined ? { itemId } : { itemId, ...c }),
          undefined, true);
        if (r !== null) added++;
      } catch (e) {
        failed.push(message(e));
      }
    }
    if (failed.length > 0) this.#libraryNotice(failed.join("; "), true);
    else if (added > 0) this.#libraryNotice(songCount(added) + " added to " + name + ".", false);
    void this.#loadLibrary();
  }

  /** A line under the sidebar's playlists (an error, or what a drop did; the latter fades after a while). */
  #libraryNotice(text: string, error: boolean): void {
    const err = document.getElementById("app-library-error");
    if (err === null) return;
    err.textContent = text;
    err.classList.toggle("error", error);
    err.classList.toggle("muted", !error);
    if (!error) {
      window.setTimeout(() => {
        if (err.textContent === text) err.textContent = "";
      }, 5000);
    }
  }

  #openPlaylistMenu(p: Playlist, dot: boolean, anchor: HTMLElement): void {
    const builtin = p.builtin !== undefined;
    const name = builtin ? "Liked Songs" : p.name;
    const actions = playlistMenu({ roomOpen: this.#host.room() !== undefined, selected: dot, builtin });
    openMenu(anchor, actions.map((a) => ({ label: PLAYLIST_ACTION_LABELS[a], action: () => {
      switch (a) {
        case "use": void this.#autoplay(p.id, "on"); return;
        case "stopUsing": void this.#autoplay(p.id, "off"); return;
        case "only": void this.#autoplay(p.id, "only"); return;
        case "open": navigate(builtin ? "/liked" : playlistPath(p.id)); return;
        case "rename": void this.#renamePlaylist(p); return;
        case "delete": void this.#deletePlaylist(p); return;
      }
    } })), name);
  }

  #libraryError(e: unknown): void {
    const err = document.getElementById("app-library-error");
    if (err === null) return;
    err.textContent = e === "" ? "" : message(e);
    err.classList.add("error");
    err.classList.remove("muted");
  }

  async #renamePlaylist(p: Playlist): Promise<void> {
    const name = playlistNameInput(window.prompt("Rename the playlist", p.name), p.name);
    if (name === undefined) return;
    try {
      await api("PATCH", "/api/playlists/" + encodeURIComponent(p.id), { name });
      await this.#loadLibrary();
      if (location.pathname === playlistPath(p.id)) reloadPage();
    } catch (e) {
      this.#libraryError(e);
    }
  }

  async #deletePlaylist(p: Playlist): Promise<void> {
    if (!window.confirm("Delete the playlist \"" + p.name + "\"? The songs stay in your library.")) return;
    try {
      await api("DELETE", "/api/playlists/" + encodeURIComponent(p.id));
      await this.#loadLibrary();
      if (location.pathname === playlistPath(p.id)) navigate("/songs", true);
    } catch (e) {
      this.#libraryError(e);
    }
  }

  /** Autoplay selection of one of the user's playlists in the current room: on, off, or the only one. */
  async #autoplay(playlistId: string, how: "on" | "off" | "only"): Promise<void> {
    const room = this.#host.room();
    if (room === undefined) return;
    const base = "/api/rooms/" + encodeURIComponent(room.id()) + "/autoplay";
    try {
      const r = how === "only"
        ? await api<{ selections: AutoplaySelectionLike[] }>("PUT", base + "/mine", { playlistIds: [playlistId] })
        : await api<{ selections: AutoplaySelectionLike[] }>(how === "on" ? "PUT" : "DELETE",
          base + "/playlists/" + encodeURIComponent(playlistId));
      this.#selections = r.selections;
      this.#drawLibrary();
    } catch (e) {
      this.#libraryError(e);
    }
  }

  async #newPlaylist(): Promise<void> {
    const name = playlistNameInput(window.prompt("Name of the new playlist"));
    if (name === undefined) return;
    try {
      const p = await api<Playlist>("POST", "/api/playlists", { name });
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
    // The room view (docs/ui.md#room-view-desktop): no right panel, the sidebar behind ☰, the video big.
    this.#drawMobile();
    const roomView = this.#roomViewShown();
    document.body.classList.toggle("room-view", roomView);
    if (!roomView && document.body.classList.contains("sidebar-open")) {
      document.body.classList.remove("sidebar-open");
      byId("app-sidebar-toggle").setAttribute("aria-expanded", "false");
    }
    this.#placeVideo(roomView);
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
    if (this.#host.roomShown()) this.#drawRoomView(room, s);
    this.#drawPlayerBar(room, s);
    this.#drawVideo();
  }

  /** The room view is what's on screen (shown, and no mobile pane over it). */
  #roomViewShown(): boolean {
    return this.#host.roomShown() && this.#pane === "";
  }

  #showError(e: unknown): void {
    const text = e === "" ? "" : message(e);
    byId("app-panel-error").textContent = text;
    const rv = document.getElementById("rv-error");
    if (rv !== null) rv.textContent = text;
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
    const li = h("li", { class: "entry" + (e.pinned ? " pinned" : ""), "data-entry": e.entryId },
      cover(e.item, "cover entry-cover"),
      h("div", { class: "grow" }, this.#entryText(e.item),
        e.pinned && !suggestion ? h("div", { class: "muted small", text: "Pinned" }) : null,
        this.#statusLine(room, e, b.retry)),
      actions);
    this.#entryMenuButton(li, actions, e.item.title ?? "this entry",
      () => entryMenu(s, e, room.actor(), suggestion), (a) => this.#entryAction(room, a, e.entryId, e.item.id));
    // Dragging out never needs queue rights; moving needs them (the drop target checks).
    this.#draggableItem(li, e.item, "queue", suggestion ? undefined : e.entryId,
      suggestion ? undefined : "q:" + e.entryId);
    return li;
  }

  /**
   * Makes an entry row draggable (desktop): its song, with its queue entry id when it may move.
   * With a `key`, the row also takes part in the panel's multi-select (Shift / Ctrl / Cmd-click,
   * within the queue or within Recents), and a drag from a selected row carries all selected rows
   * of its list, in order.
   */
  #draggableItem(li: HTMLElement, item: RoomItem, source: "queue" | "recents", entryId?: string, key?: string): void {
    if (this.#mobile()) return;
    li.dataset["item"] = item.id;
    if (entryId !== undefined) li.dataset["moveEntry"] = entryId;
    if (key !== undefined) {
      li.dataset["sel"] = key;
      li.classList.toggle("selected", this.#panelSel.has(key));
      li.addEventListener("click", (ev) => {
        if (!ev.shiftKey && !toggleKey(ev)) return;
        ev.preventDefault();
        ev.stopPropagation();
        this.#panelAnchor = clickSelect(this.#panelSel, this.#selRows(li).map((r) => r.dataset["sel"] ?? ""), key, ev,
          this.#panelAnchor);
        this.#drawPanelSelection();
      });
    }
    draggable(li, (): DragPayload => {
      const rows = key === undefined ? [li] : (() => {
        const all = this.#selRows(li);
        const keys = draggedKeys((k) => this.#panelSel.has(k), all.map((r) => r.dataset["sel"] ?? ""), key);
        return all.filter((r) => keys.includes(r.dataset["sel"] ?? ""));
      })();
      const itemIds = rows.map((r) => r.dataset["item"] ?? "");
      const entryIds = rows.map((r) => r.dataset["moveEntry"]).filter((id): id is string => id !== undefined);
      return { kind: "items", source, itemIds, ...(entryIds.length === rows.length && entryId !== undefined ? { entryIds } : {}) };
    }, () => [location.origin + itemPath(item.id)]);
  }

  /** The selectable rows of the list `li` is in (the queue's or Recents', in the panel or the room view). */
  #selRows(li: HTMLElement): HTMLElement[] {
    const box = li.closest("#app-queue, #app-recents, #rv-queue, #rv-earlier");
    const prefix = (li.dataset["sel"] ?? "").split(":")[0] + ":";
    return box === null ? [li] : Array.from(box.querySelectorAll<HTMLElement>("[data-sel]"))
      .filter((r) => (r.dataset["sel"] ?? "").startsWith(prefix));
  }

  #drawPanelSelection(): void {
    document.querySelectorAll<HTMLElement>("li[data-sel]").forEach((r) => {
      r.classList.toggle("selected", this.#panelSel.has(r.dataset["sel"] ?? ""));
    });
  }

  /**
   * A queue section (Requests = manual, From Playlist = autoplay) as a drop target: queue entries
   * move there with the rights (not in vote mode), songs from elsewhere are requested.
   */
  #queueDropTarget(el: HTMLElement, room: ShellRoom, section: QueueSection): void {
    dropZone(el, {
      accepts: (content, payload) => {
        const s = room.state();
        if (content !== "internal" || s === undefined) return false;
        const what = queueDrop(s, payload, section, null).kind;
        return what === "move" || what === "add";
      },
      rows: { selector: "li[data-entry]", key: "data-entry" },
      positioned: (_content, payload) => {
        const s = room.state();
        return s !== undefined && queueShowsPosition(s, payload);
      },
      onDrop: (d) => {
        const s = room.state();
        if (s === undefined) return;
        const what = queueDrop(s, d.payload, section, d.before);
        if (what.kind === "move") {
          const ids = Array.from(el.querySelectorAll<HTMLElement>("li[data-entry]")).map((li) => li.dataset["entry"] ?? "");
          const moved = what.message["entryIds"] as string[];
          if (moveIsNoop(ids, moved, d.before)) return;
          this.#act(room, what.message);
        } else if (what.kind === "add") {
          void this.#requestAll(room, what.itemIds);
        }
      },
    });
  }

  /** Requests songs dropped on the queue, in order (the room's request rules apply). */
  async #requestAll(room: ShellRoom, itemIds: readonly string[]): Promise<void> {
    this.#showError("");
    const failed: string[] = [];
    for (const itemId of itemIds) {
      try {
        await room.request({ type: "queue.add", itemId });
      } catch (e) {
        failed.push(message(e));
      }
    }
    if (failed.length > 0) this.#showError(failed.join("; "));
  }

  /**
   * The right-click menu of a queue entry (docs/ui.md: also on a ⋯ button and long press): what its
   * buttons do, plus Pin to top / Unpin (vote mode, owner and moderators) and Open.
   */
  #entryMenuButton(li: HTMLElement, actions: HTMLElement, title: string, menu: () => EntryAction[],
      run: (a: EntryAction) => void): void {
    const more = h("button", { type: "button", class: "icon entry-more", text: "⋯", title: "More",
      "aria-label": "More for " + title, "aria-haspopup": "true" });
    const open = (anchor: HTMLElement): void => {
      openMenu(anchor, menu().map((a) => ({ label: ENTRY_ACTION_LABELS[a], action: () => run(a) })), title);
    };
    more.addEventListener("click", (ev) => {
      ev.stopPropagation();
      open(more);
    });
    actions.append(more);
    onLongPress(li, () => open(more));
  }

  #entryAction(room: ShellRoom, a: EntryAction, entryId: string, itemId: string): void {
    const msg = entryMessage(a, entryId);
    if (msg === undefined) navigate(itemPath(itemId));
    else this.#act(room, msg);
  }

  /** Now Playing (heart, ×); vote-to-skip under it unless `voteSkipHere` is false (the room view has it under the video). */
  #nowPlayingBlock(room: ShellRoom, s: RoomState, voteSkipHere = true): HTMLElement[] {
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
    const li = h("li", { class: "entry now-entry" },
      cover(c.item, "cover entry-cover"),
      h("div", { class: "grow" }, this.#entryText(c.item), this.#statusLine(room, c, false)),
      actions);
    this.#entryMenuButton(li, actions, c.item.title ?? "Now Playing",
      () => (canSkipNow(s.room.you) ? ["skip", "open"] : ["open"]) as EntryAction[],
      (a) => this.#entryAction(room, a, c.entryId, c.item.id));
    this.#draggableItem(li, c.item, "queue");
    out.push(h("ul", { class: "entries" }, li));
    const b = voteSkipHere ? this.#voteSkipButton(room, s) : null;
    const next = voteSkipHere ? this.#nextEpisodeButton(room, s) : null;
    if (b !== null || next !== null) out.push(h("div", { class: "vote-skip" }, b, next));
    return out;
  }

  /** Next episode (owner and moderators, while a show episode plays or nothing does; shows.ts). */
  #nextEpisodeButton(room: ShellRoom, s: RoomState): HTMLButtonElement | null {
    if (s.room.alone === true || !offersNextEpisode(s.room.you.canModerate, s.current?.item)) return null;
    return this.#actButton(room, "Next episode", "Queue the next episode of this show to play next",
      { type: "queue.nextEpisode" }, "small");
  }

  #voteSkipButton(room: ShellRoom, s: RoomState): HTMLButtonElement | null {
    const vs = voteSkip(s, s.current !== undefined);
    if (!vs.show) return null;
    const b = this.#actButton(room, vs.label, "Vote to skip this song", { type: "voteSkip" }, "small");
    b.disabled = !vs.enabled;
    return b;
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
    const alone = s?.room.alone === true;
    const roomLink = h("a", { href: room.path(), text: alone ? "On your own" : room.name(), title: "Open the room view" });
    if (leaveKind(this.#me, alone) === "none") {
      head.replaceChildren(roomLink);
    } else {
      const leave = h("button", { type: "button", class: "link small", text: "Leave",
        title: this.#me?.kind === "user" ? "Leave the room and play on your own" : "Stop and leave the room" });
      leave.addEventListener("click", () => this.#host.leave());
      head.replaceChildren(roomLink, " ", leave);
    }
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
    queue.replaceChildren(...this.#queueParts(room, s, true));
    recents.replaceChildren(...(recentsFromHistory(s) ? this.#aloneRecentsParts(s) : this.#recentsParts(s)));
    people.replaceChildren(...this.#peopleParts(room, s));
  }

  /** Queue: suggestions above (people who may edit it) or below, Now Playing, Requests, From Playlist. */
  #queueParts(room: ShellRoom, s: RoomState, voteSkipHere: boolean): HTMLElement[] {
    const place = suggestionsPlace(s);
    const parts: HTMLElement[] = [];
    if (place === "above") parts.push(this.#suggestionsBlock(room, s));
    parts.push(...this.#nowPlayingBlock(room, s, voteSkipHere));
    const { requests, fromPlaylist } = queueSections(s.queue);
    const reqList = h("ul", { class: "entries" });
    for (const e of requests) reqList.append(this.#waitingRow(room, s, e, false));
    const reqSection = h("section", { class: "queue-section", "data-section": "manual" }, h("h3", { text: "Requests" }),
      requests.length > 0 ? reqList : h("p", { class: "muted small", text: "No requests." }));
    const apList = h("ul", { class: "entries" });
    for (const e of fromPlaylist) apList.append(this.#waitingRow(room, s, e, false));
    const apSection = h("section", { class: "queue-section", "data-section": "autoplay" }, h("h3", { text: "From Playlist" }),
      fromPlaylist.length > 0 ? apList : h("p", { class: "muted small", text: "Nothing from playlists." }));
    if (!this.#mobile()) {
      this.#queueDropTarget(reqSection, room, "manual");
      this.#queueDropTarget(apSection, room, "autoplay");
    }
    parts.push(reqSection, apSection);
    if (place === "below") parts.push(this.#suggestionsBlock(room, s));
    return parts;
  }

  /** Earlier: the room's history, each with a heart. */
  #earlierParts(s: RoomState): HTMLElement[] {
    const earlier = h("ul", { class: "entries" });
    for (const r of s.history) earlier.append(this.#historyRow(r));
    return [h("h3", { text: "Earlier" }),
      s.history.length > 0 ? earlier : h("p", { class: "muted small", text: "Nothing played yet." })];
  }

  /** Recents: Now Playing, then Earlier. */
  #recentsParts(s: RoomState): HTMLElement[] {
    const nowList = h("ul", { class: "entries" });
    if (s.current !== undefined) {
      const li = h("li", { class: "entry" }, cover(s.current.item, "cover entry-cover"),
        h("div", { class: "grow" }, this.#entryText(s.current.item)),
        h("span", { class: "entry-actions" }, this.#heart(s.current.item.id)));
      this.#draggableItem(li, s.current.item, "recents", undefined, "r:" + s.current.entryId);
      nowList.append(li);
    }
    return [h("h3", { text: "Now Playing" }),
      s.current !== undefined ? nowList : h("p", { class: "muted small", text: "Nothing is playing." }),
      ...this.#earlierParts(s)];
  }

  /**
   * Recents alone (docs/ui.md#alone-and-in-rooms): Now Playing, then the user's own play history
   * (`GET /api/me/history`), each song that played in a room saying which; loaded again whenever
   * another song starts.
   */
  #aloneRecentsParts(s: RoomState): HTMLElement[] {
    const key = s.current?.entryId ?? "";
    if (key !== this.#playHistoryKey) {
      this.#playHistoryKey = key;
      void api<Page<PlayHistoryRow>>("GET", "/api/me/history?limit=50").then((page) => {
        if (this.#playHistoryKey !== key) return;
        this.#playHistory = page.items;
        this.update();
      }, () => undefined);
    }
    const parts = this.#recentsParts(s).slice(0, 2);
    const rows = this.#playHistory;
    const earlier = h("ul", { class: "entries" });
    for (const r of rows ?? []) {
      // The song playing now is the newest row: Now Playing shows it already.
      if (r === rows?.[0] && s.current !== undefined && r.room === undefined && r.item.id === s.current.item.id) continue;
      const place = historyPlace(r);
      const path = historyRoomPath(r);
      const item = r.item as RoomItem;
      const li = h("li", { class: "entry" }, cover(item, "cover entry-cover"),
        h("div", { class: "grow" }, this.#entryText(item),
          place === "" ? null : path !== undefined
            ? h("a", { class: "muted small history-room", href: path, text: place })
            : h("div", { class: "muted small history-room", text: place })),
        h("span", { class: "entry-actions" }, this.#heart(item.id)));
      this.#draggableItem(li, item, "recents", undefined, "h:" + r.id);
      earlier.append(li);
    }
    parts.push(h("h3", { text: "Earlier" }), rows === undefined ? h("p", { class: "muted small", text: "Loading…" })
      : earlier.childElementCount > 0 ? earlier : h("p", { class: "muted small", text: "Nothing played yet." }));
    return parts;
  }

  /** Room: Manage Room (owner and moderators) above the people, each with a ⋯ moderation menu. */
  #peopleParts(room: ShellRoom, s: RoomState): HTMLElement[] {
    const roomParts: HTMLElement[] = [];
    if (s.room.you.canModerate && s.room.alone !== true) {
      roomParts.push(h("p", {}, h("a", { class: "button-link", href: room.path() + "/manage", text: "Manage Room" })));
    } else if (s.room.you.role === "member" && s.room.alone !== true) {
      // A member who doesn't moderate: the management view only offers leaving.
      roomParts.push(h("p", {}, h("a", { class: "small", href: room.path() + "/manage", text: "Membership" })));
    }
    roomParts.push(h("p", { class: "muted small", text: presenceText(s.presence) }));
    const ul = h("ul", { class: "people" });
    const viewer = this.#personViewer(room, s);
    for (const p of s.presence.people ?? []) {
      const li = h("li", { class: "person", "data-actor": p.actor.kind + ":" + p.actor.id },
        h("span", { class: "person-icon", "aria-hidden": "true", text: "👤" }),
        h("div", { class: "grow" }, actorLabel(p.actor), h("div", { class: "muted small", text: personModeText(p) })));
      // ⋯ when the menu can have anything (the target's room role is looked up when it opens).
      if (personActions(viewer, p, "member").length > 0 || personActions(viewer, p, "moderator").length > 0) {
        const more = h("button", { type: "button", class: "icon person-more", text: "⋯",
          title: "Moderate " + p.actor.name, "aria-label": "Moderate " + p.actor.name, "aria-haspopup": "true" });
        more.addEventListener("click", (ev) => {
          ev.stopPropagation();
          void this.#openPersonMenu(room, p, more);
        });
        li.append(more);
        // Right-click (long press on touch) opens the same menu.
        onLongPress(li, () => { void this.#openPersonMenu(room, p, more); });
      }
      ul.append(li);
    }
    roomParts.push(ul);
    return roomParts;
  }

  #personViewer(room: ShellRoom, s: RoomState): PersonViewer {
    const me = this.#me;
    return { you: s.room.you, actor: room.actor(),
      adminUsers: me?.kind === "user" && me.permissions.indexOf("admin.users") >= 0 };
  }

  // ---------------------------------------------------------------- moderation menu (⋯ on a person)

  #personMenu: HTMLElement | undefined;

  #closePersonMenu(): void {
    this.#personMenu?.remove();
    this.#personMenu = undefined;
  }

  /** Opens the moderation menu under `anchor`: looks up the person's room role first (users, for the owner and moderators). */
  async #openPersonMenu(room: ShellRoom, p: PersonLike, anchor: HTMLElement): Promise<void> {
    this.#closePersonMenu();
    const s = room.state();
    if (s === undefined) return;
    const roomPath = "/api/rooms/" + encodeURIComponent(room.id());
    let role: string | undefined;
    if (p.actor.kind === "user" && s.room.you.canModerate) {
      try {
        const page = await api<Page<{ user: { id: string }; role: string }>>("GET", roomPath + "/members?limit=200");
        role = page.items.find((m) => m.user.id === p.actor.id)?.role ?? "member";
      } catch {
        role = undefined;
      }
    } else if (p.actor.kind !== "user") {
      role = "member"; // guests have no room role
    }
    const actions = personActions(this.#personViewer(room, s), p, role);
    const menu = h("div", { class: "menu person-menu", role: "menu" });
    if (actions.length === 0) {
      menu.append(h("span", { class: "muted small", text: "Nothing you can do here." }));
    }
    for (const a of actions) {
      const item = h("button", { type: "button", role: "menuitem", text: PERSON_ACTION_LABELS[a] });
      item.addEventListener("click", () => {
        this.#closePersonMenu();
        this.#personAction(roomPath, p, a).catch((e: unknown) => this.#showError(e));
      });
      menu.append(item);
    }
    const r = anchor.getBoundingClientRect();
    menu.style.top = String(r.bottom + window.scrollY + 4) + "px";
    menu.style.left = String(Math.max(8, r.right + window.scrollX - 192)) + "px";
    document.body.append(menu);
    this.#personMenu = menu;
  }

  async #personAction(roomPath: string, p: PersonLike, a: PersonAction): Promise<void> {
    this.#showError("");
    const name = p.actor.name;
    const who = (p.actor.kind === "user" ? "/users/" : "/guests/") + encodeURIComponent(p.actor.id);
    switch (a) {
      case "addModerator":
        await api("PUT", roomPath + "/members/" + encodeURIComponent(p.actor.id), { role: "moderator" });
        return;
      case "removeModerator":
        await api("PUT", roomPath + "/members/" + encodeURIComponent(p.actor.id), { role: "member" });
        return;
      case "timeout": {
        const text = window.prompt("Time out " + name + " for how long? For example 10m, 2h, 1d or 1h 30m."
          + " They can keep listening, nothing else.", "10m");
        if (text === null) return;
        const ms = parseDurationInput(text);
        if (ms === undefined) throw new Error("\"" + text + "\" isn't a length between 1 second and 10 years (e.g. 10m, 2h, 1d).");
        await api("PUT", roomPath + "/bans" + who, { durationMs: ms });
        return;
      }
      case "ban":
        if (!window.confirm("Ban " + name + " from this room? Their IP address is banned here too. They can keep"
          + " listening, nothing else, until unbanned (Manage Room).")) return;
        await api("PUT", roomPath + "/bans" + who);
        return;
      case "banFromServer":
        if (!window.confirm("Ban " + name + " from the server? This disables their account.")) return;
        await api("PATCH", "/api/admin/users/" + encodeURIComponent(p.actor.id), { disabled: true });
        return;
    }
  }

  // ---------------------------------------------------------------- room view

  /** The room view's frame-drawn parts: the queue, the title line and vote-skip under the video, people, Earlier. */
  #drawRoomView(room: ShellRoom | undefined, s: RoomState | undefined): void {
    const queue = document.getElementById("rv-queue");
    const people = document.getElementById("rv-people");
    const earlier = document.getElementById("rv-earlier");
    const voteSkipBox = document.getElementById("rv-vote-skip");
    if (queue === null || people === null || earlier === null || voteSkipBox === null) return;
    if (room === undefined || s === undefined) {
      queue.replaceChildren();
      people.replaceChildren();
      earlier.replaceChildren();
      voteSkipBox.replaceChildren();
      voteSkipBox.hidden = true;
      return;
    }
    queue.replaceChildren(...this.#queueParts(room, s, false));
    people.replaceChildren(...this.#peopleParts(room, s));
    earlier.replaceChildren(...this.#earlierParts(s));
    const b = this.#voteSkipButton(room, s);
    const next = this.#nextEpisodeButton(room, s);
    const buttons = [b, next].filter((x): x is HTMLButtonElement => x !== null);
    voteSkipBox.replaceChildren(...buttons);
    voteSkipBox.hidden = buttons.length === 0;
  }

  #historyRow(r: HistoryEntry): HTMLLIElement {
    const li = h("li", { class: "entry" + (r.state === "skipped" ? " skipped" : "") },
      cover(r.item, "cover entry-cover"),
      h("div", { class: "grow" }, this.#entryText(r.item)),
      h("span", { class: "entry-actions" }, this.#heart(r.item.id)));
    this.#draggableItem(li, r.item, "recents", undefined, "r:" + r.entryId);
    return li;
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
      // Right-click (long press on touch): Mute player / Mute only until end of song.
      onLongPress(b, () => { muteMenu.hidden = false; });
    }
    // Mobile: the same choices in the mini bar's ⋯ menu.
    byId("pb-m-mute").addEventListener("click", () => {
      menu.hidden = true;
      this.#mute.toggle();
      this.update();
    });
    byId("pb-m-mute-song").addEventListener("click", () => {
      menu.hidden = true;
      this.#mute.muteUntilEndOf(this.#host.room()?.state()?.current?.entryId);
      this.update();
    });
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
    if (this.#personMenu !== undefined && t.closest(".person-menu") === null) this.#closePersonMenu();
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
    byId("pb-m-mute").textContent = muted ? "Unmute" : "Mute";
    byId("pb-m-mute-song").hidden = muted;
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
    // The view's title can change after its data loads (mobile top bar).
    this.#drawBarTitle();
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
    const roomView = this.#roomViewShown() || this.#pane === "now";
    const blocked = !byId("room-listen-overlay").hidden;
    const video = room !== undefined && room.mode() === "listen" && !container.hidden;
    // In the right panel: collapsed for audio items (and Audio Only), unless the "Click to enable
    // sound" layer is up. The room view's video area always shows; the Now Playing View too. Where
    // no video shows, a card like the stream's item card.
    panel.hidden = !roomView && (room === undefined || (!video && !nowView && !blocked));
    card.hidden = video || !(nowView || roomView || blocked);
    const c = room?.state()?.current;
    if (!card.hidden) {
      const key = (c?.item.id ?? "") + "|" + (c?.item.coverUrl ?? "") + "|" + (c?.item.title ?? room?.name() ?? "");
      if (card.dataset["key"] !== key) {
        card.dataset["key"] = key;
        card.replaceChildren(cover(c?.item, "cover card-cover"),
          h("div", { class: "card-title", text: c?.item.title ?? (room?.name() ?? "") }),
          h("div", { class: "card-artist", text: c !== undefined ? c.item.artist ?? "" : "Nothing is playing" }));
      }
    }
    // The room view's title line under the video: only while a video shows (the card has it otherwise).
    const title = document.getElementById("rv-title");
    if (title !== null) {
      title.hidden = !video || c === undefined;
      if (!title.hidden && c !== undefined && title.dataset["item"] !== c.item.id) {
        title.dataset["item"] = c.item.id;
        title.replaceChildren(this.#entryText(c.item));
      }
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
