// The admin pages (/admin/): users, roles and server settings, on top of /api/admin/... (docs/api.md#admin).
// Each tab loads its data when opened; tabs the user has no permission for show the API's error.
import { api, ApiError } from "./api.js";
import { isAdmin, renderNav, requireUser } from "./session.js";

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
  type: "boolean" | "integer" | "string" | "enum" | "uploadQuota" | "stringList";
  value?: SettingValue;
  default?: SettingValue;
  options?: string[];
}

const GROUPS: Record<string, string> = {
  accounts: "Accounts",
  guests: "Guests",
  library: "Library",
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

const TABS = ["users", "roles", "settings"] as const;
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

// ------------------------------------------------------------------ settings

let loadedSettings: Setting[] = [];

async function loadSettings(): Promise<void> {
  loadedSettings = (await api<{ items: Setting[] }>("GET", "/api/admin/settings")).items;
  renderSettings();
}

function describe(value: SettingValue): string {
  if (value === undefined) return "(none)";
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
    case "boolean":
      input = h("input", { type: "checkbox", name, checked: s.value === true });
      break;
    case "integer":
      input = h("input", { type: "number", name, step: "1", value: String(s.value ?? "") });
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
  const reset = h("button", { type: "button", text: "Default", title: "Reset to " + describe(s.default) });
  reset.addEventListener("click", () => {
    const error = element("settings-form").querySelector<HTMLElement>(".error");
    void run(error, reset, async () => {
      loadedSettings = (await api<{ items: Setting[] }>("PATCH", "/api/admin/settings", { [s.key]: null })).items;
      status(`Reset ${s.key}.`);
      renderSettings();
    });
  });
  return h("div", { class: "setting" }, h("span", { text: s.key }), input, reset,
    h("span", { class: "muted", text: "default: " + describe(s.default) }));
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
      case "integer":
        value = Number(data.get(name));
        break;
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
      loadedSettings = (await api<{ items: Setting[] }>("PATCH", "/api/admin/settings", changes)).items;
      status("Saved " + Object.keys(changes).join(", ") + ".");
      renderSettings();
    });
  });
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
  window.addEventListener("hashchange", () => void showTab());
  void showTab();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => void start());
} else {
  void start();
}
