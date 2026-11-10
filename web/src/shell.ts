// The room page's shell: keeps listen-mode playback going while the user visits other pages of
// the site. Every page is still its own HTML document with one module (deep links and reloads
// work as before); while the room plays in listen mode, the room page handles same-site
// navigations itself instead of letting the browser unload it (rules in router.ts):
//
// - a link (or a page script's `navigate()`, nav.ts) to a site page: the page's HTML is fetched,
//   its content goes into `#shell-page` (the room's view, `#room-body`, is hidden, with the media
//   elements, the WebSocket and the clock sync left running), its title is taken over, the URL is
//   set with history.pushState, and its module is run again (imported under a fresh `?mount=`,
//   so it starts as on a page load and finds its elements; the previous page's `pageSignal()` is
//   aborted first);
// - a link back to the playing room shows its view again, without restarting anything;
// - back / forward (popstate) do the same for the entries this page pushed; scroll positions are
//   kept in history.state, focus goes to the new page's heading;
// - a mini player (room name linking back, what plays, play state, "Audio only", Leave) is shown
//   on the other pages.
//
// Another room, logging in or out, other sites and downloads go to the browser as usual, which
// leaves the room. Remote mode plays nothing, so the shell stands aside there.
import { decide, pageKey, routeOf, type ClickInfo, type Decision } from "./router.js";
import { nextPageSignal, rememberPlaying, setNavigator } from "./nav.js";
import { recordEvent } from "./diagnostics.js";

/** What the shell needs from the room page. */
export interface ShellRoom {
  /** Whether the room plays here now (listen mode, not kicked): only then the shell takes over. */
  active(): boolean;
  /** Whether `ref` (id or slug) is this room. */
  isRoom(ref: string): boolean;
  /** The room page's path (`/rooms/{slug}`). */
  path(): string;
  name(): string;
  /** The title of the room page. */
  title(): string;
  /** What plays: title (empty when nothing) and the timeline state. */
  nowPlaying(): { title: string; state: string };
  /** Why the room stopped playing here (kicked), or undefined. */
  stopped(): string | undefined;
  audioOnly(): boolean;
  setAudioOnly(on: boolean): void;
  /** The room view is shown again (re-render the navigation etc.). */
  shown(): void;
}

interface HistoryState {
  /** Set on the entries this document manages. */
  tt?: true;
  scrollY?: number;
}

export class Shell {
  readonly #room: ShellRoom;
  readonly #roomBody: HTMLElement;
  /** `#shell-page` while another page is shown. */
  #page: HTMLElement | undefined;
  /** pathname + search of what is shown (the room page, or another page). */
  #shownKey: string;
  #mounts = 0;
  /** Counts navigations: a slow fetch that was overtaken is dropped. */
  #seq = 0;
  readonly #mini: HTMLElement;
  readonly #miniRoom: HTMLAnchorElement;
  readonly #miniTitle: HTMLElement;
  readonly #miniState: HTMLElement;
  readonly #miniAudioOnly: HTMLInputElement;

  constructor(room: ShellRoom, roomBody: HTMLElement) {
    this.#room = room;
    this.#roomBody = roomBody;
    this.#shownKey = location.pathname + location.search;
    try {
      history.scrollRestoration = "manual";
    } catch {
      // Not settable: the browser restores (too early, but harmlessly).
    }
    this.#replaceState({ tt: true });
    this.#mini = document.createElement("div");
    this.#mini.id = "mini-player";
    this.#mini.className = "mini-player";
    this.#mini.setAttribute("role", "region");
    this.#mini.setAttribute("aria-label", "Now playing");
    this.#mini.hidden = true;
    this.#miniRoom = document.createElement("a");
    this.#miniRoom.className = "mini-room";
    this.#miniState = document.createElement("span");
    this.#miniState.className = "mini-state";
    this.#miniTitle = document.createElement("span");
    this.#miniTitle.className = "mini-title grow";
    const label = document.createElement("label");
    label.className = "small";
    this.#miniAudioOnly = document.createElement("input");
    this.#miniAudioOnly.type = "checkbox";
    this.#miniAudioOnly.id = "mini-audio-only";
    label.append(this.#miniAudioOnly, " Audio only");
    this.#miniAudioOnly.addEventListener("change", () => this.#room.setAudioOnly(this.#miniAudioOnly.checked));
    const leave = document.createElement("button");
    leave.type = "button";
    leave.id = "mini-leave";
    leave.textContent = "Leave";
    leave.title = "Stop listening and leave the room";
    leave.addEventListener("click", () => this.leave());
    this.#mini.append(this.#miniState, this.#miniRoom, this.#miniTitle, label, leave);
    document.body.append(this.#mini);

    document.addEventListener("click", (ev) => this.#onClick(ev));
    document.addEventListener("submit", (ev) => this.#onSubmit(ev));
    window.addEventListener("popstate", () => this.#onPopState());
    // A page taken away while its requests were still out fails when they come back (its elements
    // are gone): not an error worth reporting.
    window.addEventListener("unhandledrejection", (ev) => {
      if (this.#fromOldPage(ev.reason)) ev.preventDefault();
    });
    setNavigator((href, replace) => this.#navigate(href, replace));
    this.update();
  }

  /** Whether another page is shown (the room view is hidden). */
  get away(): boolean {
    return this.#page !== undefined;
  }

  /** Refreshes the mini player (call on every room state). */
  update(): void {
    const away = this.#page !== undefined;
    this.#mini.hidden = !away;
    document.body.classList.toggle("has-mini-player", away);
    if (!away) return;
    this.#miniRoom.href = this.#room.path();
    this.#miniRoom.textContent = this.#room.name();
    const stopped = this.#room.stopped();
    const now = this.#room.nowPlaying();
    let state: string;
    if (stopped !== undefined) state = "Stopped";
    else if (now.title === "" || now.state === "idle") state = "Nothing playing";
    else if (now.state === "playing") state = "▶ Playing";
    else state = "⏸ Paused";
    this.#miniState.textContent = state;
    this.#miniTitle.textContent = stopped ?? now.title;
    this.#miniAudioOnly.checked = this.#room.audioOnly();
    this.#miniAudioOnly.disabled = stopped !== undefined;
    rememberPlaying(stopped === undefined ? { path: this.#room.path(), name: this.#room.name() } : null);
  }

  /**
   * The mini player's Leave: stop playing and load the shown page normally (unloading the room page
   * closes its socket, which leaves the room).
   */
  leave(): void {
    rememberPlaying(null);
    setNavigator(undefined);
    location.replace(location.href);
  }

  /** Whether `e` was thrown by the module of a page that is no longer shown (`?mount=` in its stack). */
  #fromOldPage(e: unknown): boolean {
    const stack = e instanceof Error ? e.stack ?? "" : "";
    const mounts = stack.match(/[?&]mount=\d+/g);
    if (mounts === null) return false;
    const current = this.#page !== undefined ? "mount=" + this.#mounts : "";
    return mounts.every((m) => m.slice(1) !== current);
  }

  #clickInfo(ev: MouseEvent, a: Element): ClickInfo {
    return {
      button: ev.button, ctrlKey: ev.ctrlKey, metaKey: ev.metaKey, shiftKey: ev.shiftKey, altKey: ev.altKey,
      defaultPrevented: ev.defaultPrevented, target: a.getAttribute("target") ?? "", download: a.hasAttribute("download"),
    };
  }

  #decide(href: string, click?: ClickInfo): Decision {
    // Remote mode or kicked: nothing plays, so a page load costs nothing.
    if (!this.#room.active()) return "browser";
    return decide(href, location.href, (ref) => this.#room.isRoom(ref), click);
  }

  #onClick(ev: MouseEvent): void {
    const t = ev.target;
    if (!(t instanceof Element)) return;
    const a = t.closest("a");
    if (!(a instanceof HTMLAnchorElement) || !a.hasAttribute("href")) return;
    const d = this.#decide(a.href, this.#clickInfo(ev, a));
    if (d === "browser" || d === "hash") return;
    ev.preventDefault();
    this.#go(a.href, d, false);
  }

  /** A form that really submits (no script handled it) with GET to a site page. */
  #onSubmit(ev: SubmitEvent): void {
    const form = ev.target;
    if (!(form instanceof HTMLFormElement) || ev.defaultPrevented) return;
    if (form.method.toLowerCase() !== "get" || (form.target !== "" && form.target !== "_self")) return;
    const url = new URL(form.action, location.href);
    const params = new URLSearchParams();
    for (const [k, v] of new FormData(form)) {
      if (typeof v === "string") params.append(k, v);
    }
    url.search = params.toString();
    const d = this.#decide(url.href);
    if (d === "browser" || d === "hash") return;
    ev.preventDefault();
    this.#go(url.href, d, false);
  }

  #navigate(href: string, replace: boolean): boolean {
    let d = this.#decide(href);
    if (d === "browser") return false;
    if (d === "hash") {
      if (replace) {
        // The same page again (reloadPage()): start it over in place, keeping the fragment.
        if (routeOf(location.pathname).kind !== "page") return false;
        d = "page";
      } else {
        location.hash = new URL(href, location.href).hash;
        return true;
      }
    }
    this.#go(href, d, replace);
    return true;
  }

  #go(href: string, d: "room" | "page", replace: boolean): void {
    const url = new URL(href, location.href);
    this.#saveScroll();
    if (replace) history.replaceState({ tt: true }, "", url.href);
    else history.pushState({ tt: true }, "", url.href);
    if (d === "room") this.#showRoom(0, url.hash);
    else void this.#showPage(url, 0);
  }

  #onPopState(): void {
    const key = location.pathname + location.search;
    // Only the fragment changed (an in-page link, a tab of the admin page): the page handles it.
    if (key === this.#shownKey) return;
    const state = (history.state ?? {}) as HistoryState;
    const scrollY = typeof state.scrollY === "number" ? state.scrollY : 0;
    const route = routeOf(location.pathname);
    if (route.kind === "room" && this.#room.isRoom(route.ref)) {
      this.#showRoom(scrollY, "");
    } else if (route.kind === "page" && state.tt === true) {
      void this.#showPage(new URL(location.href), scrollY);
    } else {
      // Not ours to show (another room's entry): a normal load.
      location.reload();
    }
  }

  #saveScroll(): void {
    const state = (history.state ?? {}) as HistoryState;
    this.#replaceState({ ...state, tt: true, scrollY: window.scrollY });
  }

  #replaceState(state: HistoryState): void {
    try {
      history.replaceState(state, "");
    } catch {
      // Not allowed (a file:// page): scroll positions aren't kept.
    }
  }

  #unmountPage(): void {
    nextPageSignal();
    this.#page?.remove();
    this.#page = undefined;
  }

  #showRoom(scrollY: number, hash: string): void {
    this.#seq++;
    this.#unmountPage();
    this.#shownKey = location.pathname + location.search;
    this.#roomBody.hidden = false;
    document.title = this.#room.title();
    rememberPlaying(null);
    this.update();
    this.#room.shown();
    recordEvent("shell", { show: "room" });
    this.#scroll(scrollY, hash);
    this.#focus(this.#roomBody);
  }

  async #showPage(url: URL, scrollY: number): Promise<void> {
    const seq = ++this.#seq;
    const key = url.pathname + url.search;
    let html: string;
    try {
      const res = await fetch(key, { credentials: "same-origin", headers: { Accept: "text/html" } });
      const type = res.headers.get("Content-Type") ?? "";
      if (!res.ok || !type.includes("text/html")) throw new Error("HTTP " + res.status + " " + type);
      html = await res.text();
    } catch (e) {
      recordEvent("shell", { failed: pageKey(url.href), error: String(e) });
      if (seq === this.#seq) location.reload();
      return;
    }
    if (seq !== this.#seq) return;
    const doc = new DOMParser().parseFromString(html, "text/html");
    const script = doc.querySelector<HTMLScriptElement>("script[type=module][src]");
    const src = script?.getAttribute("src");
    if (src === null || src === undefined) {
      location.reload();
      return;
    }
    this.#unmountPage();
    this.#shownKey = key;
    const page = document.createElement("div");
    page.id = "shell-page";
    page.tabIndex = -1;
    for (const node of Array.from(doc.body.childNodes)) {
      if (node instanceof Element) {
        // The navigation bar stays (the page fills it), and nothing of the room page is duplicated.
        if (node.id === "site-nav" || node.tagName === "SCRIPT" || node.id === "room-listen-overlay") continue;
      }
      page.append(document.adoptNode(node));
    }
    this.#roomBody.hidden = true;
    this.#roomBody.after(page);
    this.#page = page;
    document.title = doc.title;
    this.update();
    recordEvent("shell", { show: pageKey(url.href) });
    // The page's module, run afresh: a new URL is a new module instance, which starts like on a page load.
    const mount = ++this.#mounts;
    const moduleUrl = new URL(src, url);
    moduleUrl.searchParams.set("mount", String(mount));
    try {
      await import(moduleUrl.href);
    } catch (e) {
      recordEvent("shell", { importFailed: moduleUrl.pathname, error: String(e) });
      if (seq === this.#seq) location.reload();
      return;
    }
    this.#scroll(scrollY, url.hash);
    this.#focus(page);
  }

  #scroll(scrollY: number, hash: string): void {
    if (hash.length > 1) {
      let id = hash.slice(1);
      try {
        id = decodeURIComponent(id);
      } catch {
        // Not percent-encoded: use as is.
      }
      const target = document.getElementById(id);
      if (target !== null) {
        target.scrollIntoView();
        return;
      }
    }
    window.scrollTo(0, scrollY);
    // Pages fill in after their data loads: try once more when that's likely done.
    if (scrollY > 0) window.setTimeout(() => window.scrollTo(0, scrollY), 300);
  }

  /** Moves focus to the new view's first heading (or the view), as a page load would reset it. */
  #focus(view: HTMLElement): void {
    const heading = view.querySelector<HTMLElement>("h1");
    const target = heading ?? view;
    if (heading !== null && !heading.hasAttribute("tabindex")) heading.tabIndex = -1;
    try {
      target.focus({ preventScroll: true });
    } catch {
      target.focus();
    }
  }
}
