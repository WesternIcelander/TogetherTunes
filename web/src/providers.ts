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
