// The login page (/login.html). After logging in, a user whose password is a temporary one
// (set by an admin) goes to the change-password page first; everyone else to `?next=` or `/`.
import { api, setupNeeded } from "./api.js";
import {
  element, formValue, loadMe, nextPath, onReady, onSubmit, PASSWORD_PAGE, REGISTER_PAGE, renderNav, withNext,
} from "./session.js";

interface LoginResponse {
  user: { id: string; username: string; displayName: string; roles: string[]; mustChangePassword: boolean };
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
