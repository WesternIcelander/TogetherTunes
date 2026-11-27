// Shared parts of the test environment: paths, the jar, starting and stopping a server on a data
// dir, copying the fixture, and a small API client. Used by the fixture builder (fixture.mjs) and
// the smoke helper (smoke.mjs). Node built-ins only.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const TOOL_DIR = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(TOOL_DIR, '..', '..');
export const FIXTURE_DIR = path.join(REPO_ROOT, '.testenv');
export const JAR = path.join(REPO_ROOT, 'target', 'TogetherTunes-1.0-SNAPSHOT.jar');

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A port nobody listens on right now (the OS picks it). */
export function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.unref();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

/** Builds the jar (`mvn package -DskipTests`) when it is missing. */
export function ensureJar(log = console.log) {
  if (fs.existsSync(JAR)) return JAR;
  log('testenv: building the jar (mvn package -DskipTests)…');
  const r = spawnSync('mvn', ['-q', 'package', '-DskipTests'], { cwd: REPO_ROOT, stdio: 'inherit' });
  if (r.status !== 0 || !fs.existsSync(JAR)) throw new Error('testenv: mvn package -DskipTests failed');
  return JAR;
}

/**
 * Copies a data dir: an APFS clone (`cp -c`, near instant, no extra space) on macOS, a normal
 * recursive copy elsewhere or when cloning isn't possible.
 */
export function copyDataDir(from, to) {
  if (process.platform === 'darwin') {
    const r = spawnSync('cp', ['-cR', from, to], { stdio: 'ignore' });
    if (r.status === 0) return;
    fs.rmSync(to, { recursive: true, force: true });
  }
  fs.cpSync(from, to, { recursive: true });
}

/** Processes started here; killed on exit, SIGINT and SIGTERM so nothing keeps a port. */
const live = new Set();
/** Temp dirs removed on exit too (stop() removes them normally). */
const dirs = new Set();
let hooked = false;
function hookExit() {
  if (hooked) return;
  hooked = true;
  const killAll = () => {
    for (const p of live) {
      try { p.kill('SIGKILL'); } catch { /* gone */ }
    }
    live.clear();
    for (const d of dirs) {
      try { fs.rmSync(d, { recursive: true, force: true }); } catch { /* best effort */ }
    }
    dirs.clear();
  };
  process.on('exit', killAll);
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => {
      killAll();
      process.exit(130);
    });
  }
}

/** Remembers a child process so it is killed when this process ends. */
export function track(child) {
  hookExit();
  live.add(child);
  child.once('exit', () => live.delete(child));
  return child;
}

/** Stops a child: SIGTERM, then SIGKILL after `graceMs`. Resolves when it has exited. */
export async function stopProcess(child, graceMs = 5000) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const exited = new Promise((r) => child.once('exit', r));
  try { child.kill('SIGTERM'); } catch { /* gone */ }
  const t = setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* gone */ } }, graceMs);
  await exited;
  clearTimeout(t);
}

/** The last `n` lines of a file ('' if it can't be read). */
export function tail(file, n = 40) {
  try {
    return fs.readFileSync(file, 'utf8').split('\n').slice(-n).join('\n');
  } catch {
    return '';
  }
}

/**
 * Starts the server on `dataDir` (its own config file in `workDir`, a free port, log in
 * `workDir/server.log`) and waits until `GET /` answers 200.
 *
 * @returns {{base: string, port: number, process, log: string, stop: () => Promise<void>}}
 */
export async function startServer({ dataDir, workDir, port, timeoutMs = 90000, log = console.log }) {
  ensureJar(log);
  port = port ?? await freePort();
  const base = `http://127.0.0.1:${port}`;
  fs.mkdirSync(workDir, { recursive: true });
  const config = path.join(workDir, 'config.json');
  fs.writeFileSync(config, JSON.stringify({
    http: { bind: '127.0.0.1', port, publicBaseUrl: base },
    data: { dir: dataDir },
  }, null, 2));
  const logFile = path.join(workDir, 'server.log');
  const out = fs.openSync(logFile, 'a');
  // Without TOGETHERTUNES_* from the caller's shell, so only this config applies.
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith('TOGETHERTUNES_')));
  const child = track(spawn('java', ['-jar', JAR, '--config', config], {
    cwd: workDir, env, stdio: ['ignore', out, out],
  }));
  fs.closeSync(out);
  const stop = () => stopProcess(child);
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (child.exitCode !== null) {
      throw new Error(`testenv: the server exited with ${child.exitCode} during startup:\n${tail(logFile)}`);
    }
    try {
      const r = await fetch(base + '/', { signal: AbortSignal.timeout(2000) });
      await r.arrayBuffer();
      if (r.status === 200) break;
    } catch { /* not up yet */ }
    if (Date.now() > deadline) {
      await stop();
      throw new Error(`testenv: the server didn't answer 200 on ${base}/ within ${timeoutMs} ms:\n${tail(logFile)}`);
    }
    await sleep(200);
  }
  return { base, port, process: child, log: logFile, stop };
}

/**
 * A temp dir under the OS temp dir, removed by the caller, or on exit / SIGINT unless `keep`.
 */
export function tempDir(prefix, { keep = false } = {}) {
  const d = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), prefix));
  if (!keep) {
    hookExit();
    dirs.add(d);
  }
  return d;
}

/**
 * A JSON API client for one actor: keeps its cookies, sends `Origin` like a browser would.
 * `call(method, path, body?)` → the parsed JSON; throws on a status ≥ 400 unless `ok` lists it.
 */
export function apiClient(base) {
  const jar = new Map();
  const client = {
    base,
    cookies: jar,
    cookieHeader: () => [...jar].map(([k, v]) => `${k}=${v}`).join('; '),
    async raw(method, p, { body, headers = {}, contentType } = {}) {
      const h = { Origin: base, ...headers };
      if (jar.size) h.Cookie = client.cookieHeader();
      let payload = body;
      if (body !== undefined && !(body instanceof Uint8Array) && typeof body !== 'string') {
        payload = JSON.stringify(body);
        h['Content-Type'] = 'application/json';
      } else if (contentType) {
        h['Content-Type'] = contentType;
      }
      const r = await fetch(base + p, { method, headers: h, body: payload, signal: AbortSignal.timeout(60000) });
      for (const c of r.headers.getSetCookie()) {
        const [pair, ...attrs] = c.split(';');
        const i = pair.indexOf('=');
        const name = pair.slice(0, i).trim();
        const value = pair.slice(i + 1).trim();
        const expired = attrs.some((a) => /^\s*max-age=0\s*$/i.test(a)) || value === '';
        if (expired) jar.delete(name); else jar.set(name, value);
      }
      return r;
    },
    async call(method, p, body, { ok = [] } = {}) {
      const r = await client.raw(method, p, { body });
      const text = await r.text();
      if (r.status >= 400 && !ok.includes(r.status)) {
        throw new Error(`${method} ${p} → ${r.status} ${text.slice(0, 300)}`);
      }
      return text ? JSON.parse(text) : null;
    },
  };
  return client;
}
