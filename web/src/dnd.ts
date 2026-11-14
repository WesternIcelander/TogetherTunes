// The page side of drag and drop (rules in the DOM-free dragdrop.ts): making rows draggable with a
// payload, and drop zones that accept some kinds of drags, show where a drop lands (a line before a
// row, or the whole target highlighted) and hand the drop over. Desktop only: touch screens don't
// start HTML drags (docs/ui.md: drag and drop is desktop only).
import { decodePayload, dropBefore, dropContent, DRAG_TYPE, encodePayload, linkFromDrop, type DragPayload,
  type DropContent } from "./dragdrop.js";

/** The drag in progress that started on this page (the drop target can't read the data before the drop). */
let current: DragPayload | undefined;

/** The drag in progress, if it started on this page. */
export function currentDrag(): DragPayload | undefined {
  return current;
}

/**
 * Makes `el` draggable: `payload` says what a drag starting on it carries (undefined: no drag).
 * The drag also carries the songs' links as text, for drops outside the page.
 */
export function draggable(el: HTMLElement, payload: () => DragPayload | undefined, links?: () => string[],
    signal?: AbortSignal): void {
  el.draggable = true;
  const opts = signal !== undefined ? { signal } : undefined;
  el.addEventListener("dragstart", (ev) => {
    const p = payload();
    const dt = ev.dataTransfer;
    if (p === undefined || dt === null) {
      ev.preventDefault();
      return;
    }
    ev.stopPropagation();
    current = p;
    dt.effectAllowed = "copyMove";
    dt.setData(DRAG_TYPE, encodePayload(p));
    const urls = links !== undefined ? links() : [];
    if (urls.length > 0) dt.setData("text/plain", urls.join("\n"));
  }, opts);
  el.addEventListener("dragend", () => {
    current = undefined;
  }, opts);
}

/** What a drop hands over. */
export interface Dropped {
  content: DropContent;
  /** Our own drag's payload (content "internal"). */
  payload?: DragPayload;
  files: File[];
  link?: string;
  /** The row key the drop goes before (zones with rows); null: the end. */
  before: string | null;
}

export interface DropZoneOptions {
  /** Whether this zone takes the drag (our payload is known for "internal" drags only). */
  accepts(content: DropContent, payload: DragPayload | undefined): boolean;
  /**
   * Rows to drop between: a selector inside the zone and the data attribute holding each row's
   * key. Without rows (or when `positioned` says no), the whole zone is highlighted instead.
   */
  rows?: { selector: string; key: string };
  /** Whether this drag shows a position between the rows (default: yes when there are rows). */
  positioned?(content: DropContent, payload: DragPayload | undefined): boolean;
  onDrop(d: Dropped): void;
}

function clearMarks(zone: HTMLElement): void {
  zone.classList.remove("drop-target", "drop-end");
  zone.querySelectorAll(".drop-before").forEach((el) => el.classList.remove("drop-before"));
}

/** Makes `zone` a drop target. Inner zones that take a drag keep it from the outer ones. */
export function dropZone(zone: HTMLElement, o: DropZoneOptions, signal?: AbortSignal): void {
  const opts = signal !== undefined ? { signal } : undefined;
  const where = (ev: DragEvent): string | null => {
    if (o.rows === undefined) return null;
    const rows = Array.from(zone.querySelectorAll<HTMLElement>(o.rows.selector));
    const keys = rows.map((r) => r.getAttribute(o.rows?.key ?? "") ?? "");
    const target = ev.target instanceof Element ? ev.target.closest<HTMLElement>(o.rows.selector) : null;
    if (target === null || !zone.contains(target)) return null;
    const rect = target.getBoundingClientRect();
    return dropBefore(keys, target.getAttribute(o.rows.key) ?? undefined, ev.clientY > rect.top + rect.height / 2);
  };
  const state = (ev: DragEvent): { content: DropContent; payload?: DragPayload } | undefined => {
    const dt = ev.dataTransfer;
    if (dt === null) return undefined;
    const content = dropContent(Array.from(dt.types));
    const payload = content === "internal" ? current : undefined;
    return o.accepts(content, payload) ? { content, payload } : undefined;
  };
  zone.addEventListener("dragover", (ev) => {
    const s = state(ev);
    if (s === undefined) return;
    ev.preventDefault();
    ev.stopPropagation();
    const dt = ev.dataTransfer;
    if (dt !== null) dt.dropEffect = s.content === "internal" && s.payload?.kind === "items"
      && (s.payload.source === "queue" || s.payload.entryIds !== undefined) ? "move" : "copy";
    clearMarks(zone);
    const positioned = o.rows !== undefined && (o.positioned === undefined || o.positioned(s.content, s.payload));
    if (!positioned) {
      zone.classList.add("drop-target");
      return;
    }
    const before = where(ev);
    const row = before === null ? null
      : Array.from(zone.querySelectorAll<HTMLElement>(o.rows?.selector ?? "")).find((r) =>
        r.getAttribute(o.rows?.key ?? "") === before) ?? null;
    if (row !== null) row.classList.add("drop-before");
    else zone.classList.add("drop-end");
  }, opts);
  zone.addEventListener("dragleave", (ev) => {
    const to = ev.relatedTarget;
    if (to instanceof Node && zone.contains(to)) return;
    clearMarks(zone);
  }, opts);
  zone.addEventListener("drop", (ev) => {
    const s = state(ev);
    clearMarks(zone);
    if (s === undefined) return;
    ev.preventDefault();
    ev.stopPropagation();
    const dt = ev.dataTransfer;
    const payload = s.content === "internal" ? s.payload ?? decodePayload(dt?.getData(DRAG_TYPE)) : undefined;
    const files = s.content === "files" && dt !== null ? Array.from(dt.files) : [];
    const link = s.content === "link" && dt !== null ? linkFromDrop(dt.getData("text/uri-list"), dt.getData("text/plain"))
      : undefined;
    const positioned = o.rows !== undefined && (o.positioned === undefined || o.positioned(s.content, s.payload));
    o.onDrop({ content: s.content, payload, files, link, before: positioned ? where(ev) : null });
  }, opts);
}

// Files or links dropped anywhere else on the page would make the browser open them, leaving the
// page (and stopping the music): outside the drop zones (and text fields) such drops do nothing.
function textField(t: EventTarget | null): boolean {
  return t instanceof Element && t.closest("input, textarea, [contenteditable]") !== null;
}
document.addEventListener("dragover", (ev) => {
  const dt = ev.dataTransfer;
  if (ev.defaultPrevented || dt === null || textField(ev.target) || dropContent(Array.from(dt.types)) === "internal") {
    return;
  }
  ev.preventDefault();
  dt.dropEffect = "none";
});
document.addEventListener("drop", (ev) => {
  if (!ev.defaultPrevented && !textField(ev.target)) ev.preventDefault();
});
