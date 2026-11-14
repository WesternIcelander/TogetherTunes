// The registration view (/register). Whether registration is open is the server's call
// (`registration.mode`); when it isn't, the API's error is shown.
import { api, ApiError, setupNeeded } from "./api.js";
import {
  formValue, LOGIN_PAGE, loadMe, nextPath, offerGuestMerge, onReady, onSubmit, renderNav, SETUP_PAGE,
  withNext,
} from "./session.js";
import { identityChanged, navigate, viewScope } from "./nav.js";

/** This view's start (before any await): its signal, and its elements only. */
const VIEW = viewScope(import.meta.url);
const element = VIEW.element;

async function start(): Promise<void> {
  const form = element<HTMLFormElement>("register-form");
  const error = element<HTMLParagraphElement>("register-error");
  const status = element<HTMLParagraphElement>("register-status");
  const next = nextPath();
  element<HTMLAnchorElement>("login-link").href = withNext(LOGIN_PAGE, next);
  // An invite link (/register?invite=<code>) fills the code in.
  const invite = new URLSearchParams(VIEW.search).get("invite");
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
    const needed = await setupNeeded();
    if (VIEW.signal.aborted) return;
    if (needed) {
      navigate(SETUP_PAGE, true);
      return;
    }
    const me = await loadMe();
    if (VIEW.signal.aborted) return;
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
