// Small DOM helpers shared by the library, item and playlist pages. Text is always set as text,
// never parsed as HTML.

export type Child = Node | string | null | undefined | false;

/**
 * Creates an element. `props`: attributes (`true` = present, `false`/`undefined` = left out),
 * plus `text` for the text content.
 */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Partial<Record<string, string | boolean>> = {},
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

/**
 * The element the view scrolls in: the middle (`#app`) in the window-filling desktop layout
 * (main.css, docs/ui.md#layout-desktop), else the window (narrow windows).
 */
function contentScroller(): HTMLElement | undefined {
  const app = document.getElementById("app");
  if (app === null) return undefined;
  const y = getComputedStyle(app).overflowY;
  return y === "auto" || y === "scroll" ? app : undefined;
}

/** How far the view is scrolled (see {@link contentScroller}). */
export function contentScrollTop(): number {
  const el = contentScroller();
  return el === undefined ? window.scrollY : el.scrollTop;
}

/** Scrolls the view to `y` (see {@link contentScroller}). */
export function scrollContentTo(y: number): void {
  const el = contentScroller();
  if (el === undefined) window.scrollTo(0, y);
  else el.scrollTop = y;
}

/** A button that runs `action` when clicked, disabled while it runs; errors go to `onError`. */
export function button(text: string, action: () => Promise<void> | void, onError: (e: unknown) => void,
                       className?: string): HTMLButtonElement {
  const b = h("button", { type: "button", text, class: className });
  b.addEventListener("click", () => {
    b.disabled = true;
    Promise.resolve().then(action).catch(onError).finally(() => { b.disabled = false; });
  });
  return b;
}

/** `m:ss` or `h:mm:ss`. */
export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null || !(ms >= 0)) return "";
  const total = Math.round(ms / 1000);
  const s = total % 60;
  const m = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  const ss = String(s).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/** A byte count as B / KB / MB / GB (powers of 1024, one decimal above KB). */
export function formatBytes(n: number): string {
  if (n < 1024) return n + " B";
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return (v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)) + " " + units[i];
}

export function formatTime(ms: number): string {
  return new Date(ms).toLocaleString();
}
