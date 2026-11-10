// Who is using this page (`GET /api/me`), the site navigation, and the redirects every page
// that needs an account goes through: to the login page when nobody is logged in, and to the
// change-password page while the password is a temporary one an admin set.
import { api } from "./api.js";
import { pageSignal, rememberPlaying, showStoppedNote } from "./nav.js";

/** `GET /api/me` for a logged-in user. */
export interface MeUser {
  kind: "user";
  id: string;
  username: string;
  displayName: string;
  name: string;
  roles: string[];
  mustChangePassword: boolean;
  isGuest: false;
  permissions: string[];
}

/** `GET /api/me` for a guest. */
export interface MeGuest {
  kind: "guest";
  id: string;
  name: string;
  isGuest: true;
  nameChosen: boolean;
  customNames: boolean;
}

/** `GET /api/me` with no identity. */
export interface MeAnonymous {
  kind: "anonymous";
  guestsEnabled: boolean;
  customNames: boolean;
}

export type Me = MeUser | MeGuest | MeAnonymous;

export const LOGIN_PAGE = "/login.html";
export const REGISTER_PAGE = "/register.html";
export const PASSWORD_PAGE = "/password.html";
export const ACCOUNT_PAGE = "/account/";
export const ADMIN_PAGE = "/admin/";
export const LIBRARY_PAGE = "/library/";
export const ENCODES_PAGE = "/encodes/";
export const PLAYLISTS_PAGE = "/playlists/";
export const ROOMS_PAGE = "/rooms/";

export async function loadMe(): Promise<Me> {
  return api<Me>("GET", "/api/me");
}

/** Whether `me` may open the admin pages: any `admin.*` permission. */
export function isAdmin(me: Me): boolean {
  return me.kind === "user" && me.permissions.some((p) => p.startsWith("admin."));
}

/** This page's path, query and hash: where to come back to after logging in. */
export function here(): string {
  return location.pathname + location.search + location.hash;
}

/**
 * The `next` query parameter, if it is a path on this site (`/…`, not `//…` or `/\…`, which
 * browsers treat as another host), otherwise `fallback`.
 */
export function nextPath(fallback = "/"): string {
  const next = new URLSearchParams(location.search).get("next");
  if (next === null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return fallback;
  }
  return next;
}

/** `page?next=<path>`. */
export function withNext(page: string, next: string): string {
  return next === "/" ? page : page + "?next=" + encodeURIComponent(next);
}

/**
 * Sends a user with a temporary password to the change-password page. Returns false (after
 * starting the redirect) when it did.
 */
export function checkPasswordChange(me: Me): boolean {
  if (me.kind === "user" && me.mustChangePassword) {
    location.replace(withNext(PASSWORD_PAGE, here()));
    return false;
  }
  return true;
}

/**
 * For pages that need an account: returns the user, or redirects (to the login page, or to the
 * change-password page) and returns null.
 */
export async function requireUser(): Promise<MeUser | null> {
  const me = await loadMe();
  if (me.kind !== "user") {
    location.replace(withNext(LOGIN_PAGE, here()));
    return null;
  }
  if (!checkPasswordChange(me)) {
    return null;
  }
  return me;
}

/** `POST /api/auth/logout`, then back to the front page. */
export async function logout(): Promise<void> {
  await api("POST", "/api/auth/logout");
  // Logging out stops playback (a full page load); no "stopped" note for that.
  rememberPlaying(null);
  location.assign("/");
}

function link(href: string, text: string): HTMLAnchorElement {
  const a = document.createElement("a");
  a.href = href;
  a.textContent = text;
  if (location.pathname === href) {
    a.setAttribute("aria-current", "page");
  }
  return a;
}

/**
 * Fills `<nav id="site-nav">`: the front page and Rooms (for everyone), then Library / Playlists / Encodes / Account /
 * Administration / Log out for a user, or Log in / Register otherwise. Names are set as text, never parsed as HTML.
 */
export function renderNav(me: Me): void {
  const nav = document.getElementById("site-nav");
  if (nav === null) return;
  const items: Node[] = [link("/", "TogetherTunes"), link(ROOMS_PAGE, "Rooms")];
  if (me.kind === "user") {
    items.push(link(LIBRARY_PAGE, "Library"), link(PLAYLISTS_PAGE, "Playlists"), link(ENCODES_PAGE, "Encodes"),
      link(ACCOUNT_PAGE, "Account"));
    if (isAdmin(me)) {
      items.push(link(ADMIN_PAGE, "Administration"));
    }
    const who = document.createElement("span");
    who.className = "muted nav-who";
    who.textContent = me.name;
    const out = document.createElement("button");
    out.type = "button";
    out.className = "link";
    out.textContent = "Log out";
    out.addEventListener("click", () => {
      out.disabled = true;
      logout().catch(() => { out.disabled = false; });
    });
    items.push(who, out);
  } else {
    items.push(link(withNext(LOGIN_PAGE, here()), "Log in"), link(REGISTER_PAGE, "Register"));
  }
  nav.replaceChildren(...items);
  // After a full load that stopped a room's playback: say so (nav.ts).
  showStoppedNote();
}

/**
 * Runs `start` once the DOM is ready. A page the room page's shell already took away again while
 * it was starting (nav.ts `pageSignal()`) fails quietly: its elements are gone.
 */
export function onReady(start: () => void | Promise<void>): void {
  const signal = pageSignal();
  const run = (): void => {
    Promise.resolve().then(start).catch((e: unknown) => {
      if (!signal.aborted) throw e;
    });
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
}

/** The text of an error, for showing on the page. */
export function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** `document.getElementById`, failing when the page lacks it. */
export function element<T extends HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (el === null) {
    throw new Error("Missing #" + id);
  }
  return el as T;
}

/** A form field's value as a string. */
export function formValue(form: HTMLFormElement, name: string): string {
  return String(new FormData(form).get(name) ?? "");
}

/**
 * Handles a form's submit: disables its buttons meanwhile, clears `errorEl` first and shows a
 * thrown error's message in it.
 */
export function onSubmit(form: HTMLFormElement, errorEl: HTMLElement, action: () => Promise<void>): void {
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    errorEl.textContent = "";
    const buttons = Array.from(form.querySelectorAll("button"));
    buttons.forEach((b) => { b.disabled = true; });
    action().catch((e: unknown) => {
      errorEl.textContent = message(e);
    }).finally(() => {
      buttons.forEach((b) => { b.disabled = false; });
    });
  });
}
