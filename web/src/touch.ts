// Touch helpers for the mobile layout (docs/ui.md#interaction-rules-all-layouts): long press as the
// touch equivalent of right-click, and a small popup menu (a sheet at the bottom on narrow windows,
// main.css) for the ⋯ buttons. Rules (thresholds) are in the DOM-free mobile.ts.
import { h } from "./dom.js";
import { LONG_PRESS_MS, movedTooFar } from "./mobile.js";

/**
 * The click that ends a long press is swallowed (wherever it lands: the action may have redrawn
 * the element under the finger). A new press starts over.
 */
let swallowClick = false;
document.addEventListener("pointerdown", () => { swallowClick = false; }, true);
document.addEventListener("click", (ev) => {
  if (!swallowClick) return;
  swallowClick = false;
  ev.preventDefault();
  ev.stopPropagation();
}, true);

/**
 * Calls `action` on a long press of `el` (touch or pen held still for LONG_PRESS_MS) and on its
 * `contextmenu` event (right-click; Android also sends it for a long press), once per press. The
 * click that ends a long press is swallowed, so a long press on a link doesn't also follow it.
 */
export function onLongPress(el: HTMLElement, action: (ev: Event) => void, signal?: AbortSignal): void {
  let timer: number | undefined;
  let startX = 0;
  let startY = 0;
  let fired = false;
  const opts = signal !== undefined ? { signal } : undefined;
  const cancel = (): void => {
    window.clearTimeout(timer);
    timer = undefined;
  };
  const fire = (ev: Event): void => {
    cancel();
    if (fired) return;
    fired = true;
    swallowClick = true;
    action(ev);
  };
  el.addEventListener("pointerdown", (ev) => {
    fired = false;
    if (ev.pointerType === "mouse") return;
    startX = ev.clientX;
    startY = ev.clientY;
    cancel();
    timer = window.setTimeout(() => fire(ev), LONG_PRESS_MS);
  }, opts);
  el.addEventListener("pointermove", (ev) => {
    if (timer !== undefined && movedTooFar(ev.clientX - startX, ev.clientY - startY)) cancel();
  }, opts);
  for (const type of ["pointerup", "pointercancel", "pointerleave"]) el.addEventListener(type, cancel, opts);
  el.addEventListener("contextmenu", (ev) => {
    ev.preventDefault();
    fire(ev);
  }, opts);
}

/** One entry of a popup menu. */
export interface MenuItem {
  label: string;
  action: () => void;
}

let openMenuEl: HTMLElement | undefined;
/** Set while the event that opened the menu is still being dispatched (its click must not close it). */
let justOpened = false;

/** Closes the popup menu opened by {@link openMenu}, if any. */
export function closeMenu(): void {
  openMenuEl?.remove();
  openMenuEl = undefined;
}

document.addEventListener("click", (ev) => {
  const t = ev.target;
  if (justOpened) return;
  if (openMenuEl !== undefined && t instanceof Element && t.closest(".popup-menu") === null) closeMenu();
});
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") closeMenu();
});

/**
 * Opens a popup menu with `items` under `anchor` (on narrow windows a sheet at the bottom of the
 * screen, main.css). A click elsewhere or Escape closes it; choosing an item closes it first.
 */
export function openMenu(anchor: HTMLElement, items: readonly MenuItem[], label: string): void {
  closeMenu();
  const menu = h("div", { class: "menu popup-menu", role: "menu", "aria-label": label });
  for (const it of items) {
    const b = h("button", { type: "button", role: "menuitem", text: it.label });
    b.addEventListener("click", (ev) => {
      ev.stopPropagation();
      closeMenu();
      it.action();
    });
    menu.append(b);
  }
  const r = anchor.getBoundingClientRect();
  menu.style.top = String(r.bottom + window.scrollY + 4) + "px";
  menu.style.left = String(Math.max(8, r.right + window.scrollX - 192)) + "px";
  document.body.append(menu);
  openMenuEl = menu;
  justOpened = true;
  window.setTimeout(() => { justOpened = false; }, 0);
  (menu.querySelector("button") as HTMLButtonElement | null)?.focus({ preventScroll: true });
}
