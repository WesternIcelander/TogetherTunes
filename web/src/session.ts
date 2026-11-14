// Who is using this page (`GET /api/me`), the site navigation, and the redirects every page
// that needs an account goes through: to the login page when nobody is logged in, and to the
// change-password page while the password is a temporary one an admin set.
import { api } from "./api.js";
import { mergeableGuest, mergeGuest, mergeQuestion } from "./guestmerge.js";
import { navigate, pageSignal, rememberPlaying, showStoppedNote } from "./nav.js";

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

export const LOGIN_PAGE = "/login";
export const REGISTER_PAGE = "/register";
export const PASSWORD_PAGE = "/password";
export const SETUP_PAGE = "/setup";
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
    navigate(withNext(PASSWORD_PAGE, here()), true);
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
    navigate(withNext(LOGIN_PAGE, here()), true);
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
  // Logging out reloads the document (docs/ui.md#single-page-app), which stops playback; no
  // "stopped" note for that.
  rememberPlaying(null);
  location.assign("/");
}

function link(href: string, text: string): HTMLAnchorElement {
  const a = document.createElement("a");
  a.href = href;
  a.textContent = text;
  if (location.pathname === href || location.pathname + "/" === href) {
    a.setAttribute("aria-current", "page");
  }
  return a;
}

/**
 * Fills `<nav id="site-nav">`, the header's account part (docs/ui.md#layout-desktop): for a user an
 * account button (their name) opening a menu with Account, Library (the full library page with
 * uploads), Playlists, Rooms, Encodes, Administration (any `admin.*` permission) and Log out; for
 * everyone else Rooms, Log in and Register. Names are set as text, never parsed as HTML.
 */
export function renderNav(me: Me): void {
  const nav = document.getElementById("site-nav");
  if (nav === null) return;
  if (me.kind === "user") {
    const menu = document.createElement("details");
    menu.className = "account-menu";
    const summary = document.createElement("summary");
    summary.className = "account-button";
    summary.title = "Account";
    const icon = document.createElement("span");
    icon.className = "account-icon";
    icon.setAttribute("aria-hidden", "true");
    icon.textContent = "👤";
    const who = document.createElement("span");
    who.className = "nav-who";
    who.textContent = me.name;
    summary.append(icon, who);
    const list = document.createElement("div");
    list.className = "menu account-items";
    list.setAttribute("role", "menu");
    const items: Node[] = [link(ACCOUNT_PAGE, "Account"), link(LIBRARY_PAGE, "Manage library"),
      link(PLAYLISTS_PAGE, "Playlists"), link(ROOMS_PAGE, "Rooms"), link(ENCODES_PAGE, "Encodes")];
    if (isAdmin(me)) {
      items.push(link(ADMIN_PAGE, "Administration"));
    }
    const out = document.createElement("button");
    out.type = "button";
    out.className = "link";
    out.textContent = "Log out";
    out.addEventListener("click", () => {
      out.disabled = true;
      logout().catch(() => { out.disabled = false; });
    });
    items.push(out);
    list.append(...items);
    // A link chosen from the menu closes it (the view changes in place, the header stays).
    list.addEventListener("click", (ev) => {
      if ((ev.target as Element).closest("a") !== null) menu.open = false;
    });
    menu.append(summary, list);
    nav.replaceChildren(menu);
  } else {
    nav.replaceChildren(link(ROOMS_PAGE, "Rooms"), link(withNext(LOGIN_PAGE, here()), "Log in"),
      link(REGISTER_PAGE, "Register"));
  }
  // After a full load that stopped a room's playback: say so (nav.ts).
  showStoppedNote();
}

/**
 * Runs `start` once the DOM is ready. A view the shell already took away again while it was
 * starting (nav.ts `pageSignal()`) fails quietly: its elements are gone.
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

/**
 * After logging in or registering: if this browser was a guest, asks (a confirm dialog) whether to
 * move that guest's songs and votes to the account, and does it. Never throws: the account page
 * offers it again.
 */
export async function offerGuestMerge(): Promise<void> {
  try {
    const guest = await mergeableGuest();
    if (guest !== undefined && window.confirm(mergeQuestion(guest))) await mergeGuest();
  } catch {
    // offered again on the account page
  }
}
