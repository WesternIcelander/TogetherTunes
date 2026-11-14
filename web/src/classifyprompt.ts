// The classify prompt (docs/media-types.md#media-types): asks for a media type when an add to a
// library comes back with `409 needs_classification`: a modal dialog in the app's dialog style (type, and
// show name, season and episode for a show); the rules and the suggestion are in the DOM-free classify.ts.
import { api } from "./api.js";
import { CLASSIFY_TYPES, classifyBody, classifyDefaults, needsClassification, type ClassifyBody,
  type ClassifyNeeded, type Suggestion } from "./classify.js";
import { h } from "./dom.js";
import { mediaTypeLabel, type MediaType } from "./mediatypes.js";

/** The type question's options. */
export interface AskTypeOptions {
  /** The question shown above the fields. */
  question: string;
  /** The preselected type and show fields. */
  suggested?: Suggestion;
  /** Offer "Leave uncategorized" (resolves with `{mediaType: "uncategorized"}`, which the server takes as that choice). */
  allowLeave?: boolean;
  /** The types offered (default: every type but uncategorized). */
  types?: readonly MediaType[];
  /** The submit button's text. */
  submitText?: string;
}

/**
 * Asks for a media type (a modal form). Resolves with the classify body, `{mediaType:
 * "uncategorized"}` for "Leave uncategorized", or `null` when cancelled.
 */
export function askType(o: AskTypeOptions): Promise<ClassifyBody | null> {
  return new Promise((resolve) => {
    const d = classifyDefaults(o.suggested);
    const types = o.types ?? CLASSIFY_TYPES;
    const type = h("select", { name: "mediaType", required: true },
      h("option", { value: "", text: "Choose…", selected: d.mediaType === "" }),
      ...types.map((t) => h("option", { value: t, text: mediaTypeLabel(t), selected: t === d.mediaType })));
    const showName = h("input", { name: "showName", maxlength: "300", value: d.showName });
    const season = h("input", { name: "season", inputmode: "numeric", value: d.season, placeholder: "none" });
    const episode = h("input", { name: "episode", inputmode: "numeric", value: d.episode });
    const showFields = h("div", {},
      h("label", {}, "Show name", showName),
      h("label", {}, "Season", season),
      h("label", {}, "Episode", episode));
    const syncShow = (): void => { showFields.hidden = type.value !== "show"; };
    syncShow();
    type.addEventListener("change", syncShow);
    const error = h("p", { class: "error", role: "alert" });
    const cancel = h("button", { type: "button", text: "Cancel" });
    const leave = o.allowLeave === true
      ? h("button", { type: "button", text: "Leave uncategorized", title: "Add it without a type for now" }) : null;
    const form = h("form", { class: "form", role: "dialog", "aria-modal": "true", "aria-label": "Media type" },
      h("h2", { text: "Media type" }),
      h("p", { text: o.question }),
      h("label", {}, "Media type", type),
      showFields,
      error,
      h("div", { class: "row" }, h("button", { type: "submit", text: o.submitText ?? "Add to library" }), leave, cancel));
    const overlay = h("div", { class: "classify-prompt" }, form);
    const close = (value: ClassifyBody | null): void => {
      overlay.remove();
      resolve(value);
    };
    cancel.addEventListener("click", () => close(null));
    leave?.addEventListener("click", () => close({ mediaType: "uncategorized" }));
    overlay.addEventListener("keydown", (ev) => { if (ev.key === "Escape") close(null); });
    form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const r = classifyBody(type.value, showName.value, season.value, episode.value);
      if ("error" in r) error.textContent = r.error;
      else close(r.body);
    });
    document.body.append(overlay);
    type.focus();
  });
}

/**
 * Asks what kind of video `needed` is, with the server's suggestion preselected. Resolves with
 * the classify body, or `null` when cancelled. `what` names the item in the question;
 * `allowLeave` offers "Leave uncategorized".
 */
export function askClassification(needed: ClassifyNeeded, what?: string, allowLeave = false): Promise<ClassifyBody | null> {
  return askType({
    question: (what !== undefined && what !== "" ? what + ": w" : "W")
      + "hat kind of video is this? It needs a type before it goes into a library.",
    suggested: needed.suggested,
    allowLeave,
  });
}

/**
 * Runs `attempt` (an add to a library); if the server asks for a classification, asks and runs it
 * again with the answer. Resolves with the result, or `null` when the question was cancelled.
 */
export async function withClassification<T>(attempt: (c?: ClassifyBody) => Promise<T>, what?: string,
    allowLeave = false): Promise<T | null> {
  try {
    return await attempt();
  } catch (e) {
    const needed = needsClassification(e);
    if (needed === undefined) throw e;
    const answer = await askClassification(needed, what, allowLeave);
    if (answer === null) return null;
    return await attempt(answer);
  }
}

/**
 * After an import or upload whose library add was refused for want of a type (the item itself
 * exists): asks and adds it with `PUT /api/library/{itemId}`. Resolves with whether it was added.
 */
export async function classifyAndAdd(needed: ClassifyNeeded, what?: string): Promise<boolean> {
  const answer = await askClassification(needed, what);
  if (answer === null) return false;
  await api("PUT", "/api/library/" + encodeURIComponent(needed.itemId), answer);
  return true;
}
