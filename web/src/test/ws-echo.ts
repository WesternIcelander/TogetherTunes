// Test page for the WebSocket echo endpoint (/ws/echo), loaded by /test/ws-echo.html.
// Used for the M0 browser checks: connect, send messages, stay idle, close tabs, reload.
// Every event is logged with a timestamp; close events show code, reason and wasClean.

const ECHO_PATH = "/ws/echo";
const AUTO_CONNECT_KEY = "tt.test.wsEcho.autoConnect";

type Bytes = Uint8Array<ArrayBuffer>;

/** A message we sent and expect back, in order. */
interface Pending {
  readonly id: number;
  readonly data: string | Bytes;
  readonly sentAt: number;
  /** Not logged (the middle of a burst), unless the echo is wrong. */
  readonly quiet: boolean;
}

function element<T extends HTMLElement>(id: string, type: new () => T): T {
  const el = document.getElementById(id);
  if (!(el instanceof type)) {
    throw new Error(`#${id} is missing or not a ${type.name}`);
  }
  return el;
}

function echoUrl(): string {
  const url = new URL(ECHO_PATH, location.href);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  return url.href;
}

function time(): string {
  const d = new Date();
  const pad = (n: number, w = 2): string => String(n).padStart(w, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function formatBytes(n: number): string {
  if (n >= 1024 && n % 1024 === 0) {
    return `${n / 1024} KiB`;
  }
  return `${n} B`;
}

function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  const s = Math.floor(ms / 1000);
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`;
}

/** Standard close codes (RFC 6455 section 7.4.1). */
function describeCloseCode(code: number): string {
  const names: Record<number, string> = {
    1000: "normal",
    1001: "going away",
    1002: "protocol error",
    1003: "unsupported data",
    1005: "no status received",
    1006: "abnormal closure (no close frame)",
    1007: "invalid payload",
    1008: "policy violation",
    1009: "message too big",
    1010: "missing extension",
    1011: "internal error",
    1015: "TLS handshake failure",
  };
  return names[code] ?? "unknown";
}

function randomBytes(length: number): Bytes {
  const bytes = new Uint8Array(length);
  // getRandomValues fills at most 65536 bytes per call
  for (let offset = 0; offset < length; offset += 65536) {
    crypto.getRandomValues(bytes.subarray(offset, Math.min(length, offset + 65536)));
  }
  return bytes;
}

function randomText(length: number): string {
  const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  const bytes = randomBytes(length);
  let text = "";
  for (const b of bytes) {
    text += alphabet.charAt(b % alphabet.length);
  }
  return text;
}

function sameBytes(a: Bytes, b: Bytes): boolean {
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

function describeData(data: string | Bytes): string {
  if (typeof data === "string") {
    const preview = data.length > 60 ? `${data.slice(0, 60)}...` : data;
    return `text (${formatBytes(new TextEncoder().encode(data).length)}) ${JSON.stringify(preview)}`;
  }
  return `binary (${formatBytes(data.length)})`;
}

class EchoTest {
  private readonly stateEl = element("state", HTMLElement);
  private readonly statsEl = element("stats", HTMLElement);
  private readonly logEl = element("log", HTMLOListElement);
  private readonly connectBtn = element("connect", HTMLButtonElement);
  private readonly disconnectBtn = element("disconnect", HTMLButtonElement);
  private readonly autoConnect = element("auto-connect", HTMLInputElement);
  private readonly textInput = element("text", HTMLInputElement);
  private readonly sendTextBtn = element("send-text", HTMLButtonElement);
  private readonly burstBtn = element("burst", HTMLButtonElement);
  private readonly periodic = element("periodic", HTMLInputElement);
  private readonly period = element("period", HTMLInputElement);
  private readonly logMessages = element("log-messages", HTMLInputElement);
  private readonly sizeButtons = Array.from(document.querySelectorAll<HTMLButtonElement>("button.send-size"));

  private ws: WebSocket | null = null;
  private pending: Pending[] = [];
  private nextId = 1;
  private sent = 0;
  private echoed = 0;
  private mismatches = 0;
  private openedAt = 0;
  private lastActivity = 0;
  private periodicTimer: number | undefined;

  start(): void {
    element("url", HTMLElement).textContent = echoUrl();
    this.connectBtn.addEventListener("click", () => this.connect());
    this.disconnectBtn.addEventListener("click", () => this.close());
    element("send-form", HTMLFormElement).addEventListener("submit", (event) => {
      event.preventDefault();
      this.send(this.textInput.value);
    });
    for (const button of this.sizeButtons) {
      button.addEventListener("click", () => {
        const bytes = Number(button.dataset["bytes"]);
        this.send(button.dataset["kind"] === "text" ? randomText(bytes) : randomBytes(bytes));
      });
    }
    this.burstBtn.addEventListener("click", () => {
      for (let i = 1; i <= 100; i++) {
        this.send(`burst ${i}/100`, i !== 1 && i !== 100);
      }
    });
    this.periodic.addEventListener("change", () => this.updatePeriodic());
    this.period.addEventListener("change", () => this.updatePeriodic());
    element("clear", HTMLButtonElement).addEventListener("click", () => {
      this.logEl.replaceChildren();
    });

    this.autoConnect.checked = readFlag(AUTO_CONNECT_KEY);
    this.autoConnect.addEventListener("change", () => writeFlag(AUTO_CONNECT_KEY, this.autoConnect.checked));
    // Logged so that a reload or tab close can be matched with the server's close-info line.
    window.addEventListener("pagehide", () => {
      if (this.ws !== null) {
        console.info(`[ws-echo] page hidden with the socket ${this.stateName()}`);
      }
    });
    window.setInterval(() => this.renderStats(), 1000);

    this.log("info", `Page loaded (${navigator.userAgent})`);
    this.render();
    if (this.autoConnect.checked) {
      this.connect();
    }
  }

  private connect(): void {
    if (this.ws !== null) {
      return;
    }
    const url = echoUrl();
    this.log("info", `Connecting to ${url}`);
    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    this.pending = [];
    const connectStart = performance.now();

    ws.addEventListener("open", () => {
      this.openedAt = performance.now();
      this.lastActivity = this.openedAt;
      const extensions = ws.extensions === "" ? "none" : ws.extensions;
      this.log("ok", `Open after ${formatDuration(this.openedAt - connectStart)} (extensions: ${extensions})`);
      this.render();
      this.updatePeriodic();
    });
    ws.addEventListener("message", (event: MessageEvent<unknown>) => this.onMessage(event.data));
    ws.addEventListener("error", () => {
      // Browsers deliberately give no details here; the close event that follows has the code.
      this.log("error", "Error event");
    });
    ws.addEventListener("close", (event: CloseEvent) => {
      const lifetime = this.openedAt > 0 ? `, open for ${formatDuration(performance.now() - this.openedAt)}` : "";
      const idle = this.openedAt > 0 ? `, idle for ${formatDuration(performance.now() - this.lastActivity)}` : "";
      const reason = event.reason === "" ? "" : ` reason ${JSON.stringify(event.reason)}`;
      const unanswered = this.pending.length > 0 ? `, ${this.pending.length} message(s) never echoed` : "";
      this.log(event.wasClean && event.code === 1000 ? "info" : "warn",
        `Closed: code ${event.code} (${describeCloseCode(event.code)})${reason}, wasClean=${event.wasClean}${lifetime}${idle}${unanswered}`);
      if (this.ws === ws) {
        this.ws = null;
        this.openedAt = 0;
        this.stopPeriodic();
      }
      this.render();
    });
    this.render();
  }

  private close(): void {
    if (this.ws === null) {
      return;
    }
    this.log("info", "Closing with 1000");
    this.ws.close(1000, "closed by test page");
    this.render();
  }

  private send(data: string | Bytes, quiet = false): void {
    const ws = this.ws;
    if (ws === null || ws.readyState !== WebSocket.OPEN) {
      this.log("warn", "Not connected");
      return;
    }
    const entry: Pending = { id: this.nextId++, data, sentAt: performance.now(), quiet };
    this.pending.push(entry);
    ws.send(data);
    this.sent++;
    this.lastActivity = entry.sentAt;
    if (!quiet && this.logMessages.checked) {
      this.log("info", `#${entry.id} sent ${describeData(data)}`);
    }
    this.renderStats();
  }

  private onMessage(raw: unknown): void {
    const now = performance.now();
    this.lastActivity = now;
    let data: string | Bytes;
    if (typeof raw === "string") {
      data = raw;
    } else if (raw instanceof ArrayBuffer) {
      data = new Uint8Array(raw);
    } else {
      this.log("error", `Received an unexpected message type: ${Object.prototype.toString.call(raw)}`);
      return;
    }
    const expected = this.pending.shift();
    if (expected === undefined) {
      this.mismatches++;
      this.log("error", `Unexpected message (nothing pending): ${describeData(data)}`);
      this.renderStats();
      return;
    }
    this.echoed++;
    const matches = typeof expected.data === "string"
      ? data === expected.data
      : data instanceof Uint8Array && sameBytes(data, expected.data);
    if (!matches) {
      this.mismatches++;
      this.log("error", `#${expected.id} echo differs: sent ${describeData(expected.data)}, got ${describeData(data)}`);
    } else if (this.logMessages.checked && !expected.quiet) {
      this.log("ok", `#${expected.id} echoed in ${formatDuration(now - expected.sentAt)}: ${describeData(data)}`);
    }
    this.renderStats();
  }

  private updatePeriodic(): void {
    this.stopPeriodic();
    if (!this.periodic.checked || this.ws === null || this.ws.readyState !== WebSocket.OPEN) {
      return;
    }
    const seconds = Math.min(3600, Math.max(1, Number(this.period.value) || 10));
    let n = 0;
    this.periodicTimer = window.setInterval(() => {
      n++;
      this.send(`periodic ${n} at ${time()}`);
    }, seconds * 1000);
  }

  private stopPeriodic(): void {
    if (this.periodicTimer !== undefined) {
      window.clearInterval(this.periodicTimer);
      this.periodicTimer = undefined;
    }
  }

  private stateName(): string {
    switch (this.ws?.readyState) {
      case WebSocket.CONNECTING:
        return "connecting";
      case WebSocket.OPEN:
        return "open";
      case WebSocket.CLOSING:
        return "closing";
      default:
        return "closed";
    }
  }

  private render(): void {
    const state = this.stateName();
    this.stateEl.textContent = state;
    const open = state === "open";
    this.connectBtn.disabled = this.ws !== null;
    this.disconnectBtn.disabled = !open;
    this.sendTextBtn.disabled = !open;
    this.burstBtn.disabled = !open;
    for (const button of this.sizeButtons) {
      button.disabled = !open;
    }
    this.renderStats();
  }

  private renderStats(): void {
    const parts = [`sent ${this.sent}`, `echoed ${this.echoed}`, `pending ${this.pending.length}`, `mismatches ${this.mismatches}`];
    if (this.openedAt > 0) {
      const now = performance.now();
      parts.push(`open ${formatDuration(now - this.openedAt)}`, `idle ${formatDuration(now - this.lastActivity)}`);
    }
    this.statsEl.textContent = ` (${parts.join(", ")})`;
  }

  private log(level: "info" | "ok" | "warn" | "error", text: string): void {
    const line = `${time()} ${text}`;
    const li = document.createElement("li");
    li.className = level;
    li.textContent = line;
    const atBottom = this.logEl.scrollTop + this.logEl.clientHeight >= this.logEl.scrollHeight - 4;
    this.logEl.append(li);
    if (atBottom) {
      this.logEl.scrollTop = this.logEl.scrollHeight;
    }
    const consoleMethod = level === "error" ? console.error : level === "warn" ? console.warn : console.info;
    consoleMethod(`[ws-echo] ${line}`);
  }
}

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, value ? "1" : "0");
  } catch {
    // storage unavailable (private mode): the checkbox just isn't remembered
  }
}

function start(): void {
  new EchoTest().start();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", start);
} else {
  start();
}

// A module, not a global script, so the test pages' names don't clash.
export {};
