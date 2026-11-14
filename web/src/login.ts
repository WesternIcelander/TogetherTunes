// The login view (/login). Logging in never reloads the document (docs/ui.md#single-page-app): after
// it the page re-identifies itself (nav.ts `identityChanged`: the frame, a playing room's socket),
// offers the guest merge in place, and goes on to `?next=` or `/`. A user whose password is a
// temporary one (set by an admin) goes to the change-password view first.
//
// Login providers (OpenID Connect / OAuth) run in a popup window opened right from the click (so
// popup blockers allow it); the popup ends at /auth/popup-done, which reports back (popupdone.ts).
// A blocked popup says so here and offers the full-page login, which does reload.
import { api, setupNeeded } from "./api.js";
import {
  element, formValue, loadMe, nextPath, offerGuestMerge, onReady, onSubmit, PASSWORD_PAGE, REGISTER_PAGE, renderNav,
  SETUP_PAGE, withNext,
} from "./session.js";
import { identityChanged, navigate, pageSignal } from "./nav.js";
import {
  LOGIN_CHANNEL, LOGIN_POPUP_FEATURES, LOGIN_POPUP_NAME, parseLoginMessage, providerErrorMessage, providerPopupUrl,
  providerStartUrl, type LoginResult, type ProviderButton,
} from "./providers.js";

interface LoginResponse {
  user: { id: string; username: string; displayName: string; roles: string[]; mustChangePassword: boolean };
}

/** Where to go once logged in. */
export function afterLogin(mustChangePassword: boolean, next: string): string {
  return mustChangePassword ? withNext(PASSWORD_PAGE, next) : next;
}

/** Logged in (any way): re-identify the page, offer the guest merge, go on. */
async function loggedIn(mustChangePassword: boolean, next: string): Promise<void> {
  await identityChanged();
  if (!mustChangePassword) await offerGuestMerge();
  navigate(afterLogin(mustChangePassword, next));
}

/** The provider login running in a popup, if any: waits for its result. */
class PopupLogin {
  readonly #signal: AbortSignal;
  readonly #onResult: (r: LoginResult) => void;
  /** Reports already handled (each popup reports twice: postMessage and the channel). */
  readonly #seen = new Set<string>();

  constructor(onResult: (r: LoginResult) => void) {
    const signal = pageSignal();
    this.#signal = signal;
    this.#onResult = onResult;
    window.addEventListener("message", (ev) => {
      const r = parseLoginMessage(ev.data, ev.origin, location.origin);
      if (r !== undefined) this.#result(r);
    }, { signal: pageSignal() });
    try {
      if (typeof BroadcastChannel !== "undefined") {
        const channel = new BroadcastChannel(LOGIN_CHANNEL);
        channel.onmessage = (ev) => {
          const r = parseLoginMessage(ev.data, undefined, location.origin);
          if (r !== undefined) this.#result(r);
        };
        signal.addEventListener("abort", () => channel.close());
      }
    } catch {
      // Safari before 15.4: postMessage only.
    }
  }

  #result(r: LoginResult): void {
    if (this.#signal.aborted) return;
    const id = r.id ?? "";
    if (id !== "") {
      if (this.#seen.has(id)) return;
      this.#seen.add(id);
    }
    this.#onResult(r);
  }
}

async function showProviders(next: string, status: HTMLElement): Promise<void> {
  const result = await api<{ items: ProviderButton[] }>("GET", "/api/auth/providers");
  const list = element<HTMLUListElement>("login-provider-list");
  for (const p of result.items) {
    const li = document.createElement("li");
    const a = document.createElement("a");
    // The link itself is the full-page login (a new tab, no script): a plain click opens the popup.
    a.href = providerStartUrl(p.startUrl, next);
    a.textContent = p.displayName;
    a.addEventListener("click", (ev) => {
      if (ev.button !== 0 || ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey) return;
      ev.preventDefault();
      // Opened right here in the click, so popup blockers let it through.
      let w: Window | null = null;
      try {
        w = window.open(providerPopupUrl(p.startUrl), LOGIN_POPUP_NAME, LOGIN_POPUP_FEATURES);
      } catch {
        w = null;
      }
      if (w === null) {
        const full = document.createElement("a");
        full.href = a.href;
        full.textContent = "log in with " + p.displayName + " in this window";
        status.hidden = false;
        status.replaceChildren("The login window was blocked. Allow pop-ups for this site and try again, or ",
          full, " (this reloads the page, which stops playback).");
        return;
      }
      try {
        w.focus();
      } catch {
        // Not focusable: it is in front anyway.
      }
      status.hidden = false;
      status.textContent = "Finish logging in with " + p.displayName + " in the window that opened.";
    });
    li.appendChild(a);
    list.appendChild(li);
  }
  element<HTMLDivElement>("login-providers").hidden = result.items.length === 0;
}

async function start(): Promise<void> {
  const form = element<HTMLFormElement>("login-form");
  const error = element<HTMLParagraphElement>("login-error");
  const status = element<HTMLParagraphElement>("login-status");
  const providerStatus = element<HTMLParagraphElement>("provider-status");
  const providerErrorEl = element<HTMLParagraphElement>("provider-error");
  const next = nextPath();
  element<HTMLAnchorElement>("register-link").href = withNext(REGISTER_PAGE, next);

  // Listens from the start: a popup from an earlier visit of this view may still report.
  new PopupLogin((r) => {
    providerStatus.hidden = true;
    if (!r.ok) {
      providerErrorEl.textContent = providerErrorMessage(r.error ?? "");
      providerErrorEl.hidden = false;
      return;
    }
    providerErrorEl.hidden = true;
    void (async () => {
      let mustChange = false;
      try {
        const me = await loadMe();
        mustChange = me.kind === "user" && me.mustChangePassword;
      } catch {
        // The next view finds out.
      }
      await loggedIn(mustChange, next);
    })();
  });
  showProviders(next, providerStatus).catch(() => undefined);
  // A full-page provider login that failed comes back here with ?error=.
  const providerError = new URLSearchParams(location.search).get("error");
  if (providerError !== null) {
    providerErrorEl.textContent = providerErrorMessage(providerError);
    providerErrorEl.hidden = false;
  }

  // Handled from the start (before the checks below), so an early submit never goes to the browser.
  onSubmit(form, error, async () => {
    const result = await api<LoginResponse>("POST", "/api/auth/login", {
      username: formValue(form, "username"),
      password: formValue(form, "password"),
    });
    await loggedIn(result.user.mustChangePassword, next);
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
      status.textContent = `You are logged in as ${me.username}. Logging in again switches account.`;
    }
  } catch {
    // The form still works; a real problem shows when it is submitted.
  }
}

onReady(start);
