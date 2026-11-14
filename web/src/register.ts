// The registration view (/register). Whether registration is open is the server's call
// (`registration.mode`); when it isn't, the API's error is shown.
import { api, ApiError, setupNeeded } from "./api.js";
import {
  element, formValue, LOGIN_PAGE, loadMe, nextPath, offerGuestMerge, onReady, onSubmit, renderNav, SETUP_PAGE,
  withNext,
} from "./session.js";
import { identityChanged, navigate } from "./nav.js";

async function start(): Promise<void> {
  const form = element<HTMLFormElement>("register-form");
  const error = element<HTMLParagraphElement>("register-error");
  const status = element<HTMLParagraphElement>("register-status");
  const next = nextPath();
  element<HTMLAnchorElement>("login-link").href = withNext(LOGIN_PAGE, next);
  // An invite link (/register?invite=<code>) fills the code in.
  const invite = new URLSearchParams(location.search).get("invite");
  if (invite) {
    (form.elements.namedItem("invite") as HTMLInputElement).value = invite;
  }

  // Handled from the start (before the checks below), so an early submit never goes to the browser.
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
        navigate(SETUP_PAGE, true);
        return;
      }
      throw e;
    }
    // Logged in as the new account without a reload (docs/ui.md#single-page-app).
    await identityChanged();
    await offerGuestMerge();
    navigate(next);
  });

  try {
    if (await setupNeeded()) {
      navigate(SETUP_PAGE, true);
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
}

onReady(start);
