// The change-password view (/password), `POST /api/me/password`. Users with a temporary
// password (`mustChangePassword`, set by an admin) are sent here before any other page.
import { api } from "./api.js";
import {
  formValue, LOGIN_PAGE, loadMe, message, nextPath, onReady, onSubmit, renderNav, withNext, type Me,
} from "./session.js";
import { navigate, viewScope } from "./nav.js";

/** This view's start (before any await): its signal, and its elements only. */
const VIEW = viewScope(import.meta.url);
const element = VIEW.element;

async function start(): Promise<void> {
  const status = element<HTMLParagraphElement>("password-status");
  const form = element<HTMLFormElement>("password-form");
  const error = element<HTMLParagraphElement>("password-error");
  const next = nextPath();

  let me: Me;
  try {
    me = await loadMe();
  } catch (e) {
    status.textContent = message(e);
    return;
  }
  if (VIEW.signal.aborted) return;
  if (me.kind !== "user") {
    navigate(withNext(LOGIN_PAGE, VIEW.path + VIEW.search), true);
    return;
  }
  // While the password is temporary, the navigation would only lead back here.
  if (!me.mustChangePassword) {
    renderNav(me);
  }
  element("password-temporary").hidden = !me.mustChangePassword;
  (form.elements.namedItem("username") as HTMLInputElement).value = me.username;
  status.hidden = true;
  form.hidden = false;

  onSubmit(form, error, async () => {
    if (formValue(form, "newPassword") !== formValue(form, "newPassword2")) {
      throw new Error("The new passwords don't match.");
    }
    await api("POST", "/api/me/password", {
      currentPassword: formValue(form, "currentPassword"),
      newPassword: formValue(form, "newPassword"),
    });
    navigate(next);
  });
}

onReady(start);
