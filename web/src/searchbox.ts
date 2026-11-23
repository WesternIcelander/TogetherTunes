// The header search box's dropdown (docs/ui.md#header-search). 1 s after the last keystroke (2 or
// more characters) it asks the library search (`GET /api/search?q=&limit=8`) and shows the results
// under the box: cover, title, artist; a click opens the item page, ⋯ / right-click gives the song
// menu as in the lists; "Searching…" while it loads, "Nothing found." when empty, and See all
// results at the bottom (= Enter). At the top, **Search YouTube** (people who may import YouTube
// links) replaces the results with YouTube's (ytrows.ts), and "← Library results" goes back. Escape
// or a click outside closes it (while a row's menu is open, a click outside closes only the menu);
// a double-click on a row adds it to the room open here as in the lists (a library row: a request,
// alone the double-click setting; a YouTube row: its Add to Queue), so where it would, a single
// click opens the row only after the lists' 300 ms wait. ↑/↓ move through the rows and Enter on a row opens it (a YouTube
// row: its watch page in a new tab); Enter with no row chosen opens the full search page (the form's
// own submit, which the shell turns into a view change). Typing again closes it and waits again.
// The rules (timer, stale answers, keyboard, texts) are in the DOM-free searchdrop.ts and
// ytsearch.ts; the server enforces every rule.
import { api, ApiError } from "./api.js";
import { h } from "./dom.js";
import { doubleClickAction, doubleClickText, songMenuEntries } from "./interactions.js";
import { itemPath, mediaPath, type ItemSummary, type Page } from "./media.js";
import { currentRoom, navigate } from "./nav.js";
import { copyText } from "./outputspanel.js";
import { prefs } from "./prefsclient.js";
import {
  ClickOpener, dropStatus, enterAction, Latest, rowDoubleClickAdds, libraryDropdownPath, LIBRARY_RESULTS_LABEL, moveActive, SEARCH_YOUTUBE_LABEL,
  searchPagePath, SearchTimer, SEE_ALL_LABEL, type DropMode,
} from "./searchdrop.js";
import { message, type Me } from "./session.js";
import { findVideo, itemLinks, queueItem } from "./songmenu.js";
import { menuIsOpen, openMenu, type MenuItem } from "./touch.js";
import { pickedText } from "./videosearch.js";
import { youtubeRows } from "./ytrows.js";
import { canSearchYoutube, DROPDOWN_YOUTUBE_LIMIT, queuedText, youtubeErrorText, youtubeSearchPath,
  type YoutubeResult } from "./ytsearch.js";

/** An item's cover in the dropdown: its cover.jpg, a placeholder while there is none (or it fails). */
function cover(item: ItemSummary): HTMLElement {
  const none = (): HTMLElement => h("div", { class: "cover search-drop-cover cover-none", "aria-hidden": "true" });
  if (item.status !== "ready") return none();
  const img = h("img", { class: "cover search-drop-cover", src: mediaPath(item.id, "cover.jpg"), alt: "", loading: "lazy" });
  img.addEventListener("error", () => img.replaceWith(none()));
  return img;
}

export class HeaderSearch {
  readonly #form: HTMLFormElement;
  readonly #input: HTMLInputElement;
  readonly #me: () => Me | undefined;
  readonly #drop: HTMLElement;
  readonly #top: HTMLElement;
  readonly #status: HTMLElement;
  readonly #list: HTMLUListElement;
  readonly #notice: HTMLElement;
  readonly #all: HTMLButtonElement;
  readonly #timer: SearchTimer;
  readonly #latest = new Latest();
  readonly #opener = new ClickOpener({ set: (fn, ms) => window.setTimeout(fn, ms), clear: (id) => window.clearTimeout(id) });
  #open = false;
  #mode: DropMode = "library";
  /** The text the shown results are for. */
  #query = "";
  #library: ItemSummary[] = [];
  #youtube: YoutubeResult[] = [];
  #loading = false;
  #active = -1;

  constructor(form: HTMLFormElement, me: () => Me | undefined) {
    this.#form = form;
    this.#me = me;
    const input = form.querySelector<HTMLInputElement>("input[name=q]");
    if (input === null) throw new Error("no search box");
    this.#input = input;
    this.#top = h("div", { class: "search-drop-top" });
    this.#status = h("p", { class: "muted small search-drop-status", role: "status" });
    this.#list = h("ul", { class: "search-drop-list", id: "app-search-results", role: "listbox", "aria-label": "Results" });
    this.#notice = h("p", { class: "small search-drop-notice", role: "status", hidden: true });
    this.#all = h("button", { type: "button", class: "link search-drop-all", text: SEE_ALL_LABEL });
    this.#drop = h("div", { class: "search-drop", id: "app-search-drop", hidden: true },
      this.#top, this.#status, this.#list, this.#notice, h("div", { class: "search-drop-foot" }, this.#all));
    form.append(this.#drop);
    input.setAttribute("autocomplete", "off");
    input.setAttribute("aria-controls", "app-search-results");
    input.setAttribute("aria-expanded", "false");
    this.#timer = new SearchTimer({ set: (fn, ms) => window.setTimeout(fn, ms), clear: (id) => window.clearTimeout(id) },
      (q) => { void this.#searchLibrary(q); });
    input.addEventListener("input", () => {
      this.close();
      this.#timer.input(input.value);
    });
    input.addEventListener("keydown", (ev) => this.#key(ev));
    // Registered on the form, so it runs before the shell's submit handler on the document.
    form.addEventListener("submit", (ev) => {
      this.#timer.cancel();
      if (this.#open && enterAction(this.#active, this.#rowCount()) === "row") {
        ev.preventDefault();
        this.#openRow(this.#active);
        return;
      }
      this.close();
    });
    this.#all.addEventListener("click", () => this.#seeAll());
    // In the capture phase, before touch.ts closes an open menu on the same click: a click outside
    // while a row's menu is open closes only the menu; the next one outside closes the dropdown.
    document.addEventListener("click", (ev) => {
      const t = ev.target;
      if (!this.#open || !(t instanceof Element) || !t.isConnected) return;
      if (this.#form.contains(t) || t.closest(".popup-menu, .classify-prompt") !== null) return;
      if (menuIsOpen()) return;
      this.close();
    }, true);
  }

  /** Closes the dropdown (an answer still on its way is dropped). */
  close(): void {
    this.#latest.drop();
    this.#opener.cancel();
    this.#open = false;
    this.#mode = "library";
    this.#active = -1;
    this.#loading = false;
    this.#drop.hidden = true;
    this.#input.setAttribute("aria-expanded", "false");
    this.#input.removeAttribute("aria-activedescendant");
  }

  #show(): void {
    this.#open = true;
    this.#drop.hidden = false;
    this.#input.setAttribute("aria-expanded", "true");
  }

  #rowCount(): number {
    return this.#mode === "library" ? this.#library.length : this.#youtube.length;
  }

  #key(ev: KeyboardEvent): void {
    if (ev.key === "Escape") {
      this.#timer.cancel();
      if (this.#open) {
        ev.preventDefault();
        this.close();
      }
      return;
    }
    if (!this.#open || (ev.key !== "ArrowDown" && ev.key !== "ArrowUp")) return;
    ev.preventDefault();
    this.#active = moveActive(this.#active, this.#rowCount(), ev.key === "ArrowDown" ? 1 : -1);
    this.#markActive();
  }

  #rows(): HTMLElement[] {
    return Array.from(this.#list.querySelectorAll<HTMLElement>(":scope > li"));
  }

  #markActive(): void {
    this.#rows().forEach((li, i) => {
      const on = i === this.#active;
      li.classList.toggle("active", on);
      li.setAttribute("aria-selected", on ? "true" : "false");
      if (on) {
        this.#input.setAttribute("aria-activedescendant", li.id);
        li.scrollIntoView({ block: "nearest" });
      }
    });
    if (this.#active < 0) this.#input.removeAttribute("aria-activedescendant");
  }

  #openRow(i: number): void {
    if (this.#mode === "library") {
      const item = this.#library[i];
      if (item === undefined) return;
      this.close();
      navigate(itemPath(item.id));
      return;
    }
    const li = this.#rows()[i];
    li?.querySelector<HTMLAnchorElement>("a.button-link")?.click();
  }

  #seeAll(): void {
    const q = this.#query !== "" ? this.#query : this.#input.value.trim();
    this.#timer.cancel();
    this.close();
    if (q !== "") navigate(searchPagePath(q));
  }

  #say(text: string, error = false): void {
    this.#notice.textContent = text;
    this.#notice.className = "small search-drop-notice" + (error ? " error" : "");
    this.#notice.hidden = text === "";
  }

  /** The top bar: Search YouTube in the library results (for those who may), ← Library results in YouTube's. */
  #drawTop(): void {
    const parts: HTMLElement[] = [];
    if (this.#mode === "youtube") {
      const back = h("button", { type: "button", class: "link", text: LIBRARY_RESULTS_LABEL });
      back.addEventListener("click", () => {
        this.#latest.drop();
        this.#mode = "library";
        this.#loading = false;
        this.#draw();
        this.#input.focus();
      });
      parts.push(back);
    } else if (canSearchYoutube(this.#me())) {
      const yt = h("button", { type: "button", class: "small", text: SEARCH_YOUTUBE_LABEL });
      yt.addEventListener("click", () => { void this.#searchYoutube(); });
      parts.push(yt);
    }
    this.#top.replaceChildren(...parts);
    this.#top.hidden = parts.length === 0;
  }

  #draw(): void {
    this.#active = -1;
    this.#say("");
    this.#drawTop();
    const count = this.#rowCount();
    const status = dropStatus(this.#loading, count);
    this.#status.textContent = status;
    this.#status.hidden = status === "";
    this.#list.classList.toggle("video-results", this.#mode === "youtube");
    if (this.#loading) {
      this.#list.replaceChildren();
    } else if (this.#mode === "library") {
      this.#list.replaceChildren(...this.#library.map((item, i) => this.#libraryRow(item, i)));
    } else {
      const rows = youtubeRows(this.#youtube, {
        notice: (text, error) => this.#say(text, error === true),
        acting: (a) => { if (a === "addToLibrary") this.close(); },
        doubleClickAdds: true,
      });
      rows.forEach((li, i) => {
        li.id = "app-search-row-" + i;
        li.setAttribute("role", "option");
      });
      this.#list.replaceChildren(...rows);
    }
    this.#markActive();
  }

  #libraryRow(item: ItemSummary, i: number): HTMLLIElement {
    const more = h("button", { type: "button", class: "icon row-more", text: "⋯", title: "More",
      "aria-label": "More for " + item.title, "aria-haspopup": "true", tabindex: "-1" });
    more.addEventListener("click", (ev) => {
      ev.stopPropagation();
      void this.#openMenu(item, more);
    });
    const li = h("li", { class: "search-drop-row", id: "app-search-row-" + i, role: "option", "data-item": item.id },
      cover(item),
      h("div", { class: "search-drop-text" },
        h("a", { href: itemPath(item.id), class: "search-drop-title", tabindex: "-1", text: item.title }),
        h("div", { class: "muted small", text: item.artist ?? "" })),
      more);
    li.addEventListener("click", (ev) => {
      if ((ev.target as Element).closest(".row-more") !== null) return;
      ev.preventDefault();
      this.#opener.click(ev.detail, rowDoubleClickAdds("library", currentRoom()?.state()?.room), () => this.#openRow(i));
    });
    li.addEventListener("dblclick", (ev) => {
      if ((ev.target as Element).closest(".row-more") !== null) return;
      ev.preventDefault();
      this.#opener.cancel();
      if (!rowDoubleClickAdds("library", currentRoom()?.state()?.room)) return;
      window.getSelection()?.removeAllRanges();
      void this.#doubleClick(item);
    });
    li.addEventListener("contextmenu", (ev) => {
      ev.preventDefault();
      void this.#openMenu(item, more);
    });
    return li;
  }

  async #searchLibrary(q: string): Promise<void> {
    const ticket = this.#latest.next();
    this.#query = q;
    this.#mode = "library";
    this.#library = [];
    this.#youtube = [];
    this.#loading = true;
    this.#show();
    this.#draw();
    try {
      const page = await api<Page<{ item: ItemSummary }>>("GET", libraryDropdownPath(q));
      if (!this.#latest.current(ticket)) return;
      this.#library = page.items.map((r) => r.item);
      this.#loading = false;
      this.#draw();
    } catch (e) {
      if (!this.#latest.current(ticket)) return;
      this.#loading = false;
      this.#draw();
      this.#status.hidden = true;
      this.#say(message(e), true);
    }
  }

  async #searchYoutube(): Promise<void> {
    const ticket = this.#latest.next();
    const q = this.#query;
    this.#mode = "youtube";
    this.#youtube = [];
    this.#loading = true;
    this.#draw();
    try {
      const r = await api<{ items: YoutubeResult[] }>("GET", youtubeSearchPath(q, DROPDOWN_YOUTUBE_LIMIT));
      if (!this.#latest.current(ticket)) return;
      this.#youtube = r.items;
      this.#loading = false;
      this.#draw();
    } catch (e) {
      if (!this.#latest.current(ticket)) return;
      this.#loading = false;
      this.#draw();
      this.#status.hidden = true;
      this.#say(e instanceof ApiError ? youtubeErrorText(e.code, e.message) : message(e), true);
    }
  }

  /** The song menu, as in the lists (search results: no remove, no Select). */
  async #openMenu(item: ItemSummary, anchor: HTMLElement): Promise<void> {
    const links = await itemLinks(item.id);
    if (!this.#open) return;
    const room = currentRoom();
    const alone = room?.state()?.room.alone === true;
    const me = this.#me();
    const entries = songMenuEntries({ room: room !== undefined, alone, list: "search", signedIn: me?.kind === "user",
      youtube: canSearchYoutube(me), links })
      .filter((e) => e.kind !== "song" || e.action !== "select");
    const items: MenuItem[] = entries.map((e) => ({ label: e.label, action: () => {
      const title = (id: string): string => (id === item.id ? item.title
        : links.find((l) => l.item?.id === id)?.item?.title ?? "");
      switch (e.kind) {
        case "request":
        case "playNow":
        case "queue":
          void this.#queue(e.itemId, title(e.itemId), e.kind);
          return;
        case "open": window.open(e.url, "_blank", "noopener,noreferrer"); return;
        case "findVideo": void this.#findVideo(item); return;
        case "song": break;
      }
      switch (e.action) {
        case "request":
        case "playNow":
        case "queue":
          void this.#queue(item.id, item.title, e.action);
          return;
        case "open":
          this.close();
          navigate(itemPath(item.id));
          return;
        case "copyLink":
          this.#notice.hidden = false;
          this.#notice.className = "small search-drop-notice";
          copyText(location.origin + itemPath(item.id), this.#notice);
          return;
        default:
          return;
      }
    } }));
    openMenu(anchor, items, item.title);
  }

  async #queue(itemId: string, title: string, how: "request" | "queue" | "playNow"): Promise<void> {
    const room = currentRoom();
    if (room === undefined) {
      this.#say("Open a room first: Add to Queue adds the song to the room's queue.", true);
      return;
    }
    try {
      const outcome = await queueItem(room, itemId, how);
      this.#say(queuedText(outcome, title, room.name(), false));
    } catch (e) {
      this.#say(title + ": " + message(e), true);
    }
  }

  /** Double-click on a library row: as in the lists (a request; alone the double-click setting), with their notice. */
  async #doubleClick(item: ItemSummary): Promise<void> {
    const room = currentRoom();
    if (room === undefined) return;
    const alone = room.state()?.room.alone === true;
    const setting = alone ? await prefs.load().then((p) => p.doubleClickAlone, () => undefined) : undefined;
    try {
      const outcome = await queueItem(room, item.id, doubleClickAction(alone, setting));
      this.#say(doubleClickText(outcome, item.title, room.name()));
    } catch (e) {
      this.#say(item.title + ": " + message(e), true);
    }
  }

  /** Find a music video…: the dialog, then the video is added instead of the song (as double-click would). */
  async #findVideo(item: ItemSummary): Promise<void> {
    const found = await findVideo(item);
    if (found === null) return;
    const room = currentRoom();
    if (room === undefined) {
      this.#say(pickedText(found, item.title));
      return;
    }
    const alone = room.state()?.room.alone === true;
    const setting = alone ? await prefs.load().then((p) => p.doubleClickAlone, () => undefined) : undefined;
    await this.#queue(found.itemId, found.title, doubleClickAction(alone, setting));
  }
}
