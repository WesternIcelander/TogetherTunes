// The single-page app's shell (docs/ui.md#single-page-app). The server sends index.html for every
// UI path; this shows the URL's view in `#app` and handles every same-site navigation in place
// (rules in router.ts), so moving around never loads another document:
//
// - a view is its `<template id="view-…">` from index.html, cloned into a fresh `<div class="view">`,
//   plus its module, imported under a fresh `?mount=N` (a new module instance starts like on a page
//   load and finds its elements); the previous view's signal (nav.ts `pageSignal()`) is aborted and
//   its element removed;
// - links, a view's own `navigate()` / `reloadPage()` calls (nav.ts) and plain GET form submits go
//   through `decide()`; the URL is set with history.pushState (replaceState for a redirect), back /
//   forward (popstate) show the entry's view again, scroll positions are kept in history.state, and
//   focus goes to the new view's heading;
// - the room view, while it plays in listen mode, is kept (hidden, with its media elements, socket
//   and clock sync running) when another view is shown; the desktop frame around the views
//   (frame.ts: sidebars, right panel, player bar) follows that room meanwhile, and a link back shows
//   the room view again. Another room and the identity views (login, register, password, setup) then
//   load a new document, which leaves the room; Leave (in the right panel) takes the room view away
//   in place.
import { decide, pageKey, routeOf, VIEWS, type ClickInfo, type Decision, type ViewRoute } from "./router.js";
import { navigate, rememberPlaying, setNavigator, setRoomHost, startPageSignal, type RoomHandle,
  type ShellRoom } from "./nav.js";
import { Frame } from "./frame.js";
import { recordEvent } from "./diagnostics.js";

interface HistoryState {
  scrollY?: number;
}

/** A view in the document: shown, or (the room) kept playing in the background. */
interface Mounted {
  route: ViewRoute;
  /** pathname + search it was shown for. */
  key: string;
  el: HTMLElement;
  controller: AbortController;
  /** Its module's `?mount=` number (0: none). */
  mount: number;
  /** The room view's hooks, once its module attached them. */
  room?: ShellRoom;
}

export class Shell {
  readonly #app: HTMLElement;
  /** The view shown. */
  #view: Mounted | undefined;
  /** The room view playing in the background while another view is shown. */
  #kept: Mounted | undefined;
  #mounts = 0;
  /** Counts navigations: a slow import that was overtaken is dropped. */
  #seq = 0;
  /** The desktop layout around the views. */
  readonly #frame: Frame;
  /** Leave was clicked while the room view was shown: don't keep it when the next view comes. */
  #leaving = false;

  constructor(app: HTMLElement) {
    this.#app = app;
    try {
      history.scrollRestoration = "manual";
    } catch {
      // Not settable: the browser restores (too early, but harmlessly).
    }
    this.#frame = new Frame({
      room: () => this.#followed(),
      roomShown: () => this.#view?.route.view === "room",
      leave: () => this.leave(),
    });
    document.addEventListener("click", (ev) => this.#onClick(ev));
    document.addEventListener("submit", (ev) => this.#onSubmit(ev));
    window.addEventListener("popstate", () => this.#onPopState());
    // A view taken away while its requests were still out fails when they come back (its elements
    // are gone): not an error worth reporting.
    window.addEventListener("unhandledrejection", (ev) => {
      if (this.#fromOldView(ev.reason)) ev.preventDefault();
    });
    setNavigator((href, replace) => this.#navigate(href, replace));
    setRoomHost((room) => this.#attach(room));
  }

  /** Shows the view of the document's own URL (the first view after a load). */
  start(): void {
    const url = new URL(location.href);
    const route = routeOf(url.pathname);
    if (route.kind === "view" && route.path !== url.pathname) {
      // An alias (`/login.html`, `/library`): show the view's own spelling.
      url.pathname = route.path;
      this.#replaceState({}, url.pathname + url.search + url.hash);
    }
    void this.#frame.start();
    void this.#show(url, 0);
  }

  /** The room the frame follows: the room view's room, shown or kept in the background. */
  #followed(): ShellRoom | undefined {
    if (this.#kept?.room !== undefined) return this.#kept.room;
    return this.#view?.route.view === "room" ? this.#view.room : undefined;
  }

  /** Leave (right panel): stop the room, shown or playing in the background (leaves the room). */
  leave(): void {
    const kept = this.#kept;
    if (kept === undefined) {
      if (this.#view?.route.view !== "room") return;
      // The room view is shown: go to the library (or the front page) without keeping it.
      this.#leaving = true;
      navigate("/");
      return;
    }
    this.#kept = undefined;
    this.#unmount(kept);
    rememberPlaying(null);
    recordEvent("shell", { leave: kept.key });
    this.update();
  }

  /** Refreshes the frame's room parts (the room calls it on every state). */
  update(): void {
    const room = this.#kept?.room;
    if (room !== undefined) {
      const stopped = room.stopped();
      rememberPlaying(stopped === undefined ? { path: room.path(), name: room.name() } : null);
    }
    this.#frame.update();
  }

  /** The room view's module tells the shell about itself (nav.ts `attachRoom`). */
  #attach(room: ShellRoom): RoomHandle {
    // The view being started is the room view whose module calls this.
    const m = this.#view !== undefined && this.#view.route.view === "room" && this.#view.room === undefined
      ? this.#view : undefined;
    if (m !== undefined) m.room = room;
    return {
      get away(): boolean {
        return m !== undefined && m.el.hidden === true;
      },
      update: () => this.update(),
    };
  }

  /** The room playing here (shown or in the background), whose rules decide navigations. */
  #playing(): ShellRoom | undefined {
    const m = this.#kept ?? (this.#view?.route.view === "room" ? this.#view : undefined);
    const room = m?.room;
    return room !== undefined && room.active() ? room : undefined;
  }

  /** Whether `e` was thrown by the module of a view that is no longer in the document. */
  #fromOldView(e: unknown): boolean {
    const stack = e instanceof Error ? e.stack ?? "" : "";
    const mounts = stack.match(/[?&]mount=\d+/g);
    if (mounts === null) return false;
    const live = [this.#view, this.#kept].filter((m): m is Mounted => m !== undefined).map((m) => "mount=" + m.mount);
    return mounts.every((m) => live.indexOf(m.slice(1)) < 0);
  }

  #clickInfo(ev: MouseEvent, a: Element): ClickInfo {
    return {
      button: ev.button, ctrlKey: ev.ctrlKey, metaKey: ev.metaKey, shiftKey: ev.shiftKey, altKey: ev.altKey,
      defaultPrevented: ev.defaultPrevented, target: a.getAttribute("target") ?? "", download: a.hasAttribute("download"),
    };
  }

  #decide(href: string, click?: ClickInfo): Decision {
    const room = this.#playing();
    return decide(href, location.href, room !== undefined ? (ref) => room.isRoom(ref) : undefined, click);
  }

  #onClick(ev: MouseEvent): void {
    const t = ev.target;
    if (!(t instanceof Element)) return;
    const a = t.closest("a");
    if (!(a instanceof HTMLAnchorElement) || !a.hasAttribute("href")) return;
    const d = this.#decide(a.href, this.#clickInfo(ev, a));
    if (d === "browser" || d === "hash") return;
    ev.preventDefault();
    this.#go(a.href, false);
  }

  /** A form that really submits (no script handled it) with GET to a view. */
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
    this.#go(url.href, false);
  }

  #navigate(href: string, replace: boolean): boolean {
    const d = this.#decide(href);
    if (d === "browser") return false;
    if (d === "hash") {
      if (!replace) {
        location.hash = new URL(href, location.href).hash;
        return true;
      }
      // The same view again (reloadPage()): start it over in place, keeping the fragment. The
      // playing room isn't started over (that would stop it): the browser reloads it.
      if (this.#view?.route.view === "room") return false;
    }
    this.#go(href, replace);
    return true;
  }

  #go(href: string, replace: boolean): void {
    const url = new URL(href, location.href);
    const route = routeOf(url.pathname);
    if (route.kind === "view") url.pathname = route.path;
    this.#saveScroll();
    const target = url.pathname + url.search + url.hash;
    if (replace) this.#replaceState({}, target);
    else {
      try {
        history.pushState({}, "", target);
      } catch {
        // Not allowed (a file:// page): the view changes, the address doesn't.
      }
    }
    void this.#show(url, 0);
  }

  #onPopState(): void {
    const key = location.pathname + location.search;
    // Only the fragment changed (an in-page link, a tab of the admin view): the view handles it.
    if (this.#view !== undefined && key === this.#view.key) return;
    const state = (history.state ?? {}) as HistoryState;
    const scrollY = typeof state.scrollY === "number" ? state.scrollY : 0;
    if (this.#decide(location.href) === "browser") {
      // Not shown in place while the room plays (another room, login, ...): a normal load.
      location.reload();
      return;
    }
    void this.#show(new URL(location.href), scrollY);
  }

  #saveScroll(): void {
    const state = (history.state ?? {}) as HistoryState;
    this.#replaceState({ ...state, scrollY: window.scrollY });
  }

  #replaceState(state: HistoryState, url?: string): void {
    try {
      if (url === undefined) history.replaceState(state, "");
      else history.replaceState(state, "", url);
    } catch {
      // Not allowed (a file:// page): scroll positions aren't kept.
    }
  }

  /** Aborts a view's signal and removes its element. */
  #unmount(m: Mounted): void {
    m.controller.abort();
    m.el.remove();
  }

  /** Takes the shown view away: kept (hidden) if it is the room playing here, otherwise removed. */
  #putAway(): void {
    const v = this.#view;
    this.#view = undefined;
    if (v === undefined || v === this.#kept) return;
    const leaving = this.#leaving;
    this.#leaving = false;
    if (v.route.view === "room" && v.room !== undefined && v.room.active() && !leaving) {
      if (this.#kept !== undefined) this.#unmount(this.#kept);
      v.el.hidden = true;
      this.#kept = v;
      return;
    }
    this.#unmount(v);
  }

  /** Shows the view for `url`: the kept room again, or a new view. */
  async #show(url: URL, scrollY: number): Promise<void> {
    const seq = ++this.#seq;
    const key = url.pathname + url.search;
    const r = routeOf(url.pathname);
    const route: ViewRoute = r.kind === "view" ? r : { kind: "view", view: "notFound", params: {}, path: url.pathname };
    const shown = this.#view;
    if (route.view === "room" && shown?.route.view === "room" && shown.room !== undefined
      && shown.room.isRoom(route.params["ref"] ?? "")) {
      // The room view already shown (a link to it): nothing to start.
      shown.key = key;
      this.#scroll(scrollY, url.hash);
      return;
    }
    const kept = this.#kept;
    if (route.view === "room" && kept?.room !== undefined && kept.room.isRoom(route.params["ref"] ?? "")) {
      // The room playing in the background: show it again, without restarting anything.
      this.#putAway();
      this.#kept = undefined;
      kept.el.hidden = false;
      kept.key = key;
      this.#view = kept;
      document.title = kept.room.title();
      rememberPlaying(null);
      this.update();
      kept.room.shown();
      this.#frame.navigated(url.pathname + url.search);
      recordEvent("shell", { show: "room" });
      this.#scroll(scrollY, url.hash);
      this.#focus(kept.el);
      return;
    }
    this.#putAway();
    // A new room view replaces one playing in the background (decide() only lets it through when
    // that one isn't playing any more).
    if (route.view === "room" && this.#kept !== undefined) {
      this.#unmount(this.#kept);
      this.#kept = undefined;
    }
    const info = VIEWS[route.view];
    const template = document.getElementById(info.template);
    const el = document.createElement("div");
    el.className = "view view-" + route.view;
    el.tabIndex = -1;
    if (template instanceof HTMLTemplateElement) {
      el.append(template.content.cloneNode(true));
      document.title = template.dataset["title"] ?? "TogetherTunes";
    } else {
      el.textContent = "This page is missing from the app (" + info.template + ").";
      document.title = "TogetherTunes";
    }
    const controller = startPageSignal();
    const m: Mounted = { route, key, el, controller, mount: 0 };
    this.#view = m;
    this.#app.append(el);
    this.update();
    this.#frame.navigated(url.pathname + url.search);
    recordEvent("shell", { show: pageKey(url.href) });
    if (info.module !== undefined) {
      // The view's module, run afresh: a new URL is a new module instance, which starts like on a page load.
      const mount = ++this.#mounts;
      m.mount = mount;
      const moduleUrl = new URL("/js/" + info.module + ".js", location.href);
      moduleUrl.searchParams.set("mount", String(mount));
      try {
        await import(moduleUrl.href);
      } catch (e) {
        recordEvent("shell", { importFailed: moduleUrl.pathname, error: String(e) });
        if (seq === this.#seq) {
          const p = document.createElement("p");
          p.className = "error";
          p.setAttribute("role", "alert");
          p.textContent = "This page failed to load. Reload to try again.";
          el.prepend(p);
        }
        return;
      }
    }
    if (seq !== this.#seq) return;
    this.#scroll(scrollY, url.hash);
    // After a load the browser puts focus at the start already; after a navigation, the new view's heading.
    if (seq > 1) this.#focus(el);
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
    // Views fill in after their data loads: try once more when that's likely done.
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
