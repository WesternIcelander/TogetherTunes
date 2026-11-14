// The rooms page (/rooms/): the rooms the caller can find (`GET /api/rooms`: public rooms plus the
// caller's own, moderated and member rooms; guests and anonymous visitors get the public ones) with
// "Load more", each linking to its room page, and for users with rooms.create a "Create room" form
// (`POST /api/rooms`) that shows field errors and then goes to the new room.
import { api, ApiError } from "./api.js";
import { actorLabel } from "./actor.js";
import { h } from "./dom.js";
import type { Page } from "./media.js";
import {
  canCreateRooms, controlModeLabel, createBody, fieldForError, roleLabel, roomPagePath, slugProblem, visibilityLabel,
  type CreateField, type RoomListEntry,
} from "./roomlist.js";
import { navigate } from "./nav.js";
import { checkPasswordChange, element, formValue, loadMe, message, onReady, renderNav, type Me } from "./session.js";

let cursor: string | undefined;

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
    h("td", { class: "small" + (role === "" ? " muted" : ""), text: role === "" ? "–" : role }));
}

async function load(reset: boolean): Promise<void> {
  const tbody = element<HTMLTableSectionElement>("rooms-rows");
  if (reset) cursor = undefined;
  const params = new URLSearchParams({ limit: "50" });
  if (cursor !== undefined) params.set("cursor", cursor);
  const page = await api<Page<RoomListEntry>>("GET", "/api/rooms?" + params.toString());
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
    api<RoomListEntry>("POST", "/api/rooms", body).then((room) => {
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
  let me: Me;
  try {
    me = await loadMe();
  } catch (e) {
    status.textContent = message(e);
    return;
  }
  if (!checkPasswordChange(me)) return;
  renderNav(me);

  const intro = element("rooms-intro");
  intro.textContent = me.kind === "user"
    ? "Public rooms and the rooms you own, moderate or are a member of."
    : "Public rooms. Unlisted rooms are reached through their link.";

  if (canCreateRooms(me)) {
    element("create-panel").hidden = false;
    setupCreateForm();
  }

  const more = element<HTMLButtonElement>("rooms-more");
  more.addEventListener("click", () => {
    more.disabled = true;
    load(false).catch(showError).finally(() => { more.disabled = false; });
  });

  try {
    await load(true);
  } catch (e) {
    showError(e);
  }
  status.hidden = true;
  element("rooms-body").hidden = false;
}

onReady(start);
