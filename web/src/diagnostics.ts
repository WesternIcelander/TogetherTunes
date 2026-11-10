// Client diagnostics for listen mode: what a phone's player did, without Web Inspector.
//
// Rings in memory, filled from page load on: the sync loop's decisions (one per tick and element),
// notable player events (seeks and where they landed, rate changes, play failures, media events)
// page errors (window `error` and `unhandledrejection`, plus errors the player caught itself), and
// once a second the player's health (output level per element, audio clock, decoder counters).
// The room page's "Send diagnostics" link posts them, with the user agent and the player's and
// clock's state, to `POST /api/diagnostics`, which logs the report on one line.
//
// No DOM access at import time and no imports, so it can be tested outside a browser. Nothing here
// holds addresses, tokens or cookies: only positions, rates, element states and error texts.

/** A fixed-size ring: keeps the last `max` items pushed. */
export class Ring<T> {
  readonly max: number;
  #items: T[] = [];
  /** How many were pushed in all (so a report shows how many were dropped). */
  #total = 0;

  constructor(max: number) {
    this.max = max;
  }

  push(item: T): void {
    this.#items.push(item);
    this.#total++;
    if (this.#items.length > this.max) this.#items.splice(0, this.#items.length - this.max);
  }

  toArray(): T[] {
    return this.#items.slice();
  }

  get size(): number {
    return this.#items.length;
  }

  get total(): number {
    return this.#total;
  }

  clear(): void {
    this.#items = [];
    this.#total = 0;
  }
}

/** One sync-loop decision for one element. */
export interface Decision {
  /** `performance.now()`, rounded to ms. */
  t: number;
  /** Which element (`a0`, `a1`, `v0`, `v1`). */
  el: string;
  /** Element position − timeline position (ms), when there is one. */
  drift?: number;
  /** What the loop did: steady, rate, seek, start, wait, paused-seek, play, … */
  action: string;
  rate: number;
  readyState: number;
  seeking: boolean;
  paused: boolean;
}

export type Entry = Record<string, unknown> & { t: number; kind: string };

export const DECISIONS = 200;
export const EVENTS = 150;
export const ERRORS = 50;
/** Health samples kept: one per second, so 5 minutes (a report sent a minute after a mute covers it). */
export const HEALTH = 300;
/** Longest error text kept (message + stack). */
export const MAX_ERROR_TEXT = 2000;

const decisions = new Ring<Decision>(DECISIONS);
const events = new Ring<Entry>(EVENTS);
const errors = new Ring<Entry>(ERRORS);
const health = new Ring<Entry>(HEALTH);

function now(): number {
  return typeof performance !== "undefined" ? Math.round(performance.now()) : Date.now();
}

export function recordDecision(d: Omit<Decision, "t">): void {
  decisions.push({ t: now(), ...d });
}

export function recordEvent(kind: string, detail: Record<string, unknown> = {}): void {
  events.push({ t: now(), kind, ...detail });
}

/**
 * One second of the player's health (output level, audio clock, decoder counters; see listen.ts
 * #sampleHealth). Short keys: 300 of them go into a report.
 */
export function recordHealth(sample: Record<string, unknown>): void {
  health.push({ t: now(), kind: "h", ...sample });
}

/** An error as text: `Name: message` and the stack, cut to MAX_ERROR_TEXT. */
export function describeError(e: unknown): string {
  let text: string;
  if (e instanceof Error) {
    text = e.name + ": " + e.message;
    if (typeof e.stack === "string" && e.stack !== "") text += "\n" + e.stack;
  } else if (typeof e === "object" && e !== null && "name" in e && "message" in e) {
    const x = e as { name: unknown; message: unknown };
    text = String(x.name) + ": " + String(x.message);
  } else {
    try {
      text = typeof e === "string" ? e : JSON.stringify(e) ?? String(e);
    } catch {
      text = String(e);
    }
  }
  return text.length > MAX_ERROR_TEXT ? text.slice(0, MAX_ERROR_TEXT) + "…" : text;
}

/** An error caught somewhere (`source` says where: "tick", "error", "unhandledrejection", …). */
export function recordError(source: string, e: unknown, detail: Record<string, unknown> = {}): void {
  errors.push({ t: now(), kind: source, error: describeError(e), ...detail });
}

interface ErrorLike {
  message?: unknown;
  filename?: unknown;
  lineno?: unknown;
  colno?: unknown;
  error?: unknown;
  reason?: unknown;
}

/** The part of `window` used to catch page errors. */
export interface ErrorTarget {
  addEventListener(type: string, listener: (event: ErrorLike) => void): void;
}

let capturing = false;

/** Records the page's uncaught errors and unhandled rejections from now on (once per page). */
export function captureErrors(target: ErrorTarget): void {
  if (capturing) return;
  capturing = true;
  target.addEventListener("error", (ev) => {
    // A failed <img>/<audio> load also fires "error" (not bubbling to window), so this is a script error.
    const where = typeof ev.filename === "string" ? stripQuery(ev.filename) + ":" + String(ev.lineno) + ":" + String(ev.colno) : undefined;
    recordError("error", ev.error ?? ev.message, where !== undefined ? { where } : {});
  });
  target.addEventListener("unhandledrejection", (ev) => recordError("unhandledrejection", ev.reason));
}

/** A URL without its query and fragment (a page URL could carry a token some day). */
export function stripQuery(url: string): string {
  const i = url.search(/[?#]/);
  return i < 0 ? url : url.slice(0, i);
}

/** What has been recorded, for a report. */
export function snapshot(): Record<string, unknown> {
  return {
    decisions: decisions.toArray(),
    decisionsTotal: decisions.total,
    events: events.toArray(),
    eventsTotal: events.total,
    errors: errors.toArray(),
    errorsTotal: errors.total,
    health: health.toArray(),
    healthTotal: health.total,
  };
}

/** Drops everything recorded (tests). */
export function clearForTests(): void {
  decisions.clear();
  events.clear();
  errors.clear();
  health.clear();
}
