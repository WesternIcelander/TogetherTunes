// Unattended test of the WebSocket echo endpoint (/ws/echo), loaded by /test/ws-auto.html.
// Runs the M0 browser checks one after another and, for every close, asks the server what it
// saw (GET /test/ws-echo/closes?tag=...: sockets opened with ?tag= have their server-side close
// info remembered). The run survives its own page reload through sessionStorage. At the end the
// report is shown and POSTed to /test/ws-echo/report, which writes it to the server log.
//
// The same page also serves as the child for the closed-tab and removed-frame checks: with
// ?child=<tag> it only opens a tagged socket, echoes one message and tells its opener/parent.

const ECHO_PATH = "/ws/echo";
const CLOSES_PATH = "/test/ws-echo/closes";
const REPORT_PATH = "/test/ws-echo/report";
const STATE_KEY = "tt.test.wsAuto.state";
const MAX_MESSAGE = 65536;
const IDLE_SECONDS = 150;
const PERIODIC_SECONDS = 120;
const PERIODIC_EVERY_SECONDS = 10;
const PARALLEL_SOCKETS = 8;

type Bytes = Uint8Array<ArrayBuffer>;
type Data = string | Bytes;
type Status = "pass" | "warn" | "fail" | "skip";

interface Result {
  readonly name: string;
  readonly status: Status;
  readonly detail: string;
}

/** What the server saw when a tagged socket ended (EchoEndpoint.Closed). */
interface ServerClose {
  readonly tag: string;
  readonly durationMs: number;
  readonly remoteCode: number;
  readonly remoteReason: string;
  readonly localCode: number;
  readonly localReason: string;
  readonly clean: boolean;
  readonly initiatedByPeer: boolean;
  readonly error: string | null;
}

/** Kept in sessionStorage so the run continues after its own reload. */
interface SavedRun {
  readonly runId: string;
  readonly startedAt: number;
  readonly results: Result[];
  readonly log: string[];
  readonly reloadTag: string;
  readonly reloadAt: number;
}

interface ChildReady {
  readonly type: "ws-auto-child";
  readonly tag: string;
  readonly ok: boolean;
  readonly detail: string;
}

function element<T extends HTMLElement>(id: string, type: new () => T): T {
  const el = document.getElementById(id);
  if (!(el instanceof type)) {
    throw new Error(`#${id} is missing or not a ${type.name}`);
  }
  return el;
}

function wsUrl(tag: string): string {
  const url = new URL(ECHO_PATH, location.href);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  url.search = `?tag=${encodeURIComponent(tag)}`;
  return url.href;
}

function time(): string {
  const d = new Date();
  const pad = (n: number, w = 2): string => String(n).padStart(w, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function seconds(ms: number): string {
  return `${(ms / 1000).toFixed(1)} s`;
}

function randomId(): string {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function randomBytes(length: number): Bytes {
  const bytes = new Uint8Array(length);
  for (let offset = 0; offset < length; offset += 65536) {
    crypto.getRandomValues(bytes.subarray(offset, Math.min(length, offset + 65536)));
  }
  return bytes;
}

function randomText(length: number): string {
  const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  let text = "";
  for (const b of randomBytes(length)) {
    text += alphabet.charAt(b % alphabet.length);
  }
  return text;
}

function sameData(a: Data, b: Data): boolean {
  if (typeof a === "string" || typeof b === "string") {
    return a === b;
  }
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      return false;
    }
  }
  return true;
}

function describeData(data: Data): string {
  if (typeof data === "string") {
    return `text of ${new TextEncoder().encode(data).length} B`;
  }
  return `binary of ${data.length} B`;
}

function describeClose(e: CloseEvent): string {
  return `code ${e.code}${e.reason === "" ? "" : ` "${e.reason}"`}, wasClean=${e.wasClean}`;
}

function describeServerClose(c: ServerClose): string {
  const reason = (r: string): string => (r === "" ? "" : ` "${r}"`);
  return `server: remote=${c.remoteCode}${reason(c.remoteReason)}, local=${c.localCode}${reason(c.localReason)}, `
    + `clean=${c.clean}, initiatedByPeer=${c.initiatedByPeer}${c.error === null ? "" : `, error=${c.error}`}`;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/** One echo socket: incoming messages are queued and handed out in order by next(). */
class Conn {
  private readonly inbox: Data[] = [];
  private waiter: ((data: Data | null) => void) | null = null;
  closeEvent: CloseEvent | null = null;
  readonly closed: Promise<CloseEvent>;

  private constructor(readonly ws: WebSocket, readonly tag: string) {
    ws.binaryType = "arraybuffer";
    this.closed = new Promise((resolve) => {
      ws.addEventListener("close", (event) => {
        this.closeEvent = event;
        this.wake(null);
        resolve(event);
      });
    });
    ws.addEventListener("message", (event: MessageEvent<unknown>) => {
      const raw = event.data;
      const data: Data = typeof raw === "string" ? raw : new Uint8Array(raw as ArrayBuffer);
      if (this.waiter !== null) {
        this.wake(data);
      } else {
        this.inbox.push(data);
      }
    });
  }

  /** Opens a socket tagged {@code tag}; rejects if it closes first or takes over 10 s. */
  static open(tag: string, timeoutMs = 10000): Promise<Conn> {
    const conn = new Conn(new WebSocket(wsUrl(tag)), tag);
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        conn.ws.close();
        reject(new Error(`not open after ${seconds(timeoutMs)}`));
      }, timeoutMs);
      conn.ws.addEventListener("open", () => {
        window.clearTimeout(timer);
        resolve(conn);
      });
      void conn.closed.then((e) => {
        window.clearTimeout(timer);
        reject(new Error(`closed before opening (${describeClose(e)})`));
      });
    });
  }

  get isOpen(): boolean {
    return this.ws.readyState === WebSocket.OPEN;
  }

  /** The next message; rejects on timeout or close. */
  next(timeoutMs = 10000): Promise<Data> {
    const queued = this.inbox.shift();
    if (queued !== undefined) {
      return Promise.resolve(queued);
    }
    if (this.closeEvent !== null) {
      return Promise.reject(new Error(`socket closed (${describeClose(this.closeEvent)})`));
    }
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.waiter = null;
        reject(new Error(`no echo within ${seconds(timeoutMs)}`));
      }, timeoutMs);
      this.waiter = (data) => {
        window.clearTimeout(timer);
        if (data === null) {
          reject(new Error(`socket closed (${describeClose(this.closeEvent as CloseEvent)})`));
        } else {
          resolve(data);
        }
      };
    });
  }

  /** Sends every message, then checks each echo in order. Returns the round-trip time. */
  async echo(messages: Data[], timeoutMs = 10000): Promise<number> {
    const start = performance.now();
    for (const m of messages) {
      this.ws.send(m);
    }
    for (let i = 0; i < messages.length; i++) {
      const got = await this.next(timeoutMs);
      const sent = messages[i] as Data;
      if (!sameData(sent, got)) {
        throw new Error(`echo ${i + 1} of ${messages.length} differs: sent ${describeData(sent)}, got ${describeData(got)}`);
      }
    }
    return performance.now() - start;
  }

  private wake(data: Data | null): void {
    const waiter = this.waiter;
    this.waiter = null;
    waiter?.(data);
  }
}

/** Asks the server how a tagged socket ended, polling for up to {@code waitMs}. */
async function serverClose(tag: string, waitMs = 10000): Promise<ServerClose | null> {
  const deadline = performance.now() + waitMs;
  while (true) {
    const response = await fetch(`${CLOSES_PATH}?tag=${encodeURIComponent(tag)}`, { cache: "no-store" });
    if (!response.ok) {
      throw new Error(`${CLOSES_PATH} answered ${response.status}`);
    }
    const body = (await response.json()) as { closes: ServerClose[] };
    const found = body.closes[0];
    if (found !== undefined) {
      return found;
    }
    if (performance.now() >= deadline) {
      return null;
    }
    await sleep(250);
  }
}

/** Counts how long the page was hidden while a check ran. */
class HiddenTimer {
  private hiddenMs = 0;
  private hiddenSince: number | null = document.hidden ? performance.now() : null;
  private readonly listener = (): void => {
    if (document.hidden && this.hiddenSince === null) {
      this.hiddenSince = performance.now();
    } else if (!document.hidden && this.hiddenSince !== null) {
      this.hiddenMs += performance.now() - this.hiddenSince;
      this.hiddenSince = null;
    }
  };

  constructor() {
    document.addEventListener("visibilitychange", this.listener);
  }

  /** Stops counting; returns e.g. ", page hidden 40.0 s" or "". */
  stop(): string {
    this.listener();
    document.removeEventListener("visibilitychange", this.listener);
    const total = this.hiddenMs + (this.hiddenSince === null ? 0 : performance.now() - this.hiddenSince);
    return total < 500 ? ", page visible throughout" : `, page hidden ${seconds(total)}`;
  }
}

class AutoTest {
  private readonly stateEl = element("state", HTMLElement);
  private readonly countdownEl = element("countdown", HTMLElement);
  private readonly startBtn = element("start", HTMLButtonElement);
  private readonly copyBtn = element("copy", HTMLButtonElement);
  private readonly resultsEl = element("results", HTMLTableSectionElement);
  private readonly summaryEl = element("summary", HTMLElement);
  private readonly logEl = element("log", HTMLOListElement);

  private runId = "";
  private startedAt = 0;
  private results: Result[] = [];
  private logLines: string[] = [];
  private report = "";

  start(): void {
    this.copyBtn.addEventListener("click", () => void this.copyReport());
    const saved = loadRun();
    if (saved !== null && saved.reloadTag !== "") {
      this.runId = saved.runId;
      this.startedAt = saved.startedAt;
      this.results = [...saved.results];
      for (const line of saved.log) {
        this.appendLog(line);
      }
      for (const r of this.results) {
        this.renderResult(r);
      }
      void this.afterReload(saved);
      return;
    }
    this.setState("ready: click Start, then leave the page alone");
    this.startBtn.disabled = false;
    this.startBtn.addEventListener("click", () => {
      this.startBtn.disabled = true;
      this.runId = randomId();
      this.startedAt = Date.now();
      // Must happen inside the click, or the popup blocker stops it.
      const childTag = this.tag("tab");
      const popup = window.open(`${location.pathname}?child=${childTag}`, "_blank");
      void this.run(popup, childTag);
    });
  }

  private tag(label: string): string {
    return `${this.runId}-${label}-${randomId().slice(0, 4)}`;
  }

  private async run(popup: Window | null, childTag: string): Promise<void> {
    this.log("info", `Run ${this.runId} started in ${navigator.userAgent}`);
    await this.check("Closed tab", () => this.closedTab(popup, childTag));
    await this.check("Connect", () => this.connect());
    await this.check("Messages", () => this.messages());
    await this.check("64 KiB + 1 closes with 1009", () => this.tooBig());
    await this.check("Close 1000 from the page", () => this.closeNormal());
    await this.check("Removed frame", () => this.removedFrame());
    await this.check(`${PARALLEL_SOCKETS} sockets at once`, () => this.parallel());
    await this.check(`Idle ${IDLE_SECONDS} s`, () => this.idle());
    await this.check(`Message every ${PERIODIC_EVERY_SECONDS} s for ${PERIODIC_SECONDS} s`, () => this.periodic());
    await this.reload();
  }

  /** Runs one check; a thrown error is a failure. */
  private async check(name: string, body: () => Promise<[Status, string]>): Promise<void> {
    this.setState(`running: ${name}`);
    this.log("info", `-- ${name}`);
    let result: Result;
    try {
      const [status, detail] = await body();
      result = { name, status, detail };
    } catch (e) {
      result = { name, status: "fail", detail: e instanceof Error ? e.message : String(e) };
    }
    this.addResult(result);
  }

  private async closedTab(popup: Window | null, tag: string): Promise<[Status, string]> {
    if (popup === null) {
      return ["skip", "the browser blocked the second tab; allow popups for this site and run again to include it"];
    }
    const ready = await this.waitForChild(tag, 15000);
    popup.close();
    window.focus();
    if (!ready.ok) {
      throw new Error(`the tab's socket didn't work: ${ready.detail}`);
    }
    return this.expectGoingAway(tag, "tab closed");
  }

  private async removedFrame(): Promise<[Status, string]> {
    const tag = this.tag("frame");
    const frame = document.createElement("iframe");
    frame.src = `${location.pathname}?child=${tag}`;
    frame.title = "child";
    frame.style.cssText = "width:1px;height:1px;border:0;position:absolute;left:-10px";
    document.body.append(frame);
    const ready = await this.waitForChild(tag, 15000);
    frame.remove();
    if (!ready.ok) {
      throw new Error(`the frame's socket didn't work: ${ready.detail}`);
    }
    return this.expectGoingAway(tag, "frame removed");
  }

  /** A page went away with its socket open: expect the browser to close it with 1001. */
  private async expectGoingAway(tag: string, what: string): Promise<[Status, string]> {
    const close = await serverClose(tag);
    if (close === null) {
      return ["fail", `${what}, but after 10 s the server still had the socket open`];
    }
    const detail = `${what}; ${describeServerClose(close)}`;
    return [close.remoteCode === 1001 && close.clean ? "pass" : "warn", detail];
  }

  private waitForChild(tag: string, timeoutMs: number): Promise<ChildReady> {
    return new Promise((resolve) => {
      const timer = window.setTimeout(() => {
        window.removeEventListener("message", listener);
        resolve({ type: "ws-auto-child", tag, ok: false, detail: `no answer within ${seconds(timeoutMs)}` });
      }, timeoutMs);
      const listener = (event: MessageEvent<unknown>): void => {
        const data = event.data as Partial<ChildReady> | null;
        if (event.origin !== location.origin || data?.type !== "ws-auto-child" || data.tag !== tag) {
          return;
        }
        window.clearTimeout(timer);
        window.removeEventListener("message", listener);
        resolve(data as ChildReady);
      };
      window.addEventListener("message", listener);
    });
  }

  private async connect(): Promise<[Status, string]> {
    const start = performance.now();
    const conn = await Conn.open(this.tag("connect"));
    const opened = performance.now() - start;
    const extensions = conn.ws.extensions === "" ? "none" : conn.ws.extensions;
    conn.ws.close(1000);
    await conn.closed;
    return ["pass", `open after ${Math.round(opened)} ms, extensions: ${extensions}`];
  }

  private async messages(): Promise<[Status, string]> {
    const conn = await Conn.open(this.tag("messages"));
    const cases: [string, Data[]][] = [
      ["hello", ["hello"]],
      ["empty text", [""]],
      ["non-ASCII", ["héllo 🎵 ÄÖÜ ß 日本"]],
      ["text 16 KiB", [randomText(16384)]],
      ["text 64 KiB", [randomText(MAX_MESSAGE)]],
      ["binary 1 KiB", [randomBytes(1024)]],
      ["binary 64 KiB", [randomBytes(MAX_MESSAGE)]],
      ["burst of 100", Array.from({ length: 100 }, (_, i) => (i % 2 === 0 ? `burst ${i}` : randomBytes(100 + i)))],
    ];
    const times: string[] = [];
    try {
      for (const [name, messages] of cases) {
        try {
          const ms = await conn.echo(messages);
          times.push(`${name} ${Math.round(ms)} ms`);
        } catch (e) {
          throw new Error(`${name}: ${e instanceof Error ? e.message : String(e)}`);
        }
      }
    } finally {
      conn.ws.close(1000);
    }
    return ["pass", `all echoes match (${times.join(", ")})`];
  }

  private async tooBig(): Promise<[Status, string]> {
    const conn = await Conn.open(this.tag("toobig"));
    await conn.echo(["before"]);
    conn.ws.send(randomBytes(MAX_MESSAGE + 1));
    const timeout = sleep(10000).then(() => null);
    const event = await Promise.race([conn.closed, timeout]);
    if (event === null) {
      conn.ws.close();
      throw new Error("the socket was still open 10 s after the oversized message");
    }
    const server = await serverClose(conn.tag);
    const detail = `page: ${describeClose(event)}; ${server === null ? "server: no close recorded" : describeServerClose(server)}`;
    const ok = event.code === 1009 && server !== null && server.localCode === 1009;
    // Then a new socket must work again.
    const again = await Conn.open(this.tag("after1009"));
    await again.echo(["after"]);
    again.ws.close(1000);
    return [ok ? "pass" : "fail", `${detail}; a new socket works afterwards`];
  }

  private async closeNormal(): Promise<[Status, string]> {
    const conn = await Conn.open(this.tag("close"));
    await conn.echo(["bye soon"]);
    conn.ws.close(1000, "closed by auto test");
    const event = await conn.closed;
    const server = await serverClose(conn.tag);
    const detail = `page: ${describeClose(event)}; ${server === null ? "server: no close recorded" : describeServerClose(server)}`;
    const ok = event.code === 1000 && event.wasClean && server !== null
      && server.remoteCode === 1000 && server.remoteReason === "closed by auto test"
      && server.clean && server.initiatedByPeer;
    return [ok ? "pass" : "fail", detail];
  }

  private async parallel(): Promise<[Status, string]> {
    const conns = await Promise.all(
      Array.from({ length: PARALLEL_SOCKETS }, (_, i) => Conn.open(this.tag(`par${i}`))));
    try {
      await Promise.all(conns.map((c, i) =>
        c.echo(Array.from({ length: 20 }, (_, j) => `socket ${i} message ${j}`))));
    } finally {
      for (const c of conns) {
        c.ws.close(1000);
      }
    }
    return ["pass", `${PARALLEL_SOCKETS} sockets open together, 20 interleaved echoes each matched`];
  }

  private async idle(): Promise<[Status, string]> {
    const conn = await Conn.open(this.tag("idle"));
    await conn.echo(["before idle"]);
    const hidden = new HiddenTimer();
    const start = performance.now();
    await this.countdown(IDLE_SECONDS, "idle, nothing sent", () => conn.isOpen);
    const hiddenNote = hidden.stop();
    const elapsed = performance.now() - start;
    if (!conn.isOpen) {
      const event = await conn.closed;
      const server = await serverClose(conn.tag, 2000);
      throw new Error(`closed after ${seconds(elapsed)} idle: page ${describeClose(event)}`
        + `${server === null ? "" : `; ${describeServerClose(server)}`}${hiddenNote}`);
    }
    const ms = await conn.echo(["after idle"]);
    conn.ws.close(1000);
    return ["pass", `still open after ${seconds(elapsed)} idle, echo took ${Math.round(ms)} ms${hiddenNote}`];
  }

  private async periodic(): Promise<[Status, string]> {
    const conn = await Conn.open(this.tag("periodic"));
    const hidden = new HiddenTimer();
    const start = performance.now();
    let sent = 0;
    let slowest = 0;
    let latestGap = 0;
    let lastSend = start;
    const end = start + PERIODIC_SECONDS * 1000;
    while (performance.now() < end && conn.isOpen) {
      const now = performance.now();
      latestGap = Math.max(latestGap, now - lastSend);
      lastSend = now;
      sent++;
      slowest = Math.max(slowest, await conn.echo([`periodic ${sent} at ${time()}`]));
      this.countdownEl.textContent = `(${sent} sent, ${Math.ceil((end - performance.now()) / 1000)} s left)`;
      await sleep(Math.min(PERIODIC_EVERY_SECONDS * 1000, Math.max(0, end - performance.now())));
    }
    this.countdownEl.textContent = "";
    const hiddenNote = hidden.stop();
    if (!conn.isOpen) {
      throw new Error(`closed after ${sent} messages: ${describeClose(await conn.closed)}${hiddenNote}`);
    }
    conn.ws.close(1000);
    const expected = Math.floor(PERIODIC_SECONDS / PERIODIC_EVERY_SECONDS);
    // Background tabs throttle timers; fewer sends is the browser's doing, not a socket problem.
    const throttled = sent < expected ? `, timers throttled (largest gap ${seconds(latestGap)})` : "";
    return ["pass", `${sent} messages echoed, slowest ${Math.round(slowest)} ms${throttled}${hiddenNote}`];
  }

  /** Opens a socket, saves the run, reloads; afterReload() checks what the server saw. */
  private async reload(): Promise<void> {
    this.setState("running: Reload");
    this.log("info", "-- Reload");
    let conn: Conn;
    try {
      conn = await Conn.open(this.tag("reload"));
      await conn.echo(["before reload"]);
    } catch (e) {
      this.addResult({ name: "Reload", status: "fail", detail: e instanceof Error ? e.message : String(e) });
      await this.finish();
      return;
    }
    this.log("info", "Reloading the page with the socket open");
    const saved: SavedRun = {
      runId: this.runId,
      startedAt: this.startedAt,
      results: this.results,
      log: this.logLines,
      reloadTag: conn.tag,
      reloadAt: Date.now(),
    };
    if (!saveRun(saved)) {
      conn.ws.close(1000);
      this.addResult({ name: "Reload", status: "skip", detail: "sessionStorage is unavailable, so the run can't survive a reload" });
      await this.finish();
      return;
    }
    location.reload();
  }

  private async afterReload(saved: SavedRun): Promise<void> {
    clearRun();
    this.setState("running: Reload");
    this.log("info", `Page reloaded after ${seconds(Date.now() - saved.reloadAt)}`);
    await this.check("Reload", async () => {
      const [status, detail] = await this.expectGoingAway(saved.reloadTag, "page reloaded");
      const conn = await Conn.open(this.tag("reloaded"));
      await conn.echo(["after reload"]);
      conn.ws.close(1000);
      return [status, `${detail}; a new socket works after the reload`];
    });
    await this.finish();
  }

  private async countdown(totalSeconds: number, what: string, keepGoing: () => boolean): Promise<void> {
    const end = performance.now() + totalSeconds * 1000;
    while (performance.now() < end && keepGoing()) {
      this.countdownEl.textContent = `(${what}: ${Math.ceil((end - performance.now()) / 1000)} s left)`;
      await sleep(Math.min(1000, end - performance.now()));
    }
    this.countdownEl.textContent = "";
  }

  private async finish(): Promise<void> {
    const counts: Record<Status, number> = { pass: 0, warn: 0, fail: 0, skip: 0 };
    for (const r of this.results) {
      counts[r.status]++;
    }
    const summary = `${counts.pass} passed, ${counts.warn} warnings, ${counts.fail} failed, ${counts.skip} skipped`;
    const minutes = ((Date.now() - this.startedAt) / 60000).toFixed(1);
    this.summaryEl.textContent = `${summary} (${minutes} min)`;
    this.summaryEl.className = counts.fail > 0 ? "fail" : counts.warn > 0 ? "warn" : "pass";
    const lines = [
      `WebSocket auto test, run ${this.runId}, ${new Date(this.startedAt).toISOString()}`,
      `Browser: ${navigator.userAgent}`,
      `Result: ${summary}`,
      ...this.results.map((r) => `[${r.status.toUpperCase()}] ${r.name}: ${r.detail}`),
    ];
    this.report = lines.join("\n");
    this.copyBtn.hidden = false;
    this.log("info", `Done: ${summary}`);
    try {
      const response = await fetch(REPORT_PATH, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          runId: this.runId,
          browser: navigator.userAgent,
          startedAt: new Date(this.startedAt).toISOString(),
          summary,
          results: this.results,
        }),
      });
      this.setState(response.ok ? "done, report sent to the server log" : `done, but sending the report failed (${response.status})`);
    } catch (e) {
      this.setState(`done, but sending the report failed (${e instanceof Error ? e.message : String(e)})`);
    }
  }

  private async copyReport(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.report);
      this.copyBtn.textContent = "Copied";
    } catch {
      this.copyBtn.textContent = "Copy failed, select the results instead";
    }
  }

  private addResult(result: Result): void {
    this.results.push(result);
    this.renderResult(result);
    this.log(result.status === "pass" ? "ok" : result.status === "fail" ? "error" : "warn",
      `${result.name}: ${result.status.toUpperCase()}, ${result.detail}`);
  }

  private renderResult(result: Result): void {
    const row = document.createElement("tr");
    row.className = result.status;
    for (const text of [result.name, result.status.toUpperCase(), result.detail]) {
      const cell = document.createElement("td");
      cell.textContent = text;
      row.append(cell);
    }
    this.resultsEl.append(row);
  }

  private setState(text: string): void {
    this.stateEl.textContent = text;
  }

  private log(level: "info" | "ok" | "warn" | "error", text: string): void {
    const line = `${level}|${time()} ${text}`;
    this.logLines.push(line);
    this.appendLog(line);
    const method = level === "error" ? console.error : level === "warn" ? console.warn : console.info;
    method(`[ws-auto] ${text}`);
  }

  private appendLog(line: string): void {
    const bar = line.indexOf("|");
    const li = document.createElement("li");
    li.className = line.slice(0, bar);
    li.textContent = line.slice(bar + 1);
    this.logEl.append(li);
    this.logEl.scrollTop = this.logEl.scrollHeight;
  }
}

/** Child mode (?child=<tag>): open the tagged socket, echo once, report to the opener/parent. */
async function runChild(tag: string): Promise<void> {
  element("intro", HTMLElement).textContent = "Child page of the WebSocket auto test. It closes by itself.";
  element("state", HTMLElement).textContent = `child ${tag}`;
  let ready: ChildReady;
  try {
    const conn = await Conn.open(tag);
    await conn.echo(["child"]);
    ready = { type: "ws-auto-child", tag, ok: true, detail: "socket open" };
  } catch (e) {
    ready = { type: "ws-auto-child", tag, ok: false, detail: e instanceof Error ? e.message : String(e) };
  }
  // (instanceof Window would be false: the opener's Window comes from another realm)
  const opener = window.opener as Window | null;
  (opener ?? window.parent).postMessage(ready, location.origin);
}

function loadRun(): SavedRun | null {
  try {
    const json = sessionStorage.getItem(STATE_KEY);
    return json === null ? null : (JSON.parse(json) as SavedRun);
  } catch {
    return null;
  }
}

function saveRun(run: SavedRun): boolean {
  try {
    sessionStorage.setItem(STATE_KEY, JSON.stringify(run));
    return sessionStorage.getItem(STATE_KEY) !== null;
  } catch {
    return false;
  }
}

function clearRun(): void {
  try {
    sessionStorage.removeItem(STATE_KEY);
  } catch {
    // nothing saved
  }
}

function start(): void {
  const child = new URLSearchParams(location.search).get("child");
  if (child !== null) {
    void runChild(child);
  } else {
    new AutoTest().start();
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", start);
} else {
  start();
}

// A module, not a global script, so the test pages' names don't clash.
export {};
