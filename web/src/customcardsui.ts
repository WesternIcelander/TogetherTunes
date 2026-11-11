// The custom card images panel (minimal; the web UI is provisional): one row per screen with its
// image, warnings, Upload and Delete. Used by the admin page (server-wide) and the room management
// view (the room's own). Rules and texts live in customcards.ts.
import { api } from "./api.js";
import { button, h } from "./dom.js";
import {
  CARD_ACCEPT, checkFile, roomNote, screenPath, screenStatus, specText, type CardScreen, type CardsInfo,
} from "./customcards.js";

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * Loads `base` (`/api/admin/cards` or `/api/rooms/{id}/cards`) and renders it into `root`.
 * `room`: the room's view (its images fall back to the server's; editing needs `canEdit`).
 */
export async function mountCustomCards(root: HTMLElement, base: string, room: boolean): Promise<void> {
  const info = await api<CardsInfo>("GET", base);
  render(root, base, room, info);
}

function render(root: HTMLElement, base: string, room: boolean, info: CardsInfo): void {
  const editable = room ? info.canEdit === true : true;
  const error = h("p", { class: "error", role: "alert" });
  const reload = async (): Promise<void> => {
    render(root, base, room, await api<CardsInfo>("GET", base));
  };
  const onError = (e: unknown): void => { error.textContent = message(e); };
  const note = room ? roomNote(info) : undefined;
  const rows = info.screens.map((s) => row(s, base, room, editable, reload, onError));
  const parts: Node[] = [h("p", { class: "muted small", text: specText(info.output) })];
  if (room && !editable) {
    parts.push(h("p", { class: "muted small",
      text: "Only the room's owner, with a role that allows custom card images, changes them." }));
  }
  if (note !== undefined) parts.push(h("p", { class: "warning", text: note }));
  parts.push(h("ul", { class: "custom-cards" }, ...rows), error);
  root.replaceChildren(...parts);
}

function row(s: CardScreen, base: string, room: boolean, editable: boolean, reload: () => Promise<void>,
  onError: (e: unknown) => void): HTMLElement {
  const li = h("li", { class: "panel" },
    h("strong", { text: s.label }), " ", h("span", { class: "muted small", text: screenStatus(s, room) }));
  if (s.image !== undefined) {
    li.append(h("div", {}, h("img", { src: s.image.url, alt: s.label + " image", class: "overlay-preview",
      loading: "lazy" })));
  }
  for (const w of s.warnings) {
    li.append(h("p", { class: "warning", role: "note", text: w.message }));
  }
  if (!editable) return li;
  const file = h("input", { type: "file", accept: CARD_ACCEPT, "aria-label": s.label + " image file" });
  const upload = button(s.image !== undefined ? "Replace" : "Upload", async () => {
    const picked = file.files?.[0];
    if (picked === undefined) throw new Error("Pick an image first.");
    const problem = checkFile(picked);
    if (problem !== null) throw new Error(problem);
    await api("PUT", screenPath(base, s.screen), picked);
    await reload();
  }, onError);
  li.append(h("div", { class: "row" }, file, upload));
  if (s.image !== undefined) {
    li.append(button("Delete", async () => {
      if (!confirm(`Delete the ${s.label.toLowerCase()} image? The ${room ? "server's image or the " : ""}built-in card is shown again.`)) return;
      await api("DELETE", screenPath(base, s.screen));
      await reload();
    }, onError, "danger"));
  }
  return li;
}
