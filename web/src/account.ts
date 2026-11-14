// The user settings (/account/; docs/ui.md#existing-features-in-the-new-ui), in tabs (the URL's hash):
// - Preferences (`/api/me/settings` through the shared store, prefsclient.ts): double-click alone, Web
//   Audio (applied at once to the room playing here) and debug mode;
// - Account: profile, password, the guest merge, linked logins (`/api/me/identities`; linking goes
//   through `/auth/oidc/{slug}/start?intent=link`, which comes back here with `?linked=` or `?error=`);
// - Sessions (`/api/me/sessions`) with revoke and "log out everywhere else", and API tokens
//   (`POST /api/auth/token` for the signed-in user; the token is shown once). Sessions carry no IP
//   address (docs/auth.md);
// - Invites (`users.invite` or `admin.users`): create (the link is shown once), list, revoke.
import { api, ApiError } from "./api.js";
import { actorLabel } from "./actor.js";
import { button, formatTime, h } from "./dom.js";
import { canRevoke, DEFAULT_EXPIRY_HOURS, EXPIRY_OPTIONS, inviteBody, inviteStatusText, type CreatedInvite,
  type Invite } from "./invites.js";
import { pageSignal } from "./nav.js";
import { copyField } from "./outputspanel.js";
import { DOUBLE_CLICK_OPTIONS, WEB_AUDIO_OPTIONS, type Prefs } from "./prefs.js";
import { prefs } from "./prefsclient.js";
import {
  canInvite, element, formValue, LOGIN_PAGE, message, onReady, onSubmit, renderNav, requireUser, type MeUser,
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
  element("session-empty").hidden = tbody.childElementCount > 0;
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
  if (error !== null || linked !== null) history.replaceState(history.state, "", location.pathname + location.hash);
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

// ------------------------------------------------------------------ tabs

const TABS = ["preferences", "account", "sessions", "invites"] as const;
type Tab = typeof TABS[number];

let allowed: Tab[] = ["preferences", "account", "sessions"];

function currentTab(): Tab {
  const name = location.hash.replace(/^#/, "");
  return (allowed as string[]).indexOf(name) >= 0 ? name as Tab : "preferences";
}

const loaded = new Set<Tab>();

async function showTab(): Promise<void> {
  const tab = currentTab();
  for (const t of TABS) element("acct-tab-" + t).hidden = t !== tab;
  for (const a of Array.from(document.querySelectorAll<HTMLAnchorElement>("#account-tabs a"))) {
    if (a.dataset["tab"] === tab) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  if (loaded.has(tab)) return;
  loaded.add(tab);
  if (tab === "account") await loadAccountTab();
  else if (tab === "sessions") {
    try {
      await loadSessions(true);
    } catch (e) {
      element("session-error").textContent = message(e);
    }
  } else if (tab === "invites") await loadInvites(true);
}

// ------------------------------------------------------------------ preferences

function fillSelect(select: HTMLSelectElement, options: readonly [string, string][]): void {
  select.replaceChildren(...options.map(([v, label]) => h("option", { value: v, text: label })));
}

function showPrefs(p: Prefs): void {
  element<HTMLSelectElement>("pref-doubleClickAlone").value = p.doubleClickAlone;
  element<HTMLSelectElement>("pref-webAudio").value = p.webAudio;
  element<HTMLInputElement>("pref-debug").checked = p.debug;
}

async function setUpPrefs(): Promise<void> {
  const dc = element<HTMLSelectElement>("pref-doubleClickAlone");
  const wa = element<HTMLSelectElement>("pref-webAudio");
  const debug = element<HTMLInputElement>("pref-debug");
  fillSelect(dc, DOUBLE_CLICK_OPTIONS);
  fillSelect(wa, WEB_AUDIO_OPTIONS);
  const status = element("prefs-status");
  const error = element("prefs-error");
  element<HTMLFormElement>("prefs-form").addEventListener("submit", (ev) => ev.preventDefault());
  const unsubscribe = prefs.subscribe((p) => showPrefs(p));
  pageSignal().addEventListener("abort", unsubscribe);
  // Each change is saved at once (and applied at once: the room playing here switches its sound path
  // inside this change event, a user gesture).
  const save = (patch: Partial<Prefs>): void => {
    status.textContent = "Saving…";
    error.textContent = "";
    prefs.set(patch).then(() => {
      status.textContent = "Saved.";
    }).catch((e: unknown) => {
      status.textContent = "";
      error.textContent = message(e);
    });
  };
  dc.addEventListener("change", () => save({ doubleClickAlone: dc.value === "queue" ? "queue" : "play" }));
  wa.addEventListener("change", () => save({ webAudio: wa.value === "on" || wa.value === "off" ? wa.value : "auto" }));
  debug.addEventListener("change", () => save({ debug: debug.checked }));
  try {
    showPrefs(await prefs.load());
  } catch (e) {
    error.textContent = message(e);
  }
}

// ------------------------------------------------------------------ account

async function loadAccountTab(): Promise<void> {
  showGuestMerge().catch(() => undefined);
  try {
    await loadIdentities();
  } catch (e) {
    element("identity-error").textContent = message(e);
  }
}

// ------------------------------------------------------------------ API tokens

interface CreatedToken { token: string; sessionId: string; expiresAt: number }

async function createToken(): Promise<void> {
  element("token-error").textContent = "";
  const t = await api<CreatedToken>("POST", "/api/auth/token");
  const box = element("token-new");
  box.replaceChildren(
    h("p", { class: "small", text: "Your new API token. Copy it now: it isn't shown again." }),
    copyField(t.token, "API token"),
    h("p", { class: "muted small", text: "Unused, it expires on " + formatTime(t.expiresAt) + "." }));
  box.hidden = false;
  await loadSessions(true);
}

// ------------------------------------------------------------------ invites

let inviteCursor: string | undefined;
let showCreator = false;

function inviteError(e: unknown): void {
  element("invite-error").textContent = message(e);
}

function inviteRow(inv: Invite): HTMLTableRowElement {
  const created = h("td", { class: "small" }, formatTime(inv.createdAt));
  if (showCreator && inv.createdBy !== undefined) created.append(" by ", actorLabel(inv.createdBy));
  const actions = h("td", { class: "actions" });
  if (canRevoke(inv)) {
    actions.append(button("Revoke", async () => {
      const updated = await api<Invite>("DELETE", "/api/invites/" + encodeURIComponent(inv.id));
      row.replaceWith(inviteRow(updated));
    }, inviteError, "small"));
  }
  const row = h("tr", {},
    h("td", { text: inv.note ?? "", class: inv.note === undefined ? "muted" : "" }),
    h("td", { class: "small", text: inviteStatusText(inv) }),
    created,
    h("td", { class: "small muted", text: formatTime(inv.expiresAt) }),
    actions);
  return row;
}

async function loadInvites(reset: boolean): Promise<void> {
  const rows = element("invite-rows");
  if (reset) {
    inviteCursor = undefined;
    rows.replaceChildren();
  }
  const params = new URLSearchParams({ limit: "50" });
  if (inviteCursor !== undefined) params.set("cursor", inviteCursor);
  try {
    const page = await api<{ items: Invite[]; nextCursor?: string }>("GET", "/api/invites?" + params.toString());
    for (const inv of page.items) rows.append(inviteRow(inv));
    inviteCursor = page.nextCursor;
    element("invite-more").hidden = page.nextCursor === undefined;
  } catch (e) {
    inviteError(e);
  }
  element("invite-table").hidden = rows.childElementCount === 0;
  element("invite-empty").hidden = rows.childElementCount > 0;
}

function setUpInvites(me: MeUser): void {
  showCreator = me.permissions.indexOf("admin.users") >= 0;
  const expiry = element<HTMLSelectElement>("invite-expiry");
  fillSelect(expiry, EXPIRY_OPTIONS.map(([hours, label]) => [String(hours), label]));
  expiry.value = String(DEFAULT_EXPIRY_HOURS);
  const form = element<HTMLFormElement>("invite-create");
  const canCreate = me.permissions.indexOf("users.invite") >= 0 || showCreator;
  form.hidden = !canCreate;
  onSubmit(form, element("invite-error"), async () => {
    const r = inviteBody(expiry.value, element<HTMLInputElement>("invite-note").value);
    if ("error" in r) throw new Error(r.error);
    let created: CreatedInvite;
    try {
      created = await api<CreatedInvite>("POST", "/api/invites", r.body);
    } catch (e) {
      if (e instanceof ApiError && e.code === "too_many_invites") {
        throw new Error("You have too many invites that aren't used yet. Revoke some first.");
      }
      throw e;
    }
    element<HTMLInputElement>("invite-note").value = "";
    const box = element("invite-new");
    box.replaceChildren(
      h("p", { class: "small", text: "The registration link of the new invite. Copy it now: it isn't shown again." }),
      copyField(created.url, "Invite link"),
      h("p", { class: "muted small", text: "Valid until " + formatTime(created.invite.expiresAt) + ", for one account." }));
    box.hidden = false;
    await loadInvites(true);
  });
  element("invite-more").addEventListener("click", () => void loadInvites(false));
}

// ------------------------------------------------------------------ start

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
  const tokenButton = element<HTMLButtonElement>("token-create");
  tokenButton.addEventListener("click", () => {
    tokenButton.disabled = true;
    createToken().catch((e: unknown) => { element("token-error").textContent = message(e); })
      .finally(() => { tokenButton.disabled = false; });
  });

  if (canInvite(me)) {
    allowed = ["preferences", "account", "sessions", "invites"];
    element("account-invites-tab").hidden = false;
    setUpInvites(me);
  }
  // Back from linking a login: the Account tab shows the result.
  const params = new URLSearchParams(location.search);
  if ((params.has("linked") || params.has("error")) && location.hash === "") {
    history.replaceState(history.state, "", location.pathname + location.search + "#account");
  }
  showLinkResult();
  await setUpPrefs();
  status.hidden = true;
  element("account-body").hidden = false;
  window.addEventListener("hashchange", () => void showTab(), { signal: pageSignal() });
  await showTab();
}

onReady(start);
