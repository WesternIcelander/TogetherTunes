// The first-run setup view (/setup): creates the first account, which becomes admin (and logs it in,
// picked up in place: nav.ts identityChanged).
import { api, ApiError, setupNeeded } from "./api.js";
import { onReady } from "./session.js";
import { identityChanged, viewScope } from "./nav.js";

interface SetupResponse {
  user: { id: string; username: string; displayName: string; roles: string[] };
}

/** This view's start (before any await): its signal, and its elements only. */
const VIEW = viewScope(import.meta.url);
const element = VIEW.element;

async function start(): Promise<void> {
  const status = element<HTMLParagraphElement>("setup-status");
  const form = element<HTMLFormElement>("setup-form");
  const error = element<HTMLParagraphElement>("setup-error");

  let needed: boolean;
  try {
    needed = await setupNeeded();
  } catch (e) {
    status.textContent = e instanceof Error ? e.message : String(e);
    return;
  }
  if (VIEW.signal.aborted) return;
  if (!needed) {
    status.innerHTML = "";
    status.append("Setup is already complete. ", link("/", "Go to TogetherTunes"));
    return;
  }
  status.hidden = true;
  form.hidden = false;

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    error.textContent = "";
    const data = new FormData(form);
    const value = (name: string): string => String(data.get(name) ?? "");
    if (value("password") !== value("password2")) {
      error.textContent = "The passwords don't match.";
      return;
    }
    const button = form.querySelector("button");
    if (button !== null) button.disabled = true;
    try {
      const result = await api<SetupResponse>("POST", "/api/setup", {
        username: value("username"),
        displayName: value("displayName"),
        password: value("password"),
      });
      form.hidden = true;
      status.hidden = false;
      status.innerHTML = "";
      status.append(`Created the administrator account "${result.user.username}". `, link("/", "Continue"));
      // Setup logs the new administrator in: the frame shows them without a reload.
      await identityChanged();
    } catch (e) {
      if (e instanceof ApiError && e.code === "setup_complete") {
        form.hidden = true;
        status.hidden = false;
        status.innerHTML = "";
        status.append("Someone else completed setup first. ", link("/", "Go to TogetherTunes"));
        return;
      }
      error.textContent = e instanceof Error ? e.message : String(e);
    } finally {
      if (button !== null) button.disabled = false;
    }
  });
}

function link(href: string, text: string): HTMLAnchorElement {
  const a = document.createElement("a");
  a.href = href;
  a.textContent = text;
  return a;
}

onReady(start);
