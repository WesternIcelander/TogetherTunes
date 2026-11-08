// The account page (/account/): profile, and the user's sessions (`/api/me/sessions`) with
// revoke and "log out everywhere else". Sessions carry no IP address (docs/auth.md).
import { api } from "./api.js";
import {
  element, formValue, LOGIN_PAGE, message, onReady, onSubmit, renderNav, requireUser, type MeUser,
} from "./session.js";

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

  try {
    await loadSessions(true);
  } catch (e) {
    sessionError.textContent = message(e);
  }
  status.hidden = true;
  element("account-body").hidden = false;
}

onReady(start);
