// The user's preferences (`GET`/`PATCH /api/me/settings`, docs/ui.md#existing-features-in-the-new-ui):
// double-click behaviour alone, Web Audio and debug mode. Pure logic (no DOM): the store gets its
// loader and saver from prefsclient.ts, so it runs under Node in the tests (prefs.test.mjs).
//
// Changes apply at once: `set()` updates the cached value and tells the subscribers synchronously
// (so a change event in the settings view is still a user gesture when the room's player rebuilds
// its audio path), then saves; a failed save puts the old values back and tells them again.

export type DoubleClickAlone = "play" | "queue";
export type WebAudioPref = "auto" | "on" | "off";

export interface Prefs {
  doubleClickAlone: DoubleClickAlone;
  webAudio: WebAudioPref;
  debug: boolean;
}

export type PrefKey = keyof Prefs;

export const DEFAULT_PREFS: Readonly<Prefs> = { doubleClickAlone: "play", webAudio: "auto", debug: false };

export const PREF_KEYS: readonly PrefKey[] = ["doubleClickAlone", "webAudio", "debug"];

/** The choices of each select-type preference, with their labels. */
export const DOUBLE_CLICK_OPTIONS: readonly [DoubleClickAlone, string][] = [
  ["play", "Play it now"],
  ["queue", "Add it to the queue"],
];

export const WEB_AUDIO_OPTIONS: readonly [WebAudioPref, string][] = [
  ["auto", "Auto"],
  ["on", "On"],
  ["off", "Off"],
];

/** The server's answer as preferences: anything missing or not understood is the default. */
export function parsePrefs(raw: unknown): Prefs {
  const r = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
  const dc = r["doubleClickAlone"];
  const wa = r["webAudio"];
  return {
    doubleClickAlone: dc === "queue" ? "queue" : "play",
    webAudio: wa === "on" || wa === "off" ? wa : "auto",
    debug: r["debug"] === true,
  };
}

/** The keys whose values differ (all keys when `before` is undefined). */
export function changedKeys(before: Prefs | undefined, after: Prefs): PrefKey[] {
  return PREF_KEYS.filter((k) => before === undefined || before[k] !== after[k]);
}

/** A patch with only the keys that change `current`; null when nothing changes. */
export function prefsPatch(current: Prefs, patch: Partial<Prefs>): Partial<Prefs> | null {
  const out: Partial<Prefs> = {};
  let any = false;
  for (const k of PREF_KEYS) {
    const v = patch[k];
    if (v === undefined || v === current[k]) continue;
    (out as Record<string, unknown>)[k] = v;
    any = true;
  }
  return any ? out : null;
}

/**
 * Told about a change. `byUser`: it comes from `set()` itself (synchronously, still inside the user's
 * gesture), not from a load, a reset, the server's answer or a failed save.
 */
export type PrefsListener = (prefs: Prefs, changed: PrefKey[], byUser: boolean) => void;

/**
 * The signed-in user's preferences, loaded once and cached; `reset()` after the identity changed.
 * Listeners hear every change (the first load too, with the keys that differ from the defaults).
 */
export class PrefsStore {
  readonly #load: () => Promise<unknown>;
  readonly #save: (patch: Partial<Prefs>) => Promise<unknown>;
  #value: Prefs | undefined;
  #loading: Promise<Prefs> | undefined;
  #generation = 0;
  readonly #listeners = new Set<PrefsListener>();

  constructor(load: () => Promise<unknown>, save: (patch: Partial<Prefs>) => Promise<unknown>) {
    this.#load = load;
    this.#save = save;
  }

  /** The cached preferences (undefined until loaded, and for guests and anonymous visitors). */
  get(): Prefs | undefined {
    return this.#value;
  }

  /** Loads them (once; later calls get the cached value). */
  load(): Promise<Prefs> {
    if (this.#value !== undefined) return Promise.resolve(this.#value);
    if (this.#loading !== undefined) return this.#loading;
    const generation = this.#generation;
    const loading = this.#load().then((raw) => {
      const prefs = parsePrefs(raw);
      if (generation === this.#generation) {
        this.#loading = undefined;
        this.#replace(prefs, DEFAULT_PREFS, false);
      }
      return prefs;
    }, (e: unknown) => {
      if (generation === this.#generation) this.#loading = undefined;
      throw e;
    });
    this.#loading = loading;
    return loading;
  }

  /** Forgets them (logged out, another user logged in); listeners hear the defaults. */
  reset(): void {
    this.#generation++;
    this.#loading = undefined;
    const before = this.#value;
    this.#value = undefined;
    if (before !== undefined) this.#notify(DEFAULT_PREFS, changedKeys(before, DEFAULT_PREFS), false);
  }

  /**
   * Changes some preferences: applied (and the listeners told) at once, then saved; on failure
   * the old values come back and the error is thrown.
   */
  async set(patch: Partial<Prefs>): Promise<Prefs> {
    const current = this.#value ?? await this.load();
    const changes = prefsPatch(current, patch);
    if (changes === null) return current;
    const next: Prefs = { ...current, ...changes };
    this.#replace(next, current, true);
    try {
      const saved = parsePrefs(await this.#save(changes));
      if (this.#value === next) this.#replace(saved, next, false);
      return saved;
    } catch (e) {
      if (this.#value === next) this.#replace(current, next, false);
      throw e;
    }
  }

  /** Calls `fn` on every change; returns the unsubscribe function. */
  subscribe(fn: PrefsListener): () => void {
    this.#listeners.add(fn);
    return () => { this.#listeners.delete(fn); };
  }

  #replace(next: Prefs, before: Prefs, byUser: boolean): void {
    this.#value = next;
    const changed = changedKeys(before, next);
    if (changed.length > 0) this.#notify(next, changed, byUser);
  }

  #notify(prefs: Prefs, changed: PrefKey[], byUser: boolean): void {
    for (const fn of Array.from(this.#listeners)) {
      try {
        fn(prefs, changed, byUser);
      } catch {
        // One listener's failure doesn't stop the others.
      }
    }
  }
}
