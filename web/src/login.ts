// The login page (/login.html). After logging in, a user whose password is a temporary one
// (set by an admin) goes to the change-password page first; everyone else to `?next=` or `/`.
import { api, setupNeeded } from "./api.js";
import {
  element, formValue, loadMe, nextPath, offerGuestMerge, onReady, onSubmit, PASSWORD_PAGE, REGISTER_PAGE, renderNav,
  withNext,
} from "./session.js";
import { providerErrorMessage, providerStartUrl, type ProviderButton } from "./providers.js";

interface LoginResponse {
  user: { id: string; username: string; displayName: string; roles: string[]; mustChangePassword: boolean };
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
    if (!result.user.mustChangePassword) await offerGuestMerge();
    location.assign(afterLogin(result.user.mustChangePassword, next));
  });
}

onReady(start);
