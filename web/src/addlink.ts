// The "Add from link" dialog (docs/ui.md#library-rules): a link (typed, or dropped on a playlist)
// goes into the library and, with a playlist, into it (`POST /api/imports/youtube` with
// `playlistId` and `beforeEntryId`). An uncategorized video asks for a type, with the option to
// leave it uncategorized; an existing item is used as it is (the server decides).
import { api } from "./api.js";
import { withClassification } from "./classifyprompt.js";
import { h } from "./dom.js";
import { refreshLibrary } from "./nav.js";
import { message } from "./session.js";

export interface AddLinkOptions {
  /** The playlist it goes into (else the library only). */
  playlistId?: string;
  /** Where in the playlist (an entry id); the end when left out. */
  beforeEntryId?: string;
  /** A link to start with (a dropped one). */
  url?: string;
  /** Called after it was added, with a line to show. */
  onAdded(text: string): void;
}

/** Opens the modal dialog. */
export function openAddLink(o: AddLinkOptions): void {
  const input = h("input", { type: "url", name: "url", required: true, placeholder: "https://…",
    "aria-label": "Link", class: "grow", value: o.url ?? "" });
  const error = h("p", { class: "error", role: "alert" });
  const status = h("p", { class: "muted small", role: "status" });
  const add = h("button", { type: "submit", text: "Add" });
  const cancel = h("button", { type: "button", text: "Cancel" });
  const form = h("form", { class: "form", role: "dialog", "aria-modal": "true", "aria-label": "Add from link" },
    h("label", {}, "Enter a link:", input), status, error, h("div", { class: "row" }, add, cancel));
  const overlay = h("div", { class: "classify-prompt add-link" }, form);
  const close = (): void => overlay.remove();
  cancel.addEventListener("click", close);
  overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(); });
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    const url = input.value.trim();
    if (url === "") return;
    add.disabled = true;
    error.textContent = "";
    status.textContent = "Looking up the link…";
    const body: Record<string, unknown> = { url, addToLibrary: true };
    if (o.playlistId !== undefined) body["playlistId"] = o.playlistId;
    if (o.playlistId !== undefined && o.beforeEntryId !== undefined) body["beforeEntryId"] = o.beforeEntryId;
    withClassification((c) => api<{ itemId: string; status: string }>("POST", "/api/imports/youtube",
      c === undefined ? body : { ...body, ...c }), url, true)
      .then((r) => {
        if (r === null) {
          status.textContent = "";
          return;
        }
        close();
        o.onAdded(r.status === "ready" ? "Added." : "Added; it is being fetched and will be ready shortly.");
        refreshLibrary();
      })
      .catch((e: unknown) => {
        status.textContent = "";
        error.textContent = message(e);
      })
      .finally(() => { add.disabled = false; });
  });
  document.body.append(overlay);
  input.focus();
  if (o.url !== undefined) input.select();
}
