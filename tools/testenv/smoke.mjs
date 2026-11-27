// The browser smoke helper: a server on a throwaway copy of the fixture plus headless Chrome driven
// over the DevTools protocol (Node's built-in WebSocket, no packages).
//
//   import { start } from '../../tools/testenv/smoke.mjs';   // or an absolute path
//   const env = await start({ viewport: { width: 390, height: 844 }, touch: true });
//   try {
//     const page = env.page;
//     await env.login(page, 'A');
//     await page.goto('/songs');
//     await page.waitFor('.app-header');
//     console.log(await page.rects(['.app-header', '#player-bar']));
//   } finally {
//     const report = await env.stop();   // {pageErrors, consoleErrors, dialogs}
//   }
//
// Every action has a timeout (default 10 s) and fails with an error naming the action. Every
// JavaScript dialog is answered at once (alert: OK; confirm: OK unless `confirm: false`; prompt:
// the answer given with `page.answerPrompt(text)`, else cancelled), so a dialog never hangs Chrome.
// stop() always kills Chrome and the server and removes the copy, also after a failure, and both
// are killed on SIGINT/SIGTERM or when Node exits.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { copyDataDir, sleep, startServer, stopProcess, tempDir, track, apiClient } from './common.mjs';
import { DATA, ensureFixture } from './fixture.mjs';

export const DEFAULT_TIMEOUT_MS = 10000;

/** Chrome's binary: $CHROME, else the usual places on macOS and Linux. */
export function findChrome() {
  const candidates = [
    process.env.CHROME,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser',
  ].filter(Boolean);
  const found = candidates.find((c) => fs.existsSync(c));
  if (!found) throw new Error('testenv: no Chrome found; set CHROME to its binary');
  return found;
}

/** Runs `promise` with a deadline; the error names `what`. */
function timed(what, ms, promise) {
  let t;
  const timeout = new Promise((_, reject) => {
    t = setTimeout(() => reject(new Error(`testenv: ${what} timed out after ${ms} ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(t));
}

/** One DevTools connection to the browser; pages are flattened sessions on it. */
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 0;
    this.pending = new Map();
    this.listeners = new Set();
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id !== undefined) {
        const p = this.pending.get(m.id);
        if (!p) return;
        this.pending.delete(m.id);
        if (m.error) p.reject(new Error(`${p.method}: ${m.error.message}${m.error.data ? ' (' + m.error.data + ')' : ''}`));
        else p.resolve(m.result);
        return;
      }
      for (const l of this.listeners) l(m);
    });
    ws.addEventListener('close', () => {
      for (const p of this.pending.values()) p.reject(new Error(`${p.method}: the browser connection closed`));
      this.pending.clear();
    });
  }

  send(method, params = {}, sessionId) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      const msg = { id, method, params };
      if (sessionId) msg.sessionId = sessionId;
      this.ws.send(JSON.stringify(msg));
    });
  }
}

const RECT_FN = `(sels) => {
  const out = {};
  for (const s of sels) {
    const e = document.querySelector(s);
    if (!e || e.getClientRects().length === 0) { out[s] = null; continue; }
    const b = e.getBoundingClientRect();
    out[s] = { x: Math.round(b.left), y: Math.round(b.top), width: Math.round(b.width), height: Math.round(b.height) };
  }
  return out;
}`;

/** A page (tab) in its own browser context unless `shared` is set. */
class Page {
  constructor(env, sessionId, targetId, contextId, opts) {
    this.env = env;
    this.cdp = env.cdp;
    this.sessionId = sessionId;
    this.targetId = targetId;
    this.contextId = contextId;
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.confirmAnswer = opts.confirm ?? true;
    this.promptAnswers = [];
    this.pageErrors = [];
    this.consoleErrors = [];
    this.dialogs = [];
  }

  send(method, params) {
    return this.cdp.send(method, params, this.sessionId);
  }

  /** Runs an action under the page's timeout (or `ms`). */
  act(what, fn, ms = this.timeoutMs) {
    return timed(what, ms, fn());
  }

  /** The answer for the next `prompt()` (then cancelled again). Several calls queue answers. */
  answerPrompt(text) {
    this.promptAnswers.push(String(text));
  }

  async onEvent(m) {
    const p = m.params;
    switch (m.method) {
      case 'Page.javascriptDialogOpening': {
        let accept = true;
        let promptText;
        if (p.type === 'confirm') accept = this.confirmAnswer;
        if (p.type === 'prompt') {
          if (this.promptAnswers.length) promptText = this.promptAnswers.shift();
          else accept = false;
        }
        this.dialogs.push({ type: p.type, message: p.message, accepted: accept, answer: promptText });
        if (p.type === 'prompt' && !accept) {
          this.pageErrors.push(`prompt() without an answer (cancelled): ${p.message}`);
        }
        this.send('Page.handleJavaScriptDialog', promptText === undefined ? { accept } : { accept, promptText })
          .catch(() => {});
        break;
      }
      case 'Runtime.exceptionThrown': {
        const d = p.exceptionDetails;
        this.pageErrors.push((d.exception?.description || d.text || 'exception').split('\n').slice(0, 3).join(' | ')
          + (d.url ? ` @ ${d.url}:${d.lineNumber + 1}` : ''));
        break;
      }
      case 'Runtime.consoleAPICalled':
        if (p.type === 'error' || p.type === 'assert') {
          this.consoleErrors.push(p.args.map((a) => a.value ?? a.description ?? a.type).join(' ').slice(0, 500));
        }
        break;
      case 'Log.entryAdded':
        if (p.entry.level === 'error') this.consoleErrors.push(`${p.entry.text}${p.entry.url ? ' ' + p.entry.url : ''}`.slice(0, 500));
        break;
      default:
    }
  }

  /** Viewport size; `touch` turns on touch emulation (and a mobile viewport unless `mobile: false`). */
  async setViewport({ width, height, deviceScaleFactor, touch = false, mobile } = {}) {
    await this.act(`setViewport ${width}×${height}`, async () => {
      await this.send('Emulation.setDeviceMetricsOverride', {
        width, height, deviceScaleFactor: deviceScaleFactor ?? (touch ? 2 : 1), mobile: mobile ?? touch,
      });
      await this.send('Emulation.setTouchEmulationEnabled', touch ? { enabled: true, maxTouchPoints: 5 } : { enabled: false });
      await this.send('Emulation.setEmitTouchEventsForMouse', { enabled: false });
    });
  }

  /** Opens a path on the server (or a full URL) and waits for its load event. */
  async goto(urlOrPath, { timeoutMs } = {}) {
    const url = /^https?:/.test(urlOrPath) ? urlOrPath : this.env.base + urlOrPath;
    await this.act(`goto ${url}`, async () => {
      const loaded = this.waitEvent('Page.loadEventFired');
      const r = await this.send('Page.navigate', { url });
      if (r.errorText) throw new Error(`goto ${url}: ${r.errorText}`);
      await loaded;
    }, timeoutMs);
  }

  async reload() {
    await this.act('reload', async () => {
      const loaded = this.waitEvent('Page.loadEventFired');
      await this.send('Page.reload', {});
      await loaded;
    });
  }

  waitEvent(method) {
    return new Promise((resolve) => {
      const l = (m) => {
        if (m.sessionId === this.sessionId && m.method === method) {
          this.cdp.listeners.delete(l);
          resolve(m.params);
        }
      };
      this.cdp.listeners.add(l);
    });
  }

  /**
   * Evaluates in the page and returns the value (JSON-able). `fn` is a function, called with
   * `args`, or an expression string. Promises are awaited. A thrown error is rethrown here.
   */
  async eval(fn, ...args) {
    const expression = typeof fn === 'function' ? `(${fn.toString()})(...${JSON.stringify(args)})` : fn;
    const label = typeof fn === 'function' ? fn.toString().replace(/\s+/g, ' ').slice(0, 60) : fn.slice(0, 60);
    return this.act(`eval ${label}`, async () => {
      const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true });
      if (r.exceptionDetails) {
        throw new Error(`eval ${label}: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
      }
      return r.result.value;
    });
  }

  /**
   * Waits until a selector (a string) matches a visible element, or a function (run in the page,
   * called with `args`; it can't see Node variables) returns something truthy, which is returned.
   */
  async waitFor(what, { timeoutMs = this.timeoutMs, intervalMs = 100, args = [] } = {}) {
    const isSel = typeof what === 'string';
    const label = isSel ? what : what.toString().replace(/\s+/g, ' ').slice(0, 60);
    const check = isSel
      ? `(() => { const e = document.querySelector(${JSON.stringify(what)}); return !!e && e.getClientRects().length > 0; })()`
      : `(${what.toString()})(...${JSON.stringify(args)})`;
    const deadline = Date.now() + timeoutMs;
    let lastError;
    for (;;) {
      try {
        const r = await timed(`waitFor ${label}`, timeoutMs,
          this.send('Runtime.evaluate', { expression: check, returnByValue: true, awaitPromise: true }));
        if (!r.exceptionDetails && r.result.value) return r.result.value;
        lastError = r.exceptionDetails?.exception?.description;
      } catch (e) {
        lastError = e.message;
      }
      if (Date.now() > deadline) {
        throw new Error(`testenv: waitFor ${label} timed out after ${timeoutMs} ms${lastError ? ` (last error: ${lastError})` : ''}`);
      }
      await sleep(intervalMs);
    }
  }

  /** The centre of the first element matching `selector`, scrolled into view. */
  async center(selector) {
    const c = await this.eval((sel) => {
      const e = document.querySelector(sel);
      if (!e) return null;
      e.scrollIntoView({ block: 'center', inline: 'center' });
      const b = e.getBoundingClientRect();
      if (b.width === 0 && b.height === 0) return null;
      return [b.left + b.width / 2, b.top + b.height / 2];
    }, selector);
    if (!c) throw new Error(`testenv: no visible element for ${selector}`);
    return c;
  }

  /** A real mouse click (or `clickCount: 2`, `button: 'right'`) on an element or at [x, y]. */
  async click(target, { button = 'left', clickCount = 1 } = {}) {
    await this.act(`click ${target}`, async () => {
      const [x, y] = Array.isArray(target) ? target : await this.center(target);
      await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
      for (let i = 1; i <= clickCount; i++) {
        await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, clickCount: i });
        await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, clickCount: i });
      }
    });
  }

  /** A touch tap (needs touch emulation, see setViewport); `holdMs` makes it a long press. */
  async tap(target, { holdMs = 0 } = {}) {
    await this.act(`tap ${target}`, async () => {
      const [x, y] = Array.isArray(target) ? target : await this.center(target);
      await this.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
      if (holdMs) await sleep(holdMs);
      await this.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    }, this.timeoutMs + holdMs);
  }

  /** Focuses `selector` (when given) and types `text` as keyboard input. */
  async type(selector, text) {
    await this.act(`type into ${selector}`, async () => {
      if (selector) await this.eval((sel) => { const e = document.querySelector(sel); if (!e) throw new Error('no ' + sel); e.focus(); }, selector);
      await this.send('Input.insertText', { text });
    });
  }

  /** Presses one key, e.g. 'Enter', 'Escape', 'ArrowDown'. */
  async press(key) {
    const codes = { Enter: 13, Escape: 27, Tab: 9, Backspace: 8, ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39, ' ': 32 };
    await this.act(`press ${key}`, async () => {
      const k = { key, code: key === ' ' ? 'Space' : key, windowsVirtualKeyCode: codes[key] ?? 0 };
      await this.send('Input.dispatchKeyEvent', { type: 'keyDown', ...k, text: key === 'Enter' ? '\r' : key.length === 1 ? key : undefined });
      await this.send('Input.dispatchKeyEvent', { type: 'keyUp', ...k });
    });
  }

  /** Saves a PNG of the viewport (or `fullPage`) and returns its path. */
  async screenshot(file, { fullPage = false } = {}) {
    return this.act(`screenshot ${file}`, async () => {
      const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: fullPage });
      const out = path.resolve(file);
      fs.mkdirSync(path.dirname(out), { recursive: true });
      fs.writeFileSync(out, Buffer.from(r.data, 'base64'));
      return out;
    });
  }

  /** {selector: {x, y, width, height} | null} for the first visible match of each selector. */
  async rects(selectors) {
    return this.eval(`(${RECT_FN})(${JSON.stringify(selectors)})`);
  }

  async close() {
    await this.cdp.send('Target.closeTarget', { targetId: this.targetId }).catch(() => {});
    if (this.contextId) await this.cdp.send('Target.disposeBrowserContext', { browserContextId: this.contextId }).catch(() => {});
  }
}

/**
 * Starts a server on a fresh copy of the fixture and headless Chrome with one page.
 *
 * @param {object} [opts]
 * @param {{width: number, height: number}} [opts.viewport] default 1400×900
 * @param {boolean} [opts.touch] touch emulation (and a mobile viewport) for the first page
 * @param {boolean} [opts.autoplay] let media play without a user gesture (default: Chrome's rules)
 * @param {boolean} [opts.headless] false shows the window (default true)
 * @param {boolean} [opts.keep] keep the temp dir (server log, Chrome profile, data copy)
 * @param {number} [opts.timeoutMs] default action timeout
 * @param {boolean} [opts.rebuild] rebuild the fixture first
 * @param {string[]} [opts.chromeArgs] more Chrome flags
 */
export async function start(opts = {}) {
  const log = opts.log ?? ((s) => console.error(s));
  const manifest = await ensureFixture({ rebuild: opts.rebuild, log });
  const dir = tempDir('tt-smoke-', { keep: opts.keep });
  const env = {
    manifest, dir, pages: [], server: null, chrome: null, cdp: null, stopped: false,
  };
  env.stop = async () => {
    if (env.stopped) return env.report();
    env.stopped = true;
    try { await env.cdp?.send('Browser.close').catch(() => {}); } catch { /* closing */ }
    try { env.cdp?.ws.close(); } catch { /* closed */ }
    await stopProcess(env.chrome, 3000);
    await env.server?.stop();
    if (!opts.keep) fs.rmSync(dir, { recursive: true, force: true });
    else log(`testenv: kept ${dir}`);
    return env.report();
  };
  env.report = () => ({
    pageErrors: env.pages.flatMap((p) => p.pageErrors),
    consoleErrors: env.pages.flatMap((p) => p.consoleErrors),
    dialogs: env.pages.flatMap((p) => p.dialogs),
    serverLog: opts.keep ? env.server?.log : undefined,
  });
  try {
    const data = path.join(dir, 'data');
    copyDataDir(DATA, data);
    env.server = await startServer({ dataDir: data, workDir: dir, log });
    env.base = env.server.base;
    env.serverLog = env.server.log;

    const profile = path.join(dir, 'chrome-profile');
    fs.mkdirSync(profile);
    const args = [
      opts.headless === false ? null : '--headless=new',
      '--remote-debugging-port=0', `--user-data-dir=${profile}`,
      '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
      '--disable-component-update', '--disable-sync', '--mute-audio', '--password-store=basic',
      '--use-mock-keychain', '--disable-features=Translate,MediaRouter',
      opts.autoplay ? '--autoplay-policy=no-user-gesture-required' : null,
      ...(opts.chromeArgs ?? []), 'about:blank',
    ].filter(Boolean);
    const chromeLog = fs.openSync(path.join(dir, 'chrome.log'), 'a');
    env.chrome = track(spawn(findChrome(), args, { stdio: ['ignore', chromeLog, chromeLog] }));
    fs.closeSync(chromeLog);
    const portFile = path.join(profile, 'DevToolsActivePort');
    const wsUrl = await timed('starting Chrome', 30000, (async () => {
      for (;;) {
        if (env.chrome.exitCode !== null) throw new Error('testenv: Chrome exited at startup; see ' + path.join(dir, 'chrome.log'));
        try {
          const [port, p] = fs.readFileSync(portFile, 'utf8').split('\n');
          if (port && p) return `ws://127.0.0.1:${port.trim()}${p.trim()}`;
        } catch { /* not yet */ }
        await sleep(50);
      }
    })());
    const ws = new WebSocket(wsUrl);
    await timed('connecting to Chrome', 10000, new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true });
      ws.addEventListener('error', () => reject(new Error('testenv: could not connect to Chrome')), { once: true });
    }));
    env.cdp = new Cdp(ws);
    env.cdp.listeners.add((m) => {
      const page = env.pages.find((p) => p.sessionId === m.sessionId);
      if (page) page.onEvent(m);
    });

    /**
     * A new page. Each page has its own browser context (cookies, storage) unless `shared: true`,
     * so A, B and a guest can be open at once.
     */
    env.newPage = async (popts = {}) => {
      let contextId;
      if (!popts.shared) contextId = (await env.cdp.send('Target.createBrowserContext', { disposeOnDetach: true })).browserContextId;
      const { targetId } = await env.cdp.send('Target.createTarget', contextId ? { url: 'about:blank', browserContextId: contextId } : { url: 'about:blank' });
      const { sessionId } = await env.cdp.send('Target.attachToTarget', { targetId, flatten: true });
      const page = new Page(env, sessionId, targetId, contextId, { timeoutMs: opts.timeoutMs, ...popts });
      env.pages.push(page);
      await page.send('Page.enable');
      await page.send('Runtime.enable');
      await page.send('Log.enable');
      await page.send('Network.enable');
      const vp = popts.viewport ?? opts.viewport ?? { width: 1400, height: 900 };
      await page.setViewport({ ...vp, touch: popts.touch ?? opts.touch });
      return page;
    };

    /** Signs a page in as 'A' or 'B' (password login through the API; the page's cookies). */
    env.login = async (page, who) => {
      const u = manifest.users[who];
      if (!u) throw new Error(`testenv: login: no user ${who} (A or B)`);
      await page.act(`login ${who}`, async () => {
        const client = apiClient(env.base);
        await client.call('POST', '/api/auth/login', { username: u.username, password: u.password });
        for (const [name, value] of client.cookies) {
          await page.send('Network.setCookie', { name, value, url: env.base, httpOnly: true, sameSite: 'Lax' });
        }
      });
    };

    /** Makes a page a guest (clears its cookies, then POST /api/me/guest from the page). */
    env.guest = async (page, name) => {
      await page.send('Network.clearBrowserCookies');
      if (!(await page.eval('location.origin')).startsWith(env.base)) await page.goto('/');
      return page.eval((n) => fetch('/api/me/guest', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(n ? { name: n } : {}),
      }).then(async (r) => { if (r.status >= 400) throw new Error('POST /api/me/guest → ' + r.status); return r.json(); }), name ?? null);
    };

    /** An API client signed in as 'A' or 'B', or anonymous without `who`. */
    env.api = async (who) => {
      const client = apiClient(env.base);
      if (who) {
        const u = manifest.users[who];
        await client.call('POST', '/api/auth/login', { username: u.username, password: u.password });
      }
      return client;
    };

    env.page = await env.newPage({});
    return env;
  } catch (e) {
    await env.stop();
    throw e;
  }
}

/** start(opts), run `fn(env)`, always stop; returns {result, report}. */
export async function withSmoke(opts, fn) {
  const env = await start(opts);
  try {
    const result = await fn(env);
    return { result, report: await env.stop() };
  } finally {
    await env.stop();
  }
}
