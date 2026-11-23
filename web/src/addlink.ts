// The "Add from link" dialog (docs/ui.md#library-rules): a link (typed, or dropped on a playlist)
// goes into the library and, with a playlist, into it (`POST /api/imports/youtube` with
// `playlistId` and `beforeEntryId`). An uncategorized video asks for a type, with the option to
// leave it uncategorized; an existing item is used as it is (the server decides). A link dropped on a
// library view (dragdrop.ts libraryLinkPreset) presets that view's type: Movies and Videos theirs,
// Uncategorized Items "leave uncategorized", Shows asks for the show's name, season and episode here.
import { api } from "./api.js";
import { withClassification } from "./classifyprompt.js";
import { h } from "./dom.js";
import { libraryLinkPreset, libraryTargetName, type LibraryTarget } from "./dragdrop.js";
import { mediaTypeBody, type MediaType } from "./mediatypes.js";
import { refreshLibrary } from "./nav.js";
import { message } from "./session.js";

export interface AddLinkOptions {
  /** The playlist it goes into (else the library only). */
  playlistId?: string;
  /** Where in the playlist (an entry id); the end when left out. */
  beforeEntryId?: string;
  /** A link to start with (a dropped one). */
  url?: string;
  /** The library view it goes into (a dropped link), named in the dialog. */
  targetName?: string;
  /** The media type it gets where it needs one (no type question then). */
  mediaType?: MediaType;
  /** Ask for the show's name, season and episode (it gets the type show where it needs one). */
  showFields?: boolean;
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
  const showName = h("input", { name: "showName", maxlength: "300" });
  const season = h("input", { name: "season", inputmode: "numeric", placeholder: "none" });
  const episode = h("input", { name: "episode", inputmode: "numeric" });
  const showFields = o.showFields === true
    ? h("div", {}, h("label", {}, "Show name", showName), h("label", {}, "Season", season), h("label", {}, "Episode", episode))
    : null;
  const form = h("form", { class: "form", role: "dialog", "aria-modal": "true", "aria-label": "Add from link" },
    h("label", {}, o.targetName !== undefined ? "Enter a link to add to " + o.targetName + ":" : "Enter a link:", input),
    h("p", { class: "muted small", text: "A link to a song or video from YouTube or other music services." }),
    showFields, status, error, h("div", { class: "row" }, add, cancel));
  const overlay = h("div", { class: "classify-prompt add-link" }, form);
  const close = (): void => overlay.remove();
  cancel.addEventListener("click", close);
  overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(); });
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    const url = input.value.trim();
    if (url === "") return;
    let preset: Record<string, unknown> = o.mediaType !== undefined ? { mediaType: o.mediaType } : {};
    if (o.showFields === true) {
      const r = mediaTypeBody("show", showName.value, season.value, episode.value);
      if ("error" in r) {
        error.textContent = r.error;
        return;
      }
      preset = { ...r.body };
    }
    add.disabled = true;
    error.textContent = "";
    status.textContent = "Looking up the link…";
    const body: Record<string, unknown> = { url, addToLibrary: true, ...preset };
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

/** Add from link for a link dropped on a library view, with that view's type preset (libraryLinkPreset). */
export function openAddLinkTo(target: LibraryTarget, url: string | undefined, onAdded: (text: string) => void): void {
  const preset = libraryLinkPreset(target);
  openAddLink({
    url,
    targetName: libraryTargetName(target),
    ...(preset.kind === "type" ? { mediaType: preset.mediaType } : {}),
    ...(preset.kind === "show" ? { showFields: true } : {}),
    onAdded,
  });
}
