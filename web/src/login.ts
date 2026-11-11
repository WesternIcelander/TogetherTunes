// The login page (/login.html). After logging in, a user whose password is a temporary one
// (set by an admin) goes to the change-password page first; everyone else to `?next=` or `/`.
import { api, setupNeeded } from "./api.js";
import {
  element, formValue, loadMe, nextPath, onReady, onSubmit, PASSWORD_PAGE, REGISTER_PAGE, renderNav, withNext,
} from "./session.js";

interface LoginResponse {
  user: { id: string; username: string; displayName: string; roles: string[]; mustChangePassword: boolean };
}

interface ProviderButton {
  slug: string;
  displayName: string;
  allowSignup: boolean;
  startUrl: string;
}

/** What `?error=<code>` from a failed provider login (`/auth/oidc/{slug}/callback`) means. */
export function providerErrorMessage(code: string): string {
  switch (code) {
    case "provider_unavailable": return "That login provider isn't available.";
    case "provider_unreachable": return "The login provider can't be reached right now. Try again later.";
    case "provider_unsupported": return "That login provider isn't set up in a way this server supports.";
    case "provider_denied": return "Login was cancelled at the provider.";
    case "login_expired": return "The login took too long or was already used. Please try again.";
    case "login_browser_mismatch": return "The login has to finish in the browser it was started in. Please try again.";
    case "not_authenticated": return "Log in first.";
    default: return "Logging in with the provider failed. Please try again.";
  }
}

/** The provider's start URL, returning to `next` afterwards. */
export function providerStartUrl(startUrl: string, next: string): string {
  return next === "/" ? startUrl : startUrl + (startUrl.indexOf("?") < 0 ? "?" : "&") + "next=" + encodeURIComponent(next);
}

async function showProviders(next: string): Promise<void> {
  const result = await api<{ items: ProviderButton[] }>("GET", "/api/auth/providers");
  const list = element<HTMLUListElement>("login-provider-list");
  for (const p of result.items) {
    const li = document.createElement("li");
    const a = document.createElement("a");
    a.href = providerStartUrl(p.startUrl, next);
    a.textContent = p.displayName;
    li.appendChild(a);
    list.appendChild(li);
  }
  element<HTMLDivElement>("login-providers").hidden = result.items.length === 0;
}

/** Where to go once logged in. */
export function afterLogin(mustChangePassword: boolean, next: string): string {
  return mustChangePassword ? withNext(PASSWORD_PAGE, next) : next;
}

async function start(): Promise<void> {
  const form = element<HTMLFormElement>("login-form");
  const error = element<HTMLParagraphElement>("login-error");
  const status = element<HTMLParagraphElement>("login-status");
  const next = nextPath();
  element<HTMLAnchorElement>("register-link").href = withNext(REGISTER_PAGE, next);
  showProviders(next).catch(() => undefined);
  const providerError = new URLSearchParams(location.search).get("error");
  if (providerError !== null) {
    const p = element<HTMLParagraphElement>("provider-error");
    p.textContent = providerErrorMessage(providerError);
    p.hidden = false;
  }

  try {
    if (await setupNeeded()) {
      location.replace("/setup.html");
      return;
    }
    const me = await loadMe();
    renderNav(me);
    if (me.kind === "user") {
      status.hidden = false;
      status.textContent = `You are logged in as ${me.username}. Logging in again switches account.`;
    }
  } catch {
    // The form still works; a real problem shows when it is submitted.
  }

  onSubmit(form, error, async () => {
    const result = await api<LoginResponse>("POST", "/api/auth/login", {
      username: formValue(form, "username"),
      password: formValue(form, "password"),
    });
    location.assign(afterLogin(result.user.mustChangePassword, next));
  });
}

onReady(start);
