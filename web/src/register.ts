// The registration page (/register.html). Whether registration is open is the server's call
// (`registration.mode`); when it isn't, the API's error is shown.
import { api, ApiError, setupNeeded } from "./api.js";
import {
  element, formValue, LOGIN_PAGE, loadMe, nextPath, offerGuestMerge, onReady, onSubmit, renderNav, withNext,
} from "./session.js";

async function start(): Promise<void> {
  const form = element<HTMLFormElement>("register-form");
  const error = element<HTMLParagraphElement>("register-error");
  const status = element<HTMLParagraphElement>("register-status");
  const next = nextPath();
  element<HTMLAnchorElement>("login-link").href = withNext(LOGIN_PAGE, next);
  // An invite link (/register.html?invite=<code>) fills the code in.
  const invite = new URLSearchParams(location.search).get("invite");
  if (invite) {
    (form.elements.namedItem("invite") as HTMLInputElement).value = invite;
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
      status.textContent = `You are logged in as ${me.username}. Registering logs you in as the new account.`;
    }
  } catch {
    // The form still works; a real problem shows when it is submitted.
  }

  onSubmit(form, error, async () => {
    if (formValue(form, "password") !== formValue(form, "password2")) {
      throw new Error("The passwords don't match.");
    }
    try {
      await api("POST", "/api/auth/register", {
        username: formValue(form, "username"),
        displayName: formValue(form, "displayName"),
        password: formValue(form, "password"),
        invite: formValue(form, "invite").trim() || undefined,
      });
    } catch (e) {
      if (e instanceof ApiError && e.code === "setup_required") {
        location.replace("/setup.html");
        return;
      }
      throw e;
    }
    await offerGuestMerge();
    location.assign(next);
  });
}

onReady(start);
