// The admin pages (/admin/): users, roles, server settings (with the yt-dlp update), fonts, encode jobs and
// the audit log, on top of /api/admin/...
// (docs/api.md#admin).
// Each tab loads its data when opened; tabs the user has no permission for show the API's error.
import { actorLabel } from "./actor.js";
import { api, ApiError } from "./api.js";
import { AUDIT_ACTIONS, auditPath, detailsText, formatTime, type AuditEntry, type AuditFilter,
  type AuditPage } from "./audit.js";
import { busy, CJK_VARIANTS, cardFontChoices, cardFontSetting, downloadAllText, formatSize, statusText,
  type FontsInfo } from "./fonts.js";
import { serverCardsPath } from "./customcards.js";
import { mountCustomCards } from "./customcardsui.js";
import { mountJobs, type JobsView } from "./jobs.js";
import { pageSignal } from "./nav.js";
import { adjustedNotice, coupledChange } from "./roomsettings.js";
import { formatGib, parseGib, storageText, type StorageStatus } from "./storage.js";
import { isAdmin, onReady, renderNav, requireUser } from "./session.js";
import { updateText, updating, versionText, type YtdlpInfo, type YtdlpUpdate } from "./ytdlp.js";

interface RoleRef { id: string; name: string }

interface User {
  id: string;
  username: string;
  displayName: string;
  email?: string;
  createdAt: number;
  disabled: boolean;
  hasPassword: boolean;
  mustChangePassword: boolean;
  roles: RoleRef[];
}

interface Role {
  id: string;
  name: string;
  builtin: boolean;
  isDefault: boolean;
  editable: boolean;
  permissions: string[];
}

interface PermissionInfo { name: string; description: string; feature?: string; featureEnabled?: boolean }

interface RoleList { items: Role[]; permissions: PermissionInfo[] }

type SettingValue = boolean | number | string | string[] | { perHour: number; maxItemBytes: number } | undefined;

interface Setting {
  key: string;
  group: string;
  type: "boolean" | "integer" | "bytes" | "string" | "enum" | "uploadQuota" | "stringList";
  value?: SettingValue;
  default?: SettingValue;
  options?: string[];
}

const GROUPS: Record<string, string> = {
  accounts: "Accounts",
  guests: "Guests",
  library: "Library",
  retention: "Retention",
  storage: "Storage",
  output: "Output encoding",
  outputs: "Outputs",
  overlay: "Overlay",
};

// ------------------------------------------------------------------ DOM helpers

function element<T extends HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (el === null) {
    throw new Error("Missing #" + id);
  }
  return el as T;
}

type Child = Node | string | null | undefined | false;

function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Partial<Record<string, string | boolean>> = {},
                                                  ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [name, value] of Object.entries(props)) {
    if (value === undefined || value === false) continue;
    if (name === "text") {
      el.textContent = String(value);
    } else {
      el.setAttribute(name, value === true ? "" : value);
    }
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child);
  }
  return el;
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function status(text: string): void {
  element("admin-status").textContent = text;
}

/** Runs an action from a button or form: disables it meanwhile, reports errors in `errorEl`. */
async function run(errorEl: HTMLElement | null, control: HTMLButtonElement | HTMLFormElement | null,
                   action: () => Promise<void>): Promise<void> {
  if (errorEl !== null) errorEl.textContent = "";
  const buttons = control instanceof HTMLFormElement
    ? Array.from(control.querySelectorAll("button")) : control === null ? [] : [control];
  buttons.forEach((b) => { b.disabled = true; });
  try {
    await action();
  } catch (e) {
    if (errorEl !== null) {
      errorEl.textContent = message(e);
    } else {
      status(message(e));
    }
  } finally {
    buttons.forEach((b) => { b.disabled = false; });
  }
}

function formValue(form: HTMLFormElement, name: string): string {
  return String(new FormData(form).get(name) ?? "");
}

// ------------------------------------------------------------------ tabs

const TABS = ["users", "roles", "providers", "settings", "fonts", "cards", "jobs", "audit"] as const;
type Tab = typeof TABS[number];

function currentTab(): Tab {
  const name = location.hash.replace(/^#/, "");
  return (TABS as readonly string[]).includes(name) ? name as Tab : "users";
}

async function showTab(): Promise<void> {
  const tab = currentTab();
  for (const t of TABS) {
    element("tab-" + t).hidden = t !== tab;
  }
  for (const a of Array.from(document.querySelectorAll<HTMLAnchorElement>("#admin-tabs a"))) {
    if (a.dataset["tab"] === tab) {
      a.setAttribute("aria-current", "page");
    } else {
      a.removeAttribute("aria-current");
    }
  }
  status("");
  try {
    if (tab === "users") {
      await loadUsers(true);
    } else if (tab === "roles") {
      await loadRoles();
    } else if (tab === "providers") {
      await loadProviders();
    } else if (tab === "jobs") {
      await loadJobs();
    } else if (tab === "fonts") {
      await loadFonts();
    } else if (tab === "cards") {
      await mountCustomCards(element("cards-root"), serverCardsPath(), false);
    } else if (tab === "audit") {
      await loadAudit(true);
    } else {
      await loadSettings();
    }
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      status("Log in as an administrator first.");
    } else if (e instanceof ApiError && e.status === 403) {
      status("You don't have permission for this page.");
    } else {
      status(message(e));
    }
  }
}

// ------------------------------------------------------------------ users

let userQuery = "";
let userCursor: string | undefined;
let selectedUserId: string | undefined;
let roleCache: Role[] | undefined;

async function roles(): Promise<Role[]> {
  if (roleCache === undefined) {
    roleCache = (await api<RoleList>("GET", "/api/admin/roles")).items;
  }
  return roleCache;
}

async function loadUsers(reset: boolean): Promise<void> {
  const tbody = element<HTMLTableSectionElement>("user-rows");
  if (reset) {
    userCursor = undefined;
  }
  let path = "/api/admin/users?limit=50";
  if (userQuery !== "") path += "&q=" + encodeURIComponent(userQuery);
  if (userCursor !== undefined) path += "&cursor=" + encodeURIComponent(userCursor);
  const page = await api<{ items: User[]; nextCursor?: string }>("GET", path);
  if (reset) {
    tbody.replaceChildren();
  }
  for (const user of page.items) {
    tbody.append(userRow(user));
  }
  userCursor = page.nextCursor;
  element("user-more").hidden = userCursor === undefined;
}

function userStatus(user: User): string {
  const parts: string[] = [];
  parts.push(user.disabled ? "disabled" : "active");
  if (!user.hasPassword) parts.push("no password");
  if (user.mustChangePassword) parts.push("temporary password");
  return parts.join(", ");
}

function userRow(user: User): HTMLTableRowElement {
  const row = h("tr", { "data-id": user.id },
    h("td", { text: user.username }),
    h("td", { text: user.displayName }),
    h("td", { text: user.roles.map((r) => r.name).join(", ") }),
    h("td", { text: userStatus(user) }));
  if (user.id === selectedUserId) row.classList.add("selected");
  row.addEventListener("click", () => void run(null, null, async () => {
    selectedUserId = user.id;
    for (const r of Array.from(element("user-rows").querySelectorAll("tr"))) {
      r.classList.toggle("selected", r.dataset["id"] === user.id);
    }
    await showUser(await api<User>("GET", "/api/admin/users/" + encodeURIComponent(user.id)));
  }));
  return row;
}

/** Replaces the user's row in the table after a change. */
function updateRow(user: User): void {
  const old = element("user-rows").querySelector(`tr[data-id="${CSS.escape(user.id)}"]`);
  if (old !== null) old.replaceWith(userRow(user));
}

async function showUser(user: User): Promise<void> {
  const panel = element("user-detail");
  const allRoles = await roles();
  const userPath = "/api/admin/users/" + encodeURIComponent(user.id);
  const refresh = async (u: User): Promise<void> => {
    updateRow(u);
    await showUser(u);
  };

  // Display name
  const nameError = h("p", { class: "error", role: "alert" });
  const nameForm = h("form", { class: "row" },
    h("label", {}, "Display name ", h("input", { name: "displayName", value: user.displayName, maxlength: "64" })),
    h("button", { type: "submit", text: "Save" }), nameError);
  nameForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void run(nameError, nameForm, async () => {
      await refresh(await api<User>("PATCH", userPath, { displayName: formValue(nameForm, "displayName") }));
    });
  });

  // Roles
  const held = new Set(user.roles.map((r) => r.id));
  const roleError = h("p", { class: "error", role: "alert" });
  const roleForm = h("form", {},
    h("fieldset", { class: "checks" }, h("legend", { text: "Roles" }),
      ...allRoles.map((r) => h("label", {},
        h("input", { type: "checkbox", name: "role", value: r.id, checked: held.has(r.id) }), " " + r.name))),
    h("button", { type: "submit", text: "Save roles" }), roleError);
  roleForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const roleIds = new FormData(roleForm).getAll("role").map(String);
    void run(roleError, roleForm, async () => {
      await api("PUT", userPath + "/roles", { roleIds });
      await refresh(await api<User>("GET", userPath));
    });
  });

  // Temporary password
  const passwordError = h("p", { class: "error", role: "alert" });
  const passwordForm = h("form", { class: "row" },
    h("label", {}, "Temporary password ",
      h("input", { name: "password", type: "text", autocomplete: "off", required: true, minlength: "10", maxlength: "1024" })),
    h("button", { type: "submit", text: "Set password" }), passwordError);
  passwordForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void run(passwordError, passwordForm, async () => {
      await api("POST", userPath + "/password", { password: formValue(passwordForm, "password") });
      status(`Set a temporary password for ${user.username}; they were logged out everywhere.`);
      await refresh(await api<User>("GET", userPath));
    });
  });

  // Disable / enable
  const disableError = h("p", { class: "error", role: "alert" });
  const disableButton = h("button", { type: "button", text: user.disabled ? "Enable account" : "Disable account" });
  disableButton.addEventListener("click", () => {
    if (!user.disabled && !confirm(`Disable ${user.username}? They are logged out everywhere and can't log in.`)) {
      return;
    }
    void run(disableError, disableButton, async () => {
      await refresh(await api<User>("PATCH", userPath, { disabled: !user.disabled }));
    });
  });

  panel.replaceChildren(
    h("h3", { text: user.username }),
    h("p", { class: "muted", text: `Created ${new Date(user.createdAt).toLocaleString()} · ${userStatus(user)}`
        + (user.email !== undefined ? ` · ${user.email}` : "") }),
    nameForm, roleForm, passwordForm,
    h("div", { class: "row" }, disableButton, disableError));
  panel.hidden = false;
}

function setUpUsers(): void {
  const search = element<HTMLFormElement>("user-search");
  search.addEventListener("submit", (event) => {
    event.preventDefault();
    userQuery = formValue(search, "q").trim();
    void run(null, search, () => loadUsers(true));
  });
  const more = element<HTMLButtonElement>("user-more");
  more.addEventListener("click", () => void run(null, more, () => loadUsers(false)));

  const create = element<HTMLFormElement>("user-create");
  create.addEventListener("submit", (event) => {
    event.preventDefault();
    void run(create.querySelector(".error"), create, async () => {
      const user = await api<User>("POST", "/api/admin/users", {
        username: formValue(create, "username"),
        displayName: formValue(create, "displayName"),
        password: formValue(create, "password"),
      });
      create.reset();
      status(`Created ${user.username}.`);
      selectedUserId = user.id;
      await loadUsers(true);
      await showUser(user);
    });
  });
}

// ------------------------------------------------------------------ roles

async function loadRoles(): Promise<void> {
  const list = await api<RoleList>("GET", "/api/admin/roles");
  roleCache = list.items;
  const container = element("role-list");
  container.replaceChildren(...list.items.map((role) => roleCard(role, list.permissions)));
}

function roleCard(role: Role, permissions: PermissionInfo[]): HTMLElement {
  const rolePath = "/api/admin/roles/" + encodeURIComponent(role.id);
  const held = new Set(role.permissions);
  const error = h("p", { class: "error", role: "alert" });
  const form = h("form", { class: "panel" },
    h("h3", { text: role.name + (role.builtin ? " (built-in)" : "") }),
    role.editable && !role.builtin
      ? h("label", {}, "Name ", h("input", { name: "name", value: role.name, required: true, maxlength: "32" }))
      : null,
    h("label", {}, h("input", { type: "checkbox", name: "isDefault", checked: role.isDefault, disabled: !role.editable }),
      " Default role (new accounts get it)"),
    h("fieldset", { class: "checks" }, h("legend", { text: "Permissions" }),
      ...permissions.map((p) => h("label", { title: p.description },
        h("input", { type: "checkbox", name: "permission", value: p.name, checked: held.has(p.name), disabled: !role.editable }),
        " ", h("code", { text: p.name }), " ", h("span", { class: "muted", text: p.description }),
        p.featureEnabled === false ? h("span", { class: "muted", text: ` (off: ${p.feature ?? ""} is disabled)` }) : null))),
    role.editable ? h("button", { type: "submit", text: "Save" }) : null,
    role.editable && !role.builtin ? h("button", { type: "button", "data-action": "delete", text: "Delete role" }) : null,
    error);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const data = new FormData(form);
    void run(error, form, async () => {
      const patch: Record<string, unknown> = { isDefault: data.get("isDefault") !== null };
      if (data.has("name")) patch["name"] = String(data.get("name"));
      await api("PATCH", rolePath, patch);
      await api("PUT", rolePath + "/permissions", { permissions: data.getAll("permission").map(String) });
      status(`Saved ${role.name}.`);
      await loadRoles();
    });
  });
  const del = form.querySelector<HTMLButtonElement>("button[data-action=delete]");
  del?.addEventListener("click", () => {
    if (!confirm(`Delete the role ${role.name}? Its users lose it.`)) return;
    void run(error, del, async () => {
      await api("DELETE", rolePath);
      status(`Deleted ${role.name}.`);
      await loadRoles();
    });
  });
  return form;
}

function setUpRoles(): void {
  const create = element<HTMLFormElement>("role-create");
  create.addEventListener("submit", (event) => {
    event.preventDefault();
    void run(create.querySelector(".error"), create, async () => {
      const role = await api<Role>("POST", "/api/admin/roles", { name: formValue(create, "name"), permissions: [] });
      create.reset();
      status(`Created ${role.name}; choose its permissions below.`);
      await loadRoles();
    });
  });
}

// ------------------------------------------------------------------ login providers

interface Provider {
  id: string;
  slug: string;
  displayName: string;
  kind: "oidc" | "oauth2";
  issuer: string;
  clientId: string;
  hasClientSecret: boolean;
  scopes: string;
  enabled: boolean;
  allowSignup: boolean;
  claimMapping?: Record<string, string>;
  redirectUri: string;
  identities: number;
}

async function loadProviders(): Promise<void> {
  const list = await api<{ items: Provider[] }>("GET", "/api/admin/providers");
  element("provider-list").replaceChildren(...list.items.map((p) => providerForm(p)), providerForm(null));
}

/** The form of one provider, or (`null`) the form that adds one. */
function providerForm(p: Provider | null): HTMLElement {
  const error = h("p", { class: "error", role: "alert" });
  const input = (name: string, value: string, extra: Record<string, string | boolean> = {}): HTMLInputElement =>
    h("input", { name, value, ...extra });
  const form = h("form", { class: "panel form" },
    h("h3", { text: p === null ? "Add a login provider" : p.displayName }),
    p === null ? null : h("p", { class: "muted" }, "Redirect URI to register at the provider: ",
      h("code", { text: p.redirectUri }), ` · ${p.identities} linked account${p.identities === 1 ? "" : "s"}`),
    h("label", {}, "Kind ", h("select", { name: "kind", disabled: p !== null },
      h("option", { value: "oidc", text: "OpenID Connect", selected: p?.kind !== "oauth2" }),
      h("option", { value: "oauth2", text: "OAuth 2.0 (userinfo URL and claim paths)", selected: p?.kind === "oauth2" }))),
    h("label", {}, "Slug (in the login URLs) ", input("slug", p?.slug ?? "", { required: true, maxlength: "32" })),
    h("label", {}, "Button label ", input("displayName", p?.displayName ?? "", { required: true, maxlength: "64" })),
    h("label", {}, "Issuer (OpenID Connect; optional for OAuth) ", input("issuer", p?.issuer ?? "")),
    h("label", {}, "Client ID ", input("clientId", p?.clientId ?? "", { required: true })),
    h("label", {}, "Client secret ", input("clientSecret", "", { type: "password", autocomplete: "off",
      placeholder: p === null ? "none (public client)" : p.hasClientSecret ? "unchanged" : "none" })),
    p !== null && p.hasClientSecret
      ? h("label", {}, h("input", { type: "checkbox", name: "removeSecret" }), " Remove the client secret") : null,
    h("label", {}, "Scopes (empty: the default) ", input("scopes", p?.scopes ?? "")),
    h("label", {}, "Claim mapping (JSON; required for OAuth) ",
      h("textarea", { name: "claimMapping", rows: "4",
        text: p?.claimMapping === undefined ? "" : JSON.stringify(p.claimMapping, null, 2) })),
    h("label", {}, h("input", { type: "checkbox", name: "enabled", checked: p === null || p.enabled }), " Enabled"),
    h("label", {}, h("input", { type: "checkbox", name: "allowSignup", checked: p?.allowSignup ?? false }),
      " New accounts can sign up through it"),
    h("button", { type: "submit", text: p === null ? "Add provider" : "Save" }),
    p === null ? null : h("button", { type: "button", "data-action": "delete", text: "Delete provider" }),
    error);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const data = new FormData(form);
    void run(error, form, async () => {
      const text = (name: string): string => String(data.get(name) ?? "").trim();
      const mapping = text("claimMapping");
      let claimMapping: unknown = null;
      if (mapping !== "") {
        try {
          claimMapping = JSON.parse(mapping);
        } catch {
          throw new Error("The claim mapping isn't valid JSON.");
        }
      }
      const body: Record<string, unknown> = {
        slug: text("slug"), displayName: text("displayName"), issuer: text("issuer") || null,
        clientId: text("clientId"), scopes: text("scopes") || null, claimMapping,
        enabled: data.get("enabled") !== null, allowSignup: data.get("allowSignup") !== null,
      };
      const secret = String(data.get("clientSecret") ?? "");
      if (secret !== "") {
        body["clientSecret"] = secret;
      } else if (data.get("removeSecret") !== null) {
        body["clientSecret"] = null;
      }
      if (p === null) {
        body["kind"] = text("kind");
        await api("POST", "/api/admin/providers", body);
        status(`Added ${text("displayName")}.`);
      } else {
        await api("PATCH", "/api/admin/providers/" + encodeURIComponent(p.id), body);
        status(`Saved ${text("displayName")}.`);
      }
      await loadProviders();
    });
  });
  const del = form.querySelector<HTMLButtonElement>("button[data-action=delete]");
  if (p !== null && del !== null) {
    del.addEventListener("click", () => {
      if (!confirm(`Delete the login provider ${p.displayName}?`)) return;
      void run(error, del, async () => {
        await api("DELETE", "/api/admin/providers/" + encodeURIComponent(p.id));
        status(`Deleted ${p.displayName}.`);
        await loadProviders();
      });
    });
  }
  return form;
}

// ------------------------------------------------------------------ settings

let loadedSettings: Setting[] = [];

/** The Fonts tab edits the card font settings; the Settings tab leaves them out. */
function withoutFonts(items: Setting[]): Setting[] {
  return items.filter((s) => s.group !== "fonts");
}

async function loadSettings(): Promise<void> {
  loadedSettings = withoutFonts((await api<{ items: Setting[] }>("GET", "/api/admin/settings")).items);
  renderSettings();
  await loadStorage();
  await loadYtdlp();
}

// ------------------------------------------------------------------ yt-dlp

let ytdlpPoll: number | undefined;

/** The yt-dlp box (admin.settings): version, the Update button and the last run's output; polls while it runs. */
async function loadYtdlp(): Promise<void> {
  if (ytdlpPoll !== undefined) {
    clearTimeout(ytdlpPoll);
    ytdlpPoll = undefined;
  }
  let info: YtdlpInfo;
  try {
    info = await api<YtdlpInfo>("GET", "/api/admin/ytdlp");
  } catch (e) {
    element("ytdlp-version").textContent = "yt-dlp: " + message(e);
    return;
  }
  renderYtdlp(info);
  if (updating(info) && currentTab() === "settings") {
    ytdlpPoll = window.setTimeout(() => void loadYtdlp(), 2000);
  }
}

function renderYtdlp(info: YtdlpInfo): void {
  element("ytdlp-version").textContent = versionText(info);
  element<HTMLButtonElement>("ytdlp-update").disabled = !info.available || updating(info);
  const text = updateText(info.update);
  const line = element("ytdlp-status");
  line.textContent = text ?? "";
  line.hidden = text === undefined;
  const log = element("ytdlp-log");
  log.textContent = info.update?.log ?? "";
  log.hidden = info.update === undefined || info.update.log === "";
}

function setUpYtdlp(): void {
  const button = element<HTMLButtonElement>("ytdlp-update");
  const error = element("ytdlp-error");
  button.addEventListener("click", () => void run(error, button, async () => {
    await api<YtdlpUpdate>("POST", "/api/admin/ytdlp/update");
    await loadYtdlp();
  }));
}

async function loadStorage(): Promise<void> {
  const line = element("storage-line");
  const warning = element("storage-warning");
  try {
    const text = storageText(await api<StorageStatus>("GET", "/api/admin/storage"));
    line.textContent = text.line;
    warning.textContent = text.warning ?? "";
    warning.hidden = text.warning === undefined;
  } catch (e) {
    line.textContent = "Storage: " + message(e);
    warning.hidden = true;
  }
}

/** What an unset (null) value means, for settings where it isn't just "none". */
const UNSET_TEXT: Record<string, string> = {
  "output.videoBitrateKbps": "the profile's default",
};

function describe(value: SettingValue, type?: Setting["type"], key?: string): string {
  if (value === undefined) return (key !== undefined ? UNSET_TEXT[key] : undefined) ?? "(none)";
  if (type === "bytes" && typeof value === "number") return value === 0 ? "0 (off)" : formatGib(value) + " GiB";
  if (Array.isArray(value)) return value.length === 0 ? "(empty)" : `${value.length} entries`;
  if (typeof value === "object") return `${value.perHour} per hour, ${value.maxItemBytes} bytes per item`;
  return String(value);
}

function renderSettings(): void {
  const container = element("settings-fields");
  container.replaceChildren();
  let group = "";
  for (const s of loadedSettings) {
    if (s.group !== group) {
      group = s.group;
      container.append(h("h3", { text: GROUPS[group] ?? group }));
    }
    container.append(settingRow(s));
  }
}

function settingRow(s: Setting): HTMLElement {
  const name = "s:" + s.key;
  let input: HTMLElement;
  switch (s.type) {
    case "boolean": {
      const box = h("input", { type: "checkbox", name, checked: s.value === true });
      box.addEventListener("change", () => { coupleServerSwitch(s.key, box.checked); });
      input = box;
      break;
    }
    case "integer":
      input = h("input", { type: "number", name, step: "1", value: String(s.value ?? ""),
        placeholder: UNSET_TEXT[s.key] ?? "" });
      break;
    case "bytes":
      input = h("span", { class: "row" },
        h("input", { type: "number", name, min: "0", step: "any", value: typeof s.value === "number" ? formatGib(s.value) : "",
          "aria-label": s.key + " in GiB" }),
        "GiB");
      break;
    case "enum":
      input = h("select", { name }, ...(s.options ?? []).map((o) => h("option", { value: o, text: o, selected: o === s.value })));
      break;
    case "uploadQuota": {
      const q = typeof s.value === "object" && !Array.isArray(s.value) ? s.value : { perHour: 0, maxItemBytes: 0 };
      input = h("span", { class: "row" },
        h("input", { type: "number", name: name + ":perHour", min: "0", step: "1", value: String(q.perHour), "aria-label": "Uploads per hour" }),
        "per hour,",
        h("input", { type: "number", name: name + ":maxItemBytes", min: "0", step: "1", value: String(q.maxItemBytes), "aria-label": "Bytes per item" }),
        "bytes per item");
      break;
    }
    case "stringList":
      input = h("textarea", { name, rows: "5", cols: "40", placeholder: "One entry per line",
        text: Array.isArray(s.value) ? s.value.join("\n") : "" });
      break;
    default:
      input = h("input", { type: "text", name, value: typeof s.value === "string" ? s.value : "", size: "40" });
  }
  const reset = h("button", { type: "button", text: "Default", title: "Reset to " + describe(s.default, s.type, s.key) });
  reset.addEventListener("click", () => {
    const error = element("settings-form").querySelector<HTMLElement>(".error");
    void run(error, reset, async () => {
      if (!(await confirmEncodingChange({ [s.key]: s.default === undefined ? null : s.default }))) return;
      const r = await api<SettingsResponse>("PATCH", "/api/admin/settings", { [s.key]: null });
      loadedSettings = withoutFonts(r.items);
      status(`Reset ${s.key}.` + savedNotice(r));
      renderSettings();
      await loadStorage();
    });
  });
  return h("div", { class: "setting" }, h("span", { text: s.key }), input, reset,
    h("span", { class: "muted", text: "default: " + describe(s.default, s.type, s.key) }));
}

/** `PATCH /api/admin/settings`: the settings, and those changed on top of the patch (uploads need library requests). */
interface SettingsResponse { items: Setting[]; adjusted?: string[] }

/** " " + the notice for the settings the server adjusted, or "". */
function savedNotice(r: SettingsResponse): string {
  const notice = adjustedNotice(r.adjusted, (k) => r.items.find((x) => x.key === k)?.value, (k) => k);
  return notice === null ? "" : " " + notice;
}

/**
 * Uploads need library requests (guests.canUpload needs guests.canRequestLibrary): after a switch
 * changed in the form, flips its companion and says so.
 */
function coupleServerSwitch(key: string, value: boolean): void {
  const form = element<HTMLFormElement>("settings-form");
  const box = (k: string): HTMLInputElement | null => form.querySelector<HTMLInputElement>(
    "input[type=checkbox][name=\"s:" + k + "\"]");
  const change = coupledChange(key, value, (k) => box(k)?.checked, (k) => k);
  if (change === null) return;
  const other = box(change.key);
  if (other === null) return;
  other.checked = change.value;
  status(change.notice);
}

/** The settings whose form value differs from the loaded one, as a PATCH body. */
function changedSettings(form: HTMLFormElement): Record<string, unknown> {
  const data = new FormData(form);
  const out: Record<string, unknown> = {};
  for (const s of loadedSettings) {
    const name = "s:" + s.key;
    let value: unknown;
    switch (s.type) {
      case "boolean":
        value = data.get(name) !== null;
        break;
      case "integer": {
        // An empty field resets the setting to its default.
        const text = String(data.get(name) ?? "").trim();
        value = text === "" ? null : Number(text);
        break;
      }
      case "bytes": {
        // Unchanged text keeps the stored value (GiB with 3 decimals may not round-trip exactly).
        const text = String(data.get(name) ?? "");
        value = typeof s.value === "number" && text.trim() === formatGib(s.value) ? s.value : parseGib(text);
        break;
      }
      case "uploadQuota":
        value = { perHour: Number(data.get(name + ":perHour")), maxItemBytes: Number(data.get(name + ":maxItemBytes")) };
        break;
      case "stringList":
        value = String(data.get(name) ?? "").split("\n").map((line) => line.trim()).filter((line) => line !== "");
        break;
      case "string": {
        const text = String(data.get(name) ?? "");
        value = text === "" ? null : text;
        break;
      }
      default:
        value = String(data.get(name) ?? "");
    }
    const before = s.value === undefined ? null : s.value;
    if (JSON.stringify(value) !== JSON.stringify(before)) {
      out[s.key] = value;
    }
  }
  return out;
}

/** The value a setting would have after `changes` (null: back to its default). */
function valueAfter(changes: Record<string, unknown>, key: string): unknown {
  const s = loadedSettings.find((x) => x.key === key);
  if (s === undefined) return undefined;
  if (!(key in changes)) return s.value;
  const v = changes[key];
  return v === null || v === undefined ? s.default : v;
}

/**
 * A change of output.profile or output.videoBitrateKbps re-encodes every item: say how many and
 * ask first. Resolves true to go ahead (also when the encoding stays the same).
 */
async function confirmEncodingChange(changes: Record<string, unknown>): Promise<boolean> {
  if (!("output.profile" in changes) && !("output.videoBitrateKbps" in changes)) return true;
  const params = new URLSearchParams();
  const profile = valueAfter(changes, "output.profile");
  if (typeof profile === "string") params.set("profile", profile);
  const bitrate = valueAfter(changes, "output.videoBitrateKbps");
  if (typeof bitrate === "number") params.set("videoBitrateKbps", String(bitrate));
  const info = await api<{ current: string; encoding: string; reencodeItems: number }>(
    "GET", "/api/admin/encoding?" + params.toString());
  if (info.encoding === info.current) return true;
  const items = info.reencodeItems === 1 ? "1 item" : `${info.reencodeItems} items`;
  return confirm(`Change the output encoding from ${info.current} to ${info.encoding}?\n\n`
    + `${items} will re-encode (this takes a while and keeps the ingest workers busy). `
    + "Until an item is re-encoded, the program output shows \u201cLoading\u2026\u201d for it; web players are not affected. "
    + "Running program outputs restart: .ts viewers and RTMP pushes reconnect.");
}

function setUpSettings(): void {
  const form = element<HTMLFormElement>("settings-form");
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const changes = changedSettings(form);
    if (Object.keys(changes).length === 0) {
      status("Nothing changed.");
      return;
    }
    void run(form.querySelector(".error"), form, async () => {
      if (!(await confirmEncodingChange(changes))) return;
      const r = await api<SettingsResponse>("PATCH", "/api/admin/settings", changes);
      loadedSettings = withoutFonts(r.items);
      status("Saved " + Object.keys(changes).join(", ") + "." + savedNotice(r));
      renderSettings();
      await loadStorage();
    });
  });
}

// ------------------------------------------------------------------ fonts

let fontsTimer: number | undefined;

/** The Fonts tab (admin.settings): card font, script font downloads, uploads, uncovered characters. */
async function loadFonts(): Promise<void> {
  if (fontsTimer !== undefined) {
    clearTimeout(fontsTimer);
    fontsTimer = undefined;
  }
  const info = await api<FontsInfo>("GET", "/api/admin/fonts");
  renderFonts(info);
  if (busy(info)) {
    fontsTimer = window.setTimeout(() => {
      if (currentTab() === "fonts") void loadFonts().catch((e) => status(message(e)));
    }, 3000);
  }
}

async function patchFontSetting(key: string, value: unknown): Promise<void> {
  await api("PATCH", "/api/admin/settings", { [key]: value });
  status("Saved " + key + ".");
  await loadFonts();
}

function renderFonts(info: FontsInfo): void {
  const root = element("fonts-root");
  const error = h("p", { class: "error", role: "alert" });

  const cardFont = h("select", { "aria-label": "Card font" },
    ...cardFontChoices(info).map((c) => h("option", { value: c.value, text: c.label, selected: c.value === info.cardFont })));
  const saveFont = h("button", { type: "button", text: "Use this font" });
  saveFont.addEventListener("click", () => void run(error, saveFont,
    () => patchFontSetting("cards.font", cardFontSetting(cardFont.value))));

  const file = h("input", { type: "file", accept: ".ttf,.otf,font/ttf,font/otf", "aria-label": "Font file" });
  const upload = h("button", { type: "button", text: "Upload" });
  upload.addEventListener("click", () => void run(error, upload, async () => {
    const picked = file.files?.[0];
    if (picked === undefined) throw new Error("Pick a TrueType or OpenType font file first.");
    const u = await api<{ family: string }>("POST", "/api/admin/fonts/uploads", picked);
    status(`Uploaded ${u.family}. Pick it as the card font to use it.`);
    await loadFonts();
  }));
  const uploads = h("ul", {}, ...info.choices.uploads.map((u) => {
    const del = h("button", { type: "button", text: "Delete" });
    del.addEventListener("click", () => void run(error, del, async () => {
      await api("DELETE", "/api/admin/fonts/uploads/" + encodeURIComponent(u.id));
      status(`Deleted ${u.family}.`);
      await loadFonts();
    }));
    return h("li", {}, `${u.family} (${formatSize(u.size)}) `, del);
  }));

  const auto = h("input", { type: "checkbox", checked: info.autoDownload });
  auto.addEventListener("change", () => void run(error, null,
    () => patchFontSetting("fonts.autoDownload", auto.checked)));
  const cjk = h("select", { "aria-label": "Preferred CJK variant" },
    ...CJK_VARIANTS.map((v) => h("option", { value: v.value, text: v.label, selected: v.value === info.cjkVariant })));
  cjk.addEventListener("change", () => void run(error, null, () => patchFontSetting("fonts.cjkVariant", cjk.value)));

  const all = h("button", { type: "button", text: `Download all (${formatSize(info.missingBytes)})`,
    disabled: info.missingBytes === 0 });
  all.addEventListener("click", () => {
    if (!confirm(downloadAllText(info))) return;
    void run(error, all, async () => {
      await api("POST", "/api/admin/fonts/download-all");
      await loadFonts();
    });
  });

  const rows = info.fonts.map((f) => {
    const action = f.status === "installed"
      ? h("button", { type: "button", text: "Remove" })
      : h("button", { type: "button", text: "Download", disabled: f.status === "queued" || f.status === "downloading" });
    action.addEventListener("click", () => void run(error, action, async () => {
      if (f.status === "installed") {
        await api("DELETE", "/api/admin/fonts/scripts/" + encodeURIComponent(f.id));
      } else {
        await api("POST", "/api/admin/fonts/scripts/" + encodeURIComponent(f.id) + "/download");
      }
      await loadFonts();
    }));
    return h("tr", {}, h("td", { text: f.name }), h("td", { text: f.scripts.join(", ") }),
      h("td", { text: formatSize(f.size) }),
      h("td", { text: statusText(f, (ms) => new Date(ms).toLocaleTimeString()) }), h("td", {}, action));
  });

  const uncovered = info.uncovered.length === 0
    ? h("p", { text: "Every character in the library's titles, artists and albums has a font." })
    : h("table", { class: "list" },
      h("thead", {}, h("tr", {}, h("th", { text: "Character" }), h("th", { text: "Code point" }),
        h("th", { text: "Script" }), h("th", { text: "Items" }), h("th", { text: "Font that has it" }))),
      h("tbody", {}, ...info.uncovered.map((u) => h("tr", {}, h("td", { text: u.char }), h("td", { text: u.codePoint }),
        h("td", { text: u.script }), h("td", { text: String(u.count) }),
        h("td", { text: u.font === undefined ? "none known" : info.fonts.find((f) => f.id === u.font)?.name ?? u.font })))));

  root.replaceChildren(
    h("h3", { text: "Card font" }),
    h("p", { text: "The font card text is drawn with. Characters it lacks fall back to the built-in font, the downloaded script fonts and then any installed font." }),
    h("div", { class: "row" }, cardFont, saveFont),
    h("h4", { text: "Upload a font" }),
    h("p", { text: "A TrueType or OpenType file (at most 32 MiB), e.g. for servers without internet access." }),
    h("div", { class: "row" }, file, upload),
    uploads,
    h("h3", { text: "Script fonts" }),
    h("label", {}, auto, " Download a missing script font when a card needs it"),
    h("label", {}, "Han characters without kana or hangul are drawn as ", cjk),
    h("div", { class: "row" }, all),
    h("table", { class: "list" },
      h("thead", {}, h("tr", {}, h("th", { text: "Font" }), h("th", { text: "Scripts" }), h("th", { text: "Size" }),
        h("th", { text: "Status" }), h("th", { text: "" }))),
      h("tbody", {}, ...rows)),
    h("h3", { text: "Characters without a font" }),
    uncovered,
    error);
}

// ------------------------------------------------------------------ jobs

let jobsView: JobsView | undefined;

/** The Jobs tab (admin.jobs): every encode job, with logs, Retry and Cancel (jobs.ts). */
async function loadJobs(): Promise<void> {
  if (jobsView === undefined) {
    jobsView = mountJobs(element("jobs-root"), {
      base: "/api/admin/jobs",
      admin: true,
      onError: (e) => status(message(e)),
      onDone: (text) => status(text),
    });
  }
  await jobsView.refresh();
}

// ------------------------------------------------------------------ audit log

let auditFilter: AuditFilter = { action: "", actor: "" };
let auditCursor: string | undefined;

/** The Audit log tab (admin.users): newest first, filtered by action and actor, "Load more" pages on. */
async function loadAudit(reset: boolean): Promise<void> {
  const page = await api<AuditPage>("GET", auditPath(auditFilter, reset ? undefined : auditCursor));
  const rows = element("audit-rows");
  if (reset) rows.replaceChildren();
  for (const entry of page.items) rows.append(auditRow(entry));
  auditCursor = page.nextCursor;
  element("audit-more").hidden = auditCursor === undefined;
  element("audit-empty").hidden = rows.childElementCount > 0;
}

function auditRow(e: AuditEntry): HTMLTableRowElement {
  let who: Child = e.actor === undefined ? h("span", { class: "muted", text: "(none)" }) : null;
  if (e.actor !== undefined) {
    const actor = e.actor;
    const link = h("button", { type: "button", class: "link", title: "Show only this actor's entries" },
      actorLabel(actor));
    link.addEventListener("click", () => setAuditFilter({ action: auditFilter.action, actor: actor.id }));
    who = link;
  }
  const action = h("button", { type: "button", class: "link", title: "Show only this action", text: e.action });
  action.addEventListener("click", () => setAuditFilter({ action: e.action, actor: auditFilter.actor }));
  return h("tr", {},
    h("td", { text: formatTime(e.at) }),
    h("td", {}, who),
    h("td", {}, action),
    h("td", { text: e.target ?? "" }),
    h("td", { text: detailsText(e.details) }),
    h("td", { text: e.ip ?? "" }));
}

function setAuditFilter(filter: AuditFilter): void {
  auditFilter = filter;
  const form = element<HTMLFormElement>("audit-filter");
  (form.elements.namedItem("action") as HTMLInputElement).value = filter.action;
  (form.elements.namedItem("actor") as HTMLInputElement).value = filter.actor;
  void run(null, null, () => loadAudit(true));
}

function setUpAudit(): void {
  const list = element("audit-actions");
  for (const a of AUDIT_ACTIONS) list.append(h("option", { value: a }));
  const form = element<HTMLFormElement>("audit-filter");
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    setAuditFilter({ action: formValue(form, "action"), actor: formValue(form, "actor") });
  });
  form.addEventListener("reset", () => setAuditFilter({ action: "", actor: "" }));
  const more = element<HTMLButtonElement>("audit-more");
  more.addEventListener("click", () => void run(null, more, () => loadAudit(false)));
}

// ------------------------------------------------------------------ start

async function start(): Promise<void> {
  // Log in first, and replace a temporary password, before anything else.
  try {
    const me = await requireUser();
    if (me === null) return;
    renderNav(me);
    if (!isAdmin(me)) {
      element("admin-tabs").hidden = true;
      status("You don't have permission for this page.");
      return;
    }
  } catch (e) {
    status(message(e));
    return;
  }
  setUpUsers();
  setUpRoles();
  setUpSettings();
  setUpYtdlp();
  setUpAudit();
  window.addEventListener("hashchange", () => void showTab(), { signal: pageSignal() });
  void showTab();
}

onReady(start);
