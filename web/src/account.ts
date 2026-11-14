// The account page (/account/): profile, linked logins (`/api/me/identities`; linking goes through
// `/auth/oidc/{slug}/start?intent=link`, which comes back here with `?linked=` or `?error=`), and
// the user's sessions (`/api/me/sessions`) with revoke and "log out everywhere else". Sessions
// carry no IP address (docs/auth.md).
import { api } from "./api.js";
import {
  element, formValue, LOGIN_PAGE, message, onReady, onSubmit, renderNav, requireUser, type MeUser,
} from "./session.js";
import {
  providerErrorMessage, providerLinkUrl, type LinkedIdentity, type ProviderButton,
} from "./providers.js";
import { mergeableGuest, mergedText, mergeGuest, mergeQuestion } from "./guestmerge.js";

interface SessionItem {
  id: string;
  kind: string;
  createdAt: number;
  lastSeenAt: number;
  expiresAt: number;
  userAgent?: string;
  current: boolean;
}

interface SessionPage { items: SessionItem[]; nextCursor?: string }

let cursor: string | undefined;

function time(ms: number): string {
  return new Date(ms).toLocaleString();
}

/** A short description of a session's device from its user agent. */
export function deviceName(userAgent: string | undefined): string {
  if (userAgent === undefined || userAgent === "") return "Unknown device";
  const browser = /Edg\//.test(userAgent) ? "Edge"
    : /Firefox\//.test(userAgent) ? "Firefox"
    : /Chrome\//.test(userAgent) ? "Chrome"
    : /Safari\//.test(userAgent) ? "Safari"
    : undefined;
  const os = /iPhone|iPad/.test(userAgent) ? "iOS"
    : /Android/.test(userAgent) ? "Android"
    : /Mac OS X/.test(userAgent) ? "macOS"
    : /Windows/.test(userAgent) ? "Windows"
    : /Linux/.test(userAgent) ? "Linux"
    : undefined;
  if (browser !== undefined && os !== undefined) return `${browser} on ${os}`;
  return browser ?? os ?? userAgent.slice(0, 60);
}

function cell(text: string, title?: string): HTMLTableCellElement {
  const td = document.createElement("td");
  td.textContent = text;
  if (title !== undefined) td.title = title;
  return td;
}

function sessionRow(s: SessionItem): HTMLTableRowElement {
  const tr = document.createElement("tr");
  const device = cell(deviceName(s.userAgent), s.userAgent);
  if (s.current) {
    const badge = document.createElement("span");
    badge.className = "badge badge-current";
    badge.textContent = "This device";
    device.append(" ", badge);
  }
  const revoke = document.createElement("button");
  revoke.type = "button";
  revoke.textContent = s.current ? "Log out" : "Revoke";
  revoke.addEventListener("click", () => {
    revoke.disabled = true;
    element("session-error").textContent = "";
    api("DELETE", "/api/me/sessions/" + encodeURIComponent(s.id)).then(() => {
      if (s.current) {
        // Logging this device out reloads the document, like logging out.
        location.assign(LOGIN_PAGE);
      } else {
        tr.remove();
      }
    }).catch((e: unknown) => {
      revoke.disabled = false;
      element("session-error").textContent = message(e);
    });
  });
  const actions = document.createElement("td");
  actions.append(revoke);
  tr.append(device, cell(s.kind === "bearer" ? "Token" : "Browser"), cell(time(s.createdAt)),
    cell(time(s.lastSeenAt)), cell(time(s.expiresAt)), actions);
  return tr;
}

async function loadSessions(reset: boolean): Promise<void> {
  const tbody = element<HTMLTableSectionElement>("session-rows");
  if (reset) cursor = undefined;
  let path = "/api/me/sessions?limit=50";
  if (cursor !== undefined) path += "&cursor=" + encodeURIComponent(cursor);
  const page = await api<SessionPage>("GET", path);
  if (reset) tbody.replaceChildren();
  for (const s of page.items) tbody.append(sessionRow(s));
  cursor = page.nextCursor;
  element("session-more").hidden = cursor === undefined;
}

async function loadIdentities(): Promise<void> {
  const [mine, providers] = await Promise.all([
    api<{ items: LinkedIdentity[]; hasPassword: boolean }>("GET", "/api/me/identities"),
    api<{ items: ProviderButton[] }>("GET", "/api/auth/providers"),
  ]);
  const tbody = element<HTMLTableSectionElement>("identity-rows");
  tbody.replaceChildren();
  for (const i of mine.items) {
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "Remove";
    remove.addEventListener("click", () => {
      remove.disabled = true;
      element("identity-error").textContent = "";
      api("DELETE", "/api/me/identities/" + encodeURIComponent(i.id)).then(() => loadIdentities())
        .catch((e: unknown) => {
          remove.disabled = false;
          element("identity-error").textContent = message(e);
        });
    });
    const actions = document.createElement("td");
    actions.append(remove);
    const tr = document.createElement("tr");
    tr.append(cell(i.provider.displayName + (i.provider.enabled ? "" : " (disabled)")), cell(i.email ?? ""),
      cell(time(i.createdAt)), cell(i.lastLoginAt === undefined ? "" : time(i.lastLoginAt)), actions);
    tbody.append(tr);
  }
  element("identity-table").hidden = mine.items.length === 0;
  element("identity-none").hidden = mine.items.length !== 0;
  const linked = new Set(mine.items.map((i) => i.provider.slug));
  const list = element<HTMLUListElement>("identity-link-list");
  list.replaceChildren();
  for (const p of providers.items) {
    if (linked.has(p.slug)) continue;
    const li = document.createElement("li");
    const a = document.createElement("a");
    a.href = providerLinkUrl(p.startUrl);
    a.textContent = p.displayName;
    li.append(a);
    list.append(li);
  }
  element("identity-link").hidden = list.childElementCount === 0;
}

/** The result of a link (`?linked=<slug>` or `?error=<code>`), shown once. */
function showLinkResult(): void {
  const params = new URLSearchParams(location.search);
  const error = params.get("error");
  const linked = params.get("linked");
  if (error !== null) {
    element("identity-error").textContent = providerErrorMessage(error);
  } else if (linked !== null) {
    const notice = element("identity-notice");
    notice.textContent = "Login linked.";
    notice.hidden = false;
  }
  if (error !== null || linked !== null) history.replaceState(null, "", location.pathname);
}

/** Offers to merge the guest this browser holds (e.g. after a provider login), if there is one. */
async function showGuestMerge(): Promise<void> {
  const guest = await mergeableGuest();
  const section = element("guest-merge");
  section.hidden = guest === undefined;
  if (guest === undefined) return;
  element("guest-merge-question").textContent = mergeQuestion(guest);
  const button = element<HTMLButtonElement>("guest-merge-button");
  button.onclick = () => {
    button.disabled = true;
    element("guest-merge-error").textContent = "";
    mergeGuest().then((result) => {
      section.hidden = true;
      const notice = element("guest-merge-notice");
      notice.textContent = mergedText(result);
      notice.hidden = false;
    }).catch((e: unknown) => {
      element("guest-merge-error").textContent = message(e);
    }).finally(() => { button.disabled = false; });
  };
}

function showProfile(me: MeUser): void {
  element("account-username").textContent = me.username;
  element("account-roles").textContent = me.roles.join(", ");
  const form = element<HTMLFormElement>("profile-form");
  (form.elements.namedItem("displayName") as HTMLInputElement).value = me.displayName;
}

async function start(): Promise<void> {
  const status = element<HTMLParagraphElement>("account-status");
  let me: MeUser | null;
  try {
    me = await requireUser();
  } catch (e) {
    status.textContent = message(e);
    return;
  }
  if (me === null) return;
  renderNav(me);
  showProfile(me);

  const profile = element<HTMLFormElement>("profile-form");
  onSubmit(profile, element("profile-error"), async () => {
    const updated = await api<MeUser>("PATCH", "/api/me", { displayName: formValue(profile, "displayName") });
    showProfile(updated);
    renderNav(updated);
  });

  const sessionError = element("session-error");
  const more = element<HTMLButtonElement>("session-more");
  more.addEventListener("click", () => {
    more.disabled = true;
    loadSessions(false).catch((e: unknown) => { sessionError.textContent = message(e); })
      .finally(() => { more.disabled = false; });
  });
  const others = element<HTMLButtonElement>("session-revoke-others");
  others.addEventListener("click", () => {
    others.disabled = true;
    sessionError.textContent = "";
    api("DELETE", "/api/me/sessions").then(() => loadSessions(true))
      .catch((e: unknown) => { sessionError.textContent = message(e); })
      .finally(() => { others.disabled = false; });
  });

  showGuestMerge().catch(() => undefined);
  showLinkResult();
  try {
    await loadIdentities();
  } catch (e) {
    element("identity-error").textContent = message(e);
  }

  try {
    await loadSessions(true);
  } catch (e) {
    sessionError.textContent = message(e);
  }
  status.hidden = true;
  element("account-body").hidden = false;
}

onReady(start);
