// The rooms page (/rooms/): the rooms the caller can find (`GET /api/rooms`: public rooms plus the
// caller's own, moderated and member rooms; guests and anonymous visitors get the public ones), each
// with its listener count, a search box (`?q=`: an unlisted room's exact link name or link comes
// first) and "Load more", each linking to its room page, and for users with rooms.create a "Create
// room" form (`POST /api/rooms`; a user's alone state moves into the new room, `moveAloneSession`)
// that shows field errors and then goes to the new room (switched to in place).
import { api, ApiError } from "./api.js";
import { actorLabel } from "./actor.js";
import { h } from "./dom.js";
import type { Page } from "./media.js";
import {
  canCreateRooms, controlModeLabel, createBody, fieldForError, roleLabel, roomPagePath, slugProblem, visibilityLabel,
  type CreateField, type RoomListEntry,
} from "./roomlist.js";
import { navigate, pageSignal } from "./nav.js";
import { createRoomBody } from "./alone.js";
import { checkPasswordChange, element, formValue, loadMe, message, onReady, renderNav, type Me } from "./session.js";

let cursor: string | undefined;
let me: Me | undefined;
/** The search the list shows. */
let query = "";

function showError(e: unknown): void {
  element("rooms-error").textContent = message(e);
}

function roomRow(room: RoomListEntry): HTMLTableRowElement {
  const role = roleLabel(room.you.role);
  return h("tr", {},
    h("td", {}, h("a", { href: roomPagePath(room), text: room.name, class: "room-name" })),
    h("td", {}, actorLabel(room.owner)),
    h("td", { class: "small", text: visibilityLabel(room.visibility) }),
    h("td", { class: "small", text: controlModeLabel(room.controlMode) }),
    h("td", { class: "small" + (role === "" ? " muted" : ""), text: role === "" ? "–" : role }),
    h("td", { class: "small num", title: "In the room now", text: String(room.listeners ?? 0) }));
}

async function load(reset: boolean): Promise<void> {
  const tbody = element<HTMLTableSectionElement>("rooms-rows");
  if (reset) cursor = undefined;
  const q = query;
  const params = new URLSearchParams({ limit: "50" });
  if (cursor !== undefined) params.set("cursor", cursor);
  if (q !== "") params.set("q", q);
  const page = await api<Page<RoomListEntry>>("GET", "/api/rooms?" + params.toString());
  if (q !== query) return; // overtaken by a newer search
  if (reset) tbody.replaceChildren();
  for (const room of page.items) tbody.append(roomRow(room));
  cursor = page.nextCursor;
  element("rooms-more").hidden = cursor === undefined;
  element("rooms-table").hidden = tbody.childElementCount === 0;
  element("rooms-empty").hidden = tbody.childElementCount > 0;
}

const FIELDS: CreateField[] = ["name", "slug", "visibility", "controlMode"];

function fieldError(field: CreateField): HTMLElement {
  return element("create-" + field + "-error");
}

function clearFieldErrors(): void {
  for (const f of FIELDS) fieldError(f).textContent = "";
  element("create-error").textContent = "";
}

function setupCreateForm(): void {
  const form = element<HTMLFormElement>("create-room");
  const slugInput = element<HTMLInputElement>("create-slug");
  slugInput.addEventListener("input", () => {
    fieldError("slug").textContent = slugProblem(slugInput.value) ?? "";
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearFieldErrors();
    const body = createBody({
      name: formValue(form, "name"),
      slug: formValue(form, "slug"),
      visibility: formValue(form, "visibility"),
      controlMode: formValue(form, "controlMode"),
    });
    if (body.name === "") {
      fieldError("name").textContent = "A name is required";
      return;
    }
    const problem = slugProblem(body.slug ?? "");
    if (problem !== null) {
      fieldError("slug").textContent = problem;
      return;
    }
    const buttons = Array.from(form.querySelectorAll("button"));
    buttons.forEach((b) => { b.disabled = true; });
    api<RoomListEntry>("POST", "/api/rooms", createRoomBody(body, me)).then((room) => {
      navigate(roomPagePath(room));
    }).catch((e: unknown) => {
      buttons.forEach((b) => { b.disabled = false; });
      const field = e instanceof ApiError ? fieldForError(e.code) : null;
      (field !== null ? fieldError(field) : element("create-error")).textContent = message(e);
    });
  });
}

async function start(): Promise<void> {
  const status = element("rooms-status");
  let who: Me;
  try {
    who = await loadMe();
  } catch (e) {
    status.textContent = message(e);
    return;
  }
  me = who;
  if (!checkPasswordChange(who)) return;
  renderNav(who);

  const intro = element("rooms-intro");
  intro.textContent = who.kind === "user"
    ? "Public rooms and the rooms you own, moderate or are a member of."
    : "Public rooms. Unlisted rooms are reached through their link.";

  if (canCreateRooms(who)) {
    element("create-panel").hidden = false;
    setupCreateForm();
  }
  // New Room (the sidebar's Rooms tab) links to the form.
  const focusCreate = (): void => {
    if (location.hash !== "#create-room" || element("create-panel").hidden) return;
    element("create-panel").scrollIntoView({ block: "start" });
    element<HTMLInputElement>("create-name").focus();
  };
  window.addEventListener("hashchange", focusCreate, { signal: pageSignal() });

  const more = element<HTMLButtonElement>("rooms-more");
  more.addEventListener("click", () => {
    more.disabled = true;
    load(false).catch(showError).finally(() => { more.disabled = false; });
  });

  const search = element<HTMLInputElement>("rooms-search");
  let timer: number | undefined;
  search.addEventListener("input", () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => {
      query = search.value.trim();
      element("rooms-error").textContent = "";
      load(true).catch(showError);
    }, 250);
  });

  try {
    await load(true);
  } catch (e) {
    showError(e);
  }
  status.hidden = true;
  element("rooms-body").hidden = false;
  focusCreate();
}

onReady(start);
