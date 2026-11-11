// The room's management view (/rooms/{idOrSlug}/manage; docs/rooms.md).
//
// Four tabs: settings (name, link name, visibility, control mode and every room setting; options
// the server has turned off are greyed out with "disabled by the server admin"; only the owner
// saves, moderators see them read-only; the owner or rooms.moderateAny may delete the room),
// members (roles as the viewer may give them, ownership transfer for the owner, adding by
// username) and guest bans (list, unban; a guest is banned from the room page's history, with the
// IP-mode warning first) and card images (the room's custom card images, customcardsui.ts). The
// request history is gone: the room page's history replaces it.
import { api, ApiError } from "./api.js";
import { roomCardsPath } from "./customcards.js";
import { mountCustomCards } from "./customcardsui.js";
import type { GuestBan } from "./guestban.js";
import { actorLabel, type ActorRef } from "./actor.js";
import { navigate, pageSignal, reloadPage } from "./nav.js";
import { button, formatTime, h } from "./dom.js";
import { type Page } from "./media.js";
import {
  adjustedNotice, assignableRoles, buildPatch, canManageMember, coupledChange, describeDefault, getPath, GROUPS,
  OVERLAY_PREVIEW_KEYS, overlayNotes, overlayPreviewUrl, parseInput, serverCapNote, serverDisabledReason,
  SETTINGS, thresholdToPercent, type ServerSwitches, type SettingDef,
} from "./roomsettings.js";
import {
  checkPasswordChange, element, here, loadMe, LOGIN_PAGE, message, onReady, renderNav, withNext, type Me,
} from "./session.js";

interface RoomView {
  id: string;
  slug: string;
  name: string;
  owner: ActorRef;
  visibility: string;
  controlMode: string;
  settings: Record<string, unknown>;
  createdAt: number;
  you: { role?: string; canEdit: boolean; canDelete: boolean; canModerate: boolean };
}

interface Member { user: ActorRef; role: string; joinedAt: number }

/** The room from `/rooms/{idOrSlug}/manage` (or `?room=` when the page is opened as a plain file). */
function roomFromLocation(): string {
  const m = /^\/rooms\/([^/]+)\/manage\/?$/.exec(location.pathname);
  if (m !== null && m[1] !== undefined) {
    try {
      return decodeURIComponent(m[1]);
    } catch {
      return "";
    }
  }
  return new URLSearchParams(location.search).get("room") ?? "";
}

let me: Me;
let room: RoomView;
let switches: ServerSwitches | undefined;

function roomPath(): string {
  return "/api/rooms/" + encodeURIComponent(room.id);
}

function isOwner(): boolean {
  return room.you.role === "owner";
}

function setStatus(text: string): void {
  const el = element("manage-status");
  el.textContent = text;
  el.hidden = text === "";
}

// ------------------------------------------------------------------ tabs

const TABS = ["settings", "members", "bans", "cards"] as const;
type Tab = typeof TABS[number];

function currentTab(): Tab {
  const name = location.hash.replace(/^#/, "");
  return (TABS as readonly string[]).includes(name) ? name as Tab : "settings";
}

const loaded = new Set<Tab>();

async function showTab(): Promise<void> {
  const tab = currentTab();
  for (const t of TABS) element("tab-" + t).hidden = t !== tab;
  for (const a of Array.from(document.querySelectorAll<HTMLAnchorElement>("#manage-tabs a"))) {
    if (a.dataset["tab"] === tab) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  if (loaded.has(tab)) return;
  loaded.add(tab);
  if (tab === "settings") renderSettings();
  else if (tab === "members") await loadMembers(true);
  else if (tab === "cards") await mountCustomCards(element("cards-root"), roomCardsPath(room.id), true);
  else await loadBans(true);
}

// ------------------------------------------------------------------ settings

/** The inputs of the room settings, by dotted name. */
const inputs = new Map<string, HTMLInputElement | HTMLSelectElement>();

function settingInput(def: SettingDef, value: unknown, disabled: boolean): HTMLInputElement | HTMLSelectElement {
  const id = "setting-" + def.key.replace(/\./g, "-");
  if (def.kind === "bool") {
    const input = h("input", { type: "checkbox", id, name: def.key });
    input.checked = value === true;
    input.disabled = disabled;
    return input;
  }
  if (def.kind === "select") {
    const select = h("select", { id, name: def.key });
    for (const [v, label] of def.options ?? []) select.append(h("option", { value: v, text: label }));
    select.value = typeof value === "string" ? value : String(def.default);
    select.disabled = disabled;
    return select;
  }
  const input = h("input", { id, name: def.key });
  if (def.kind === "int") {
    input.type = "number";
    input.step = "1";
    if (def.min !== undefined) input.min = String(def.min);
    if (def.max !== undefined) input.max = String(def.max);
    input.inputMode = "numeric";
    input.value = typeof value === "number" ? String(value) : String(def.default);
  } else if (def.kind === "threshold") {
    input.inputMode = "decimal";
    input.placeholder = "Off";
    input.size = 6;
    input.value = thresholdToPercent(value);
  } else {
    input.type = "url";
    if (def.max !== undefined) input.maxLength = def.max;
    input.value = typeof value === "string" ? value : "";
    input.placeholder = "none";
  }
  input.disabled = disabled;
  return input;
}

function renderSettings(): void {
  const readOnly = !isOwner();
  element("settings-readonly").hidden = !readOnly;
  element("settings-save").hidden = readOnly;
  const form = element<HTMLFormElement>("settings-form");
  element<HTMLInputElement>("room-field-name").value = room.name;
  element<HTMLInputElement>("room-field-slug").value = room.slug;
  element("room-field-slug-preview").textContent = room.slug;
  element<HTMLSelectElement>("room-field-visibility").value = room.visibility;
  element<HTMLSelectElement>("room-field-control").value = room.controlMode;
  for (const id of ["room-field-name", "room-field-slug", "room-field-visibility", "room-field-control"]) {
    element<HTMLInputElement>(id).disabled = readOnly;
  }
  inputs.clear();
  const groups = element("settings-groups");
  groups.replaceChildren();
  for (const [group, title] of GROUPS) {
    const fieldset = h("fieldset", { class: "panel" }, h("legend", { text: title }));
    if (group === "outputs") {
      fieldset.append(h("p", { class: "muted small",
        text: "Turning outputs on and off, their stream URLs and the RTMP target and key are on the room page, under \"Stream outputs\"." }));
    }
    for (const def of SETTINGS) {
      if (def.group !== group) continue;
      const reason = serverDisabledReason(def.key, switches);
      const cap = reason === null ? serverCapNote(def.key, getPath(room.settings, def.key), switches) : null;
      const input = settingInput(def, getPath(room.settings, def.key), readOnly || reason !== null);
      inputs.set(def.key, input);
      if (def.kind === "bool") input.addEventListener("change", () => { coupleInputs(def.key); });
      const label = h("label", { for: input.id, text: def.label });
      const row = h("div", { class: "setting" + (reason !== null ? " server-disabled" : "") },
        label, input,
        h("span", { class: "muted small", text: "default: " + describeDefault(def) }),
        reason !== null ? h("span", { class: "small server-disabled-note", text: reason }) : null,
        cap !== null ? h("span", { class: "muted small setting-help", text: cap }) : null,
        def.help !== undefined ? h("span", { class: "muted small setting-help", text: def.help }) : null);
      if (reason !== null) row.title = reason;
      fieldset.append(row);
    }
    if (group === "overlay") appendOverlayPreview(fieldset);
    groups.append(fieldset);
  }
  element("room-delete").hidden = !room.you.canDelete;
  form.hidden = false;
}

/** The pending preview refresh (debounced while typing). */
let previewTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * Under the overlay settings (when the server allows the overlay): notes on where it shows and
 * whether music videos get it, and a preview image of the room's idle card with the overlay as
 * the form has it (unsaved values too), refreshed when corner, size or margin change.
 */
function appendOverlayPreview(fieldset: HTMLElement): void {
  const notes = overlayNotes(switches);
  if (notes.length === 0) return;
  for (const note of notes) fieldset.append(h("p", { class: "muted small", text: note }));
  const img = h("img", { class: "overlay-preview", alt: "Preview of the QR overlay on the stream" });
  const status = h("p", { class: "muted small", text: "Preview (the QR code uses the saved link name):" });
  img.addEventListener("error", () => { status.textContent = "The preview could not be loaded."; });
  img.addEventListener("load", () => {
    status.textContent = "Preview (the QR code uses the saved link name):";
  });
  const refresh = (): void => {
    const raw: Record<string, string | undefined> = {};
    for (const key of OVERLAY_PREVIEW_KEYS) raw[key] = inputs.get(key)?.value;
    const url = overlayPreviewUrl(room.id, raw);
    if (img.getAttribute("src") !== url) img.src = url;
  };
  for (const key of OVERLAY_PREVIEW_KEYS) {
    const input = inputs.get(key);
    if (input === undefined) continue;
    const later = (): void => {
      if (previewTimer !== undefined) clearTimeout(previewTimer);
      previewTimer = setTimeout(refresh, 400);
    };
    input.addEventListener("input", later);
    input.addEventListener("change", later);
  }
  fieldset.append(status, img);
  refresh();
}

/**
 * Uploads need library requests: after a switch changed, flips its companion in the form (unless
 * greyed out: the server adjusts those) and says so.
 */
function coupleInputs(key: string): void {
  const input = inputs.get(key);
  if (!(input instanceof HTMLInputElement)) return;
  const change = coupledChange(key, input.checked, (k) => {
    const other = inputs.get(k);
    return other instanceof HTMLInputElement ? other.checked : getPath(room.settings, k);
  });
  if (change === null) return;
  const other = inputs.get(change.key);
  if (other instanceof HTMLInputElement && !other.disabled) {
    other.checked = change.value;
    element("settings-saved").textContent = change.notice;
  }
}

/** The changes in the settings form: the room fields and the settings merge patch. */
function settingsChanges(): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  const name = element<HTMLInputElement>("room-field-name").value.trim();
  const slug = element<HTMLInputElement>("room-field-slug").value.trim().toLowerCase();
  const visibility = element<HTMLSelectElement>("room-field-visibility").value;
  const controlMode = element<HTMLSelectElement>("room-field-control").value;
  if (name !== room.name) body["name"] = name;
  if (slug !== room.slug) body["slug"] = slug;
  if (visibility !== room.visibility) body["visibility"] = visibility;
  if (controlMode !== room.controlMode) body["controlMode"] = controlMode;
  const values: Record<string, unknown> = {};
  for (const def of SETTINGS) {
    const input = inputs.get(def.key);
    // A greyed-out setting is left as it is (its stored value still counts once the server allows it).
    if (input === undefined || input.disabled) continue;
    const raw = input instanceof HTMLInputElement && input.type === "checkbox" ? input.checked : input.value;
    values[def.key] = parseInput(def, raw);
  }
  const patch = buildPatch(room.settings, values);
  if (patch !== null) body["settings"] = patch;
  return body;
}

function setUpSettings(): void {
  const form = element<HTMLFormElement>("settings-form");
  const error = element("settings-error");
  const saved = element("settings-saved");
  element<HTMLInputElement>("room-field-slug").addEventListener("input", (e) => {
    element("room-field-slug-preview").textContent = (e.target as HTMLInputElement).value.trim().toLowerCase();
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    error.textContent = "";
    saved.textContent = "";
    let body: Record<string, unknown>;
    try {
      body = settingsChanges();
    } catch (e) {
      error.textContent = message(e);
      return;
    }
    if (Object.keys(body).length === 0) {
      saved.textContent = "Nothing changed.";
      return;
    }
    const save = element<HTMLButtonElement>("settings-save");
    save.disabled = true;
    api<RoomView & { adjusted?: string[] }>("PATCH", roomPath(), body).then((updated) => {
      const notice = adjustedNotice(updated.adjusted, (k) => getPath(updated.settings, k));
      room = updated;
      renderHeader();
      renderSettings();
      saved.textContent = notice === null ? "Saved." : "Saved. " + notice;
    }).catch((e: unknown) => {
      error.textContent = message(e);
    }).finally(() => { save.disabled = false; });
  });
  element("room-delete-button").addEventListener("click", () => {
    if (!confirm("Delete the room \"" + room.name + "\"? Its queue, members and history are deleted too.")) return;
    const b = element<HTMLButtonElement>("room-delete-button");
    b.disabled = true;
    api("DELETE", roomPath()).then(() => { navigate("/"); }).catch((e: unknown) => {
      element("room-delete-error").textContent = message(e);
      b.disabled = false;
    });
  });
}

// ------------------------------------------------------------------ members

const ROLE_TEXT: Record<string, string> = {
  owner: "Owner", moderator: "Moderator", member: "Member", banned: "Banned",
};

let memberCursor: string | undefined;

async function loadMembers(reset: boolean): Promise<void> {
  const rows = element("member-rows");
  if (reset) {
    rows.replaceChildren();
    memberCursor = undefined;
  }
  const params = new URLSearchParams({ limit: "100" });
  if (memberCursor !== undefined) params.set("cursor", memberCursor);
  try {
    const page = await api<Page<Member>>("GET", roomPath() + "/members?" + params);
    for (const m of page.items) rows.append(memberRow(m));
    memberCursor = page.nextCursor;
    element("member-more").hidden = page.nextCursor === undefined;
  } catch (e) {
    element("member-error").textContent = message(e);
  }
}

function memberError(e: unknown): void {
  element("member-error").textContent = message(e);
}

function memberRow(m: Member): HTMLTableRowElement {
  const myId = me.kind === "user" ? me.id : "";
  const actions = h("div", { class: "row" });
  const target = { id: m.user.id, role: m.role };
  if (canManageMember(room.you.role, myId, target)) {
    const select = h("select", { "aria-label": "Role of " + m.user.name });
    for (const r of assignableRoles(room.you.role)) select.append(h("option", { value: r, text: ROLE_TEXT[r] ?? r }));
    select.value = m.role;
    select.addEventListener("change", () => {
      const role = select.value;
      if (role === "banned" && !confirm("Ban " + m.user.name + " from this room?")) {
        select.value = m.role;
        return;
      }
      select.disabled = true;
      element("member-error").textContent = "";
      api<Member>("PUT", roomPath() + "/members/" + encodeURIComponent(m.user.id), { role })
        .then((updated) => { row.replaceWith(memberRow(updated)); })
        .catch((e: unknown) => { memberError(e); select.value = m.role; select.disabled = false; });
    });
    actions.append(select);
    if (isOwner() && m.role !== "banned") {
      actions.append(button("Make owner", async () => {
        if (!confirm("Hand this room over to " + m.user.name + "? You become a moderator and can't undo this yourself.")) {
          return;
        }
        await api("PUT", roomPath() + "/members/" + encodeURIComponent(m.user.id), { role: "owner" });
        reloadPage();
      }, memberError, "small"));
    }
    actions.append(button(m.role === "banned" ? "Lift ban" : "Remove", async () => {
      await api("DELETE", roomPath() + "/members/" + encodeURIComponent(m.user.id));
      row.remove();
    }, memberError, "small"));
  } else if (m.user.id === myId && m.role !== "owner") {
    actions.append(button("Leave", async () => {
      if (!confirm("Leave this room? You lose your role in it.")) return;
      await api("DELETE", roomPath() + "/members/" + encodeURIComponent(m.user.id));
      navigate("/rooms/" + encodeURIComponent(room.slug));
    }, memberError, "small"));
  }
  const row = h("tr", {},
    h("td", {}, actorLabel(m.user)),
    h("td", { text: ROLE_TEXT[m.role] ?? m.role }),
    h("td", { class: "muted small", text: formatTime(m.joinedAt) }),
    h("td", { class: "actions" }, actions));
  return row;
}

function setUpMembers(): void {
  element("member-more").addEventListener("click", () => void loadMembers(false));
  const roles = assignableRoles(room.you.role);
  const select = element<HTMLSelectElement>("member-add-role");
  for (const r of roles) select.append(h("option", { value: r, text: ROLE_TEXT[r] ?? r }));
  select.value = "member";
  const form = element<HTMLFormElement>("member-add");
  form.hidden = roles.length === 0;
  const error = element("member-add-error");
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    error.textContent = "";
    const username = String(new FormData(form).get("username") ?? "").trim();
    const role = select.value;
    if (username === "") return;
    const submit = form.querySelector("button");
    if (submit !== null) submit.disabled = true;
    api<Member>("POST", roomPath() + "/members", { username, role }).then(() => {
      form.reset();
      select.value = "member";
      return loadMembers(true);
    }).catch((e: unknown) => {
      error.textContent = e instanceof ApiError && e.code === "user_not_found" ? "No user called " + username + "." : message(e);
    }).finally(() => { if (submit !== null) submit.disabled = false; });
  });
}

// ------------------------------------------------------------------ guest bans

let banCursor: string | undefined;

function banError(e: unknown): void {
  element("ban-error").textContent = message(e);
}

async function loadBans(reset: boolean): Promise<void> {
  const rows = element("ban-rows");
  if (reset) {
    rows.replaceChildren();
    banCursor = undefined;
  }
  const params = new URLSearchParams({ limit: "100" });
  if (banCursor !== undefined) params.set("cursor", banCursor);
  try {
    const page = await api<Page<GuestBan>>("GET", roomPath() + "/guest-bans?" + params);
    for (const b of page.items) rows.append(banRow(b));
    banCursor = page.nextCursor;
    element("ban-more").hidden = page.nextCursor === undefined;
    element("ban-empty").hidden = rows.children.length > 0;
  } catch (e) {
    banError(e);
  }
}

function identifiedBy(b: GuestBan): HTMLElement {
  const cell = h("td", {}, b.identifiedBy === "ip" ? "IP address" : "Browser cookie");
  // The address only ever arrives for viewers with ip.view.
  if (b.ip !== undefined) cell.append(" ", h("code", { text: b.ip }));
  if (b.warning !== undefined) cell.append(h("div", { class: "muted small", text: b.warning }));
  return cell;
}

function banRow(b: GuestBan): HTMLTableRowElement {
  const row = h("tr", {},
    h("td", {}, actorLabel(b.guest)),
    identifiedBy(b),
    h("td", { class: "muted small", text: b.bannedAt !== undefined ? formatTime(b.bannedAt) : "" }),
    h("td", { class: "actions" }, button("Unban", async () => {
      await api("DELETE", roomPath() + "/guest-bans/" + encodeURIComponent(b.guest.id));
      row.remove();
      element("ban-empty").hidden = element("ban-rows").children.length > 0;
    }, banError, "small")));
  return row;
}

// ------------------------------------------------------------------ start

function renderHeader(): void {
  document.title = "Manage " + room.name + " · TogetherTunes";
  element("manage-name").textContent = room.name;
  element<HTMLAnchorElement>("manage-back").href = "/rooms/" + encodeURIComponent(room.slug);
  element("manage-role").textContent = room.you.role === "owner" ? "You own this room"
    : "You moderate this room (settings are the owner's)";
}

async function start(): Promise<void> {
  try {
    me = await loadMe();
  } catch (e) {
    setStatus(message(e));
    return;
  }
  if (!checkPasswordChange(me)) return;
  renderNav(me);
  if (me.kind !== "user") {
    element("manage-status").replaceChildren("Only the room's owner and moderators manage it. ",
      h("a", { href: withNext(LOGIN_PAGE, here()), text: "Log in" }), ".");
    return;
  }
  const ref = roomFromLocation();
  try {
    room = await api<RoomView>("GET", "/api/rooms/" + encodeURIComponent(ref));
  } catch (e) {
    setStatus(e instanceof ApiError && e.status === 404 ? "There is no room at this link." : message(e));
    return;
  }
  renderHeader();
  if (!room.you.canModerate) {
    element("manage-status").replaceChildren("Only the room's owner and moderators manage it. ",
      h("a", { href: "/rooms/" + encodeURIComponent(room.slug), text: "Back to the room" }), ".");
    return;
  }
  try {
    switches = await api<ServerSwitches>("GET", roomPath() + "/server-switches");
  } catch {
    // Without them nothing is greyed out; the settings still save, and the server ANDs them where used.
    switches = undefined;
  }
  setStatus("");
  element("manage-tabs").hidden = false;
  setUpSettings();
  setUpMembers();
  element("ban-more").addEventListener("click", () => void loadBans(false));
  window.addEventListener("hashchange", () => void showTab(), { signal: pageSignal() });
  await showTab();
}

onReady(start);
