// Login providers (OpenID Connect / OAuth): DOM-free helpers shared by the login and account pages.

/** A login button, as `/api/auth/providers` lists them. */
export interface ProviderButton {
  slug: string;
  displayName: string;
  allowSignup: boolean;
  startUrl: string;
}

/** A linked login, as `/api/me/identities` lists them. */
export interface LinkedIdentity {
  id: string;
  provider: { slug: string; displayName: string; enabled: boolean };
  email?: string;
  createdAt: number;
  lastLoginAt?: number;
}

/** What `?error=<code>` from a failed provider login or link (`/auth/oidc/{slug}/callback`) means. */
export function providerErrorMessage(code: string): string {
  switch (code) {
    case "provider_unavailable": return "That login provider isn't available.";
    case "provider_unreachable": return "The login provider can't be reached right now. Try again later.";
    case "provider_unsupported": return "That login provider isn't set up in a way this server supports.";
    case "provider_denied": return "Login was cancelled at the provider.";
    case "login_expired": return "The login took too long or was already used. Please try again.";
    case "userinfo_failed": return "The login provider didn't say who you are. Please try again later.";
    case "login_browser_mismatch": return "The login has to finish in the browser it was started in. Please try again.";
    case "not_authenticated": return "Log in first.";
    case "link_session_changed": return "You were logged out or switched accounts while linking. Please try again.";
    case "account_disabled": return "This account is disabled.";
    case "signup_disabled":
      return "No account is linked to that login, and signing up with it is off. "
        + "Log in another way and link it from your account page.";
    case "setup_required": return "The server hasn't been set up yet.";
    case "identity_in_use": return "That login is already linked to another account.";
    case "provider_already_linked":
      return "Your account already has a login with that provider. Remove it first to link a different one.";
    default: return "Logging in with the provider failed. Please try again.";
  }
}

/** The provider's start URL, returning to `next` afterwards. */
export function providerStartUrl(startUrl: string, next: string): string {
  return next === "/" ? startUrl : startUrl + (startUrl.indexOf("?") < 0 ? "?" : "&") + "next=" + encodeURIComponent(next);
}

/** The provider's start URL for linking it to the logged-in account. */
export function providerLinkUrl(startUrl: string): string {
  return startUrl + (startUrl.indexOf("?") < 0 ? "?" : "&") + "intent=link";
}

// ------------------------------------------------------------------ popup logins
// (docs/ui.md#single-page-app) A provider login runs in a popup window, so the page (and a room
// playing in it) stays. The popup ends at /auth/popup-done (`?error=<code>` on failure), whose
// script (popupdone.ts) tells this page with a same-origin `postMessage` and on a `BroadcastChannel`
// (for when the provider's pages cut the popup's opener link), then closes.

/** The popup window's name (reused: a second click brings the same window back). */
export const LOGIN_POPUP_NAME = "togethertunes-login";

/** The popup window's size. */
export const LOGIN_POPUP_FEATURES = "popup=yes,width=520,height=700";

/** The `BroadcastChannel` the popup also reports on. */
export const LOGIN_CHANNEL = "togethertunes.login";

/** The message's `type`. */
export const LOGIN_MESSAGE_TYPE = "togethertunes.login";

/** How a popup login ended. */
export interface LoginResult {
  ok: boolean;
  /** The failure's code (providerErrorMessage) when not ok. */
  error?: string;
  /** Which popup's report it is: the same one comes by postMessage and by the channel. */
  id?: string;
}

/** The provider's start URL for a login in a popup (the server ends it at /auth/popup-done). */
export function providerPopupUrl(startUrl: string): string {
  return startUrl + (startUrl.indexOf("?") < 0 ? "?" : "&") + "popup=1";
}

/** What the popup-done page reports, from its query string (`?error=<code>` on failure). */
export function popupResult(search: string): LoginResult {
  const error = new URLSearchParams(search).get("error");
  return error === null ? { ok: true } : { ok: false, error: error === "" ? "provider_error" : error };
}

/** The message the popup sends (`id`: a random tag, the same on both ways it is sent). */
export function loginMessage(result: LoginResult, id: string): Record<string, unknown> {
  return result.ok ? { type: LOGIN_MESSAGE_TYPE, id, ok: true } : { type: LOGIN_MESSAGE_TYPE, id, ok: false, error: result.error };
}

/**
 * A received message as a login result, or undefined when it isn't one. `origin` is the message
 * event's origin (undefined for a BroadcastChannel, which is same-origin by design) and must be
 * `ownOrigin`.
 */
export function parseLoginMessage(data: unknown, origin: string | undefined, ownOrigin: string): LoginResult | undefined {
  if (origin !== undefined && origin !== ownOrigin) return undefined;
  if (data === null || typeof data !== "object") return undefined;
  const d = data as Record<string, unknown>;
  if (d["type"] !== LOGIN_MESSAGE_TYPE) return undefined;
  const id = typeof d["id"] === "string" ? d["id"].slice(0, 64) : "";
  if (d["ok"] === true) return { ok: true, id };
  const error = typeof d["error"] === "string" && /^[a-z0-9_]{1,64}$/.test(d["error"]) ? d["error"] : "provider_error";
  return { ok: false, error, id };
}
