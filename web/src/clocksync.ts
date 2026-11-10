// Clock sync (docs/rooms.md#clock-sync-web-and-app): estimates offset = serverClock − clientClock
// over the room WebSocket, so the page knows the server's time to within a few milliseconds.
//
// A round sends 8 `{type:"time", t0}` messages one after the other (the next one when the reply
// arrives, or after a timeout) and keeps the sample with the lowest round-trip time. A round runs
// on every (re)connect and then every 30 s. The rounds' best samples are smoothed with an EMA;
// a round whose best RTT is above 2× the lowest RTT seen recently is an outlier and ignored.
//
// This module touches no DOM and imports nothing, so it can be tested outside a browser.

/**
 * When `performance.now()` was 0, in epoch ms: `performance.timeOrigin`, or where a browser lacks
 * it (or returns nonsense), the wall clock minus `performance.now()` taken once at startup.
 */
function timeOrigin(): number {
  const origin = (performance as Performance & { timeOrigin?: number }).timeOrigin;
  if (typeof origin === "number" && Number.isFinite(origin) && origin > 0) return origin;
  return Date.now() - performance.now();
}

let origin: number | undefined;

/** The client's clock for clock sync: monotonic, in epoch milliseconds. */
export function clientNow(): number {
  if (origin === undefined) origin = timeOrigin();
  return origin + performance.now();
}

export interface ClockSample {
  /** Round-trip time in ms. */
  rtt: number;
  /** serverClock − clientClock in ms, assuming the reply took half the round trip. */
  offset: number;
}

/** One exchange's sample: t0 sent, ts the server's clock, t1 when the reply arrived. */
export function sample(t0: number, ts: number, t1: number): ClockSample {
  return { rtt: t1 - t0, offset: ts - (t0 + t1) / 2 };
}

export interface ClockSyncOptions {
  /** Exchanges per round (8). */
  exchanges?: number;
  /** Time between the starts of two rounds (30 s). */
  intervalMs?: number;
  /** How long to wait for a reply before sending the next exchange (2 s). */
  replyTimeoutMs?: number;
  /** EMA weight of a new round's sample (0.3). */
  alpha?: number;
  /**
   * The lowest RTT is taken over this many recent rounds (10, about 5 minutes), so a network
   * that got slower for good stops being filtered out after a while.
   */
  rttWindow?: number;
  /**
   * RTTs within this many ms of the minimum always pass the outlier filter (5), so sub-millisecond
   * jitter on a local network doesn't count as "above 2× the minimum".
   */
  rttSlackMs?: number;
  /**
   * A sample this far (ms) from the smoothed offset replaces it instead of being averaged in
   * (1000): the client's clock stepped (e.g. the device slept), and easing towards the new value
   * would keep playback wrong for minutes.
   */
  stepMs?: number;
  /** Clock to use (tests); defaults to `clientNow`. */
  now?: () => number;
  /** Timer functions (tests); default to the global ones. */
  setTimeout?: (f: () => void, ms: number) => unknown;
  clearTimeout?: (handle: unknown) => void;
}

interface Round {
  sent: number;
  pendingT0: number | undefined;
  timer: unknown;
  best: ClockSample | undefined;
}

export class ClockSync {
  /** The smoothed offset in ms (serverClock − clientClock), undefined until the first round. */
  offsetMs: number | undefined;
  /** The best RTT of the last accepted round, in ms. */
  rttMs: number | undefined;
  /** Called after every accepted round. */
  onUpdate: ((offsetMs: number, rttMs: number) => void) | undefined;

  readonly #send: (t0: number) => boolean;
  readonly #exchanges: number;
  readonly #intervalMs: number;
  readonly #replyTimeoutMs: number;
  readonly #alpha: number;
  readonly #rttWindow: number;
  readonly #rttSlackMs: number;
  readonly #stepMs: number;
  readonly #now: () => number;
  readonly #setTimeout: (f: () => void, ms: number) => unknown;
  readonly #clearTimeout: (handle: unknown) => void;
  #round: Round | undefined;
  #nextRound: unknown;
  #running = false;
  /** The 30 s timer fired while a round was still going (very slow replies): start after it. */
  #scheduleAfterRound = false;
  /** Best RTT of each recent round (accepted or not), newest last. */
  #recentRtts: number[] = [];

  /** `send` sends `{type:"time", t0}` and says whether the socket took it. */
  constructor(send: (t0: number) => boolean, options: ClockSyncOptions = {}) {
    this.#send = send;
    this.#exchanges = options.exchanges ?? 8;
    this.#intervalMs = options.intervalMs ?? 30000;
    this.#replyTimeoutMs = options.replyTimeoutMs ?? 2000;
    this.#alpha = options.alpha ?? 0.3;
    this.#rttWindow = options.rttWindow ?? 10;
    this.#rttSlackMs = options.rttSlackMs ?? 5;
    this.#stepMs = options.stepMs ?? 1000;
    this.#now = options.now ?? clientNow;
    this.#setTimeout = options.setTimeout ?? ((f, ms) => setTimeout(f, ms));
    this.#clearTimeout = options.clearTimeout ?? ((h) => clearTimeout(h as number));
  }

  /** The server's clock as estimated now (the client's wall clock until the first round). */
  serverNow(): number {
    return this.offsetMs !== undefined ? this.#now() + this.offsetMs : Date.now();
  }

  /** The estimate and its history, for diagnostics. */
  state(): Record<string, unknown> {
    return {
      offsetMs: this.offsetMs,
      rttMs: this.rttMs,
      running: this.#running,
      roundInProgress: this.#round !== undefined,
      recentRttsMs: this.#recentRtts.slice(),
      clientNowMs: this.#now(),
      dateNowMs: Date.now(),
      serverNowMs: this.serverNow(),
    };
  }

  /** Starts a round now and one every 30 s after it (call when the socket is connected). */
  start(): void {
    this.stop();
    this.#running = true;
    this.#startRound();
  }

  /** Stops measuring (call when the socket is lost); the offset is kept. */
  stop(): void {
    this.#running = false;
    this.#scheduleAfterRound = false;
    if (this.#nextRound !== undefined) this.#clearTimeout(this.#nextRound);
    this.#nextRound = undefined;
    const r = this.#round;
    if (r !== undefined && r.timer !== undefined) this.#clearTimeout(r.timer);
    this.#round = undefined;
  }

  /** Measures again now (e.g. the page became visible after the device slept). */
  syncNow(): void {
    if (this.#running && this.#round === undefined) this.start();
  }

  /** A `{type:"time", t0, ts}` reply that arrived at `t1` (client clock). */
  reply(t0: number, ts: number, t1: number): void {
    const r = this.#round;
    if (r === undefined || r.pendingT0 !== t0) return; // a late reply to a timed-out exchange
    if (r.timer !== undefined) this.#clearTimeout(r.timer);
    r.timer = undefined;
    r.pendingT0 = undefined;
    const s = sample(t0, ts, t1);
    if (s.rtt >= 0 && isFinite(s.offset) && (r.best === undefined || s.rtt < r.best.rtt)) r.best = s;
    this.#exchange(r);
  }

  #startRound(): void {
    if (!this.#running) return;
    this.#nextRound = this.#setTimeout(() => {
      this.#nextRound = undefined;
      if (this.#round === undefined) this.#startRound();
      else this.#scheduleAfterRound = true;
    }, this.#intervalMs);
    const r: Round = { sent: 0, pendingT0: undefined, timer: undefined, best: undefined };
    this.#round = r;
    this.#exchange(r);
  }

  #exchange(r: Round): void {
    if (this.#round !== r) return;
    if (r.sent >= this.#exchanges) {
      this.#finish(r);
      return;
    }
    const t0 = this.#now();
    r.sent++;
    if (!this.#send(t0)) {
      // Not connected: the round ends here; the next connect starts a new one.
      this.#finish(r);
      return;
    }
    r.pendingT0 = t0;
    r.timer = this.#setTimeout(() => {
      r.timer = undefined;
      r.pendingT0 = undefined;
      this.#exchange(r);
    }, this.#replyTimeoutMs);
  }

  #finish(r: Round): void {
    if (this.#round !== r) return;
    this.#round = undefined;
    const best = r.best;
    if (best !== undefined) this.#apply(best);
    if (this.#scheduleAfterRound) {
      this.#scheduleAfterRound = false;
      this.#startRound();
    }
  }

  /** Adds a round's best sample: outlier filter, then EMA (or a step). */
  #apply(s: ClockSample): void {
    this.#recentRtts.push(s.rtt);
    while (this.#recentRtts.length > this.#rttWindow) this.#recentRtts.shift();
    const minRtt = Math.min(...this.#recentRtts);
    if (this.offsetMs !== undefined && s.rtt > Math.max(2 * minRtt, minRtt + this.#rttSlackMs)) return;
    if (this.offsetMs === undefined || Math.abs(s.offset - this.offsetMs) > this.#stepMs) {
      this.offsetMs = s.offset;
    } else {
      this.offsetMs += this.#alpha * (s.offset - this.offsetMs);
    }
    this.rttMs = s.rtt;
    this.onUpdate?.(this.offsetMs, s.rtt);
  }
}
