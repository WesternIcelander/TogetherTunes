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
//   the room view again. Another room's link switches rooms in place (the old room view goes, the new
//   one joins); Leave (in the right panel) takes the room view away in place;
// - logging in doesn't reload either: the login, register, password and setup views are shown in
//   place like any other, and after a login nav.ts `identityChanged()` reaches #identityChanged
//   (the frame and the room's socket re-identify); logging out reloads the document (session.ts);
// - alone and switching rooms (docs/ui.md#alone-and-in-rooms, rules in alone.ts): a signed-in user
//   is always in a room or alone, so whenever no room view is open the shell starts the play
//   session's room (the room they are in, otherwise their alone room) as a background room view
//   (hidden from the start, with its own signal and path, nav.ts `viewStart`). Leave asks fork or
//   restore and then plays the alone room; a room deleted while the user is in it asks the same.
import { decide, pageKey, routeOf, VIEWS, type ClickInfo, type Decision, type ViewRoute } from "./router.js";
import { navigate, registerViewStart, rememberPlaying, setIdentityHook, setNavigator, setRoomHost, startPageSignal,
  type RoomHandle, type ShellRoom } from "./nav.js";
import {
  canBeAlone, leaveKind, leaveOffer, needsLeaveChoice, roomPath, sessionRoom, type LeaveChoice, type LeaveOffer,
  type PlaySession,
} from "./alone.js";
import { api, ApiError } from "./api.js";
import { contentScrollTop, h, scrollContentTo } from "./dom.js";
import { loadMe, message, type Me } from "./session.js";
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
  /** Who is here (loaded when the shell first needs it, again until it is a user). */
  #me: Me | undefined;
  /** A background room is being looked up or asked about (fork / restore): don't start another. */
  #ensuring = false;

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
      leave: () => { void this.leave(); },
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
    setIdentityHook(() => this.#identityChanged());
  }

  /**
   * Who is using the page changed (a login in a view or a popup, nav.ts `identityChanged`): no reload
   * (docs/ui.md#single-page-app). The frame draws the new identity, and the room playing here
   * reconnects its socket as it (playback goes on); only the previous user's alone room stops, as it
   * isn't the new user's, and their own play session's room starts instead.
   */
  async #identityChanged(): Promise<void> {
    const before = this.#me;
    this.#me = undefined;
    const me = await this.#loadMe();
    recordEvent("shell", { identity: me?.kind ?? "unknown" });
    await this.#frame.identityChanged();
    const room = this.#followed();
    if (room !== undefined) {
      const sameUser = before?.kind === "user" && me?.kind === "user" && before.id === me.id;
      if (room.state()?.room.alone === true && !sameUser) this.#stopRoom();
      else await room.reidentify();
    }
    void this.#ensureRoom();
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
    void this.#show(url, 0).then(() => this.#ensureRoom());
  }

  /** The room the frame follows: the room view's room, shown or kept in the background. */
  #followed(): ShellRoom | undefined {
    if (this.#kept?.room !== undefined) return this.#kept.room;
    return this.#view?.route.view === "room" ? this.#view.room : undefined;
  }

  /**
   * Leave (right panel): a user chooses fork or restore and then plays alone; guests and anonymous
   * visitors just stop the room here. The alone room has nothing to leave.
   */
  async leave(): Promise<void> {
    const room = this.#followed();
    if (room === undefined) return;
    const me = await this.#loadMe();
    const kind = leaveKind(me, room.state()?.room.alone === true);
    if (kind === "none") return;
    if (kind === "ask") {
      const choice = await askLeaveChoice(leaveOffer(room.name(), false));
      if (choice === null) return;
      try {
        await api("POST", "/api/me/play-session/leave", { choice });
      } catch (e) {
        // Already alone (left on another device): stopping here is all that is left to do.
        if (!(e instanceof ApiError && e.code === "not_in_room")) {
          window.alert("Couldn't leave the room: " + message(e));
          return;
        }
      }
      if (this.#followed() !== room) return; // something else happened meanwhile
    }
    this.#stopRoom();
    void this.#ensureRoom();
  }

  /** Takes the room view away, shown or playing in the background. */
  #stopRoom(): void {
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

  async #loadMe(): Promise<Me | undefined> {
    if (this.#me?.kind === "user") return this.#me;
    try {
      this.#me = await loadMe();
    } catch {
      // Offline or the server restarting: try again with the next navigation.
    }
    return this.#me;
  }

  /**
   * A signed-in user with no room view open: plays the play session's room (the room they are in,
   * otherwise their alone room) in the background, after asking fork or restore if their room was
   * deleted.
   */
  async #ensureRoom(): Promise<void> {
    if (this.#ensuring || this.#roomOpen()) return;
    this.#ensuring = true;
    try {
      const me = await this.#loadMe();
      if (!canBeAlone(me)) return;
      let session = await api<PlaySession>("GET", "/api/me/play-session");
      if (needsLeaveChoice(session)) {
        const choice = await askLeaveChoice(leaveOffer(session.deletedRoom?.name ?? "", true));
        session = await api<PlaySession>("POST", "/api/me/play-session/leave", { choice: choice ?? "restore" });
      }
      if (this.#roomOpen()) return; // a room view came meanwhile
      await this.#startBackground(roomPath(sessionRoom(session)));
    } catch (e) {
      recordEvent("shell", { sessionFailed: String(e) });
    } finally {
      this.#ensuring = false;
    }
  }

  /** Whether a room view is open (shown, or in the background). */
  #roomOpen(): boolean {
    return this.#kept !== undefined || this.#view?.route.view === "room";
  }

  /** The room view stopped for good (the socket's reason): a deleted room asks fork or restore. */
  #gone(m: Mounted | undefined, reason: string): void {
    if (reason !== "room_deleted") return;
    if (m === undefined || !canBeAlone(this.#me)) return;
    if (m === this.#kept) {
      this.#kept = undefined;
      this.#unmount(m);
      this.update();
      void this.#ensureRoom();
    } else if (m === this.#view) {
      // The room view says what happened; the alone state starts once another view is shown.
      void this.#ensureRoomBeside();
    }
  }

  /** The dead room view stays shown with its message: ask now, play once the user moves on. */
  async #ensureRoomBeside(): Promise<void> {
    if (this.#ensuring) return;
    this.#ensuring = true;
    try {
      const session = await api<PlaySession>("GET", "/api/me/play-session");
      if (!needsLeaveChoice(session)) return;
      const choice = await askLeaveChoice(leaveOffer(session.deletedRoom?.name ?? "", true));
      await api("POST", "/api/me/play-session/leave", { choice: choice ?? "restore" });
    } catch (e) {
      recordEvent("shell", { sessionFailed: String(e) });
    } finally {
      this.#ensuring = false;
    }
    // Moved on while the question was up: play the alone state now.
    if (this.#view?.route.view !== "room") void this.#ensureRoom();
  }

  /** Starts the room view for `path` hidden, as the room playing in the background. */
  async #startBackground(path: string): Promise<void> {
    const route = routeOf(path);
    if (route.kind !== "view" || route.view !== "room") return;
    const info = VIEWS.room;
    const template = document.getElementById(info.template);
    if (!(template instanceof HTMLTemplateElement) || info.module === undefined) return;
    const el = document.createElement("div");
    el.className = "view view-room";
    el.tabIndex = -1;
    el.hidden = true;
    el.append(template.content.cloneNode(true));
    const controller = new AbortController();
    const mount = ++this.#mounts;
    const m: Mounted = { route, key: path, el, controller, mount };
    this.#kept = m;
    this.#app.append(el);
    registerViewStart(mount, { path, search: "", signal: controller.signal });
    recordEvent("shell", { background: path });
    const moduleUrl = new URL("/js/" + info.module + ".js", location.href);
    moduleUrl.searchParams.set("mount", String(mount));
    try {
      await import(moduleUrl.href);
    } catch (e) {
      recordEvent("shell", { importFailed: moduleUrl.pathname, error: String(e) });
      if (this.#kept === m) {
        this.#kept = undefined;
        this.#unmount(m);
      }
    }
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
    // The room view being started (shown, or in the background) is the one whose module calls this.
    const m = [this.#view, this.#kept].find((v) => v !== undefined && v.route.view === "room" && v.room === undefined);
    if (m !== undefined) m.room = room;
    return {
      get away(): boolean {
        return m !== undefined && m.el.hidden === true;
      },
      update: () => this.update(),
      gone: (reason) => this.#gone(m, reason),
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
    void this.#show(url, 0).then(() => this.#ensureRoom());
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
    void this.#show(new URL(location.href), scrollY).then(() => this.#ensureRoom());
  }

  #saveScroll(): void {
    const state = (history.state ?? {}) as HistoryState;
    this.#replaceState({ ...state, scrollY: contentScrollTop() });
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
    // The room view may hold the frame's video panel: it goes back to the right panel first.
    this.#frame.release(m.el);
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
      this.#frame.release(v.el);
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
      registerViewStart(mount, { path: url.pathname, search: url.search, signal: controller.signal });
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
    scrollContentTo(scrollY);
    // Views fill in after their data loads: try once more when that's likely done.
    if (scrollY > 0) window.setTimeout(() => scrollContentTo(scrollY), 300);
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

/**
 * Asks fork or restore (docs/ui.md#alone-and-in-rooms). Resolves with the choice, or null when
 * cancelled (only when `offer.cancellable`).
 */
function askLeaveChoice(offer: LeaveOffer): Promise<LeaveChoice | null> {
  return new Promise((resolve) => {
    const fork = h("button", { type: "button", text: offer.fork,
      title: "Your own queue gets the room's current song, position and queue" });
    const restore = h("button", { type: "button", text: offer.restore,
      title: "Your own song and queue from before you joined come back" });
    const cancel = offer.cancellable ? h("button", { type: "button", text: "Cancel" }) : null;
    const form = h("div", { class: "form", role: "dialog", "aria-modal": "true", "aria-label": "Leave the room" },
      h("p", { text: offer.question }),
      h("div", { class: "row" }, fork, restore, cancel));
    const overlay = h("div", { class: "classify-prompt leave-prompt" }, form);
    const close = (v: LeaveChoice | null): void => {
      overlay.remove();
      resolve(v);
    };
    fork.addEventListener("click", () => close("fork"));
    restore.addEventListener("click", () => close("restore"));
    cancel?.addEventListener("click", () => close(null));
    if (cancel !== null) overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(null); });
    document.body.append(overlay);
    fork.focus();
  });
}
