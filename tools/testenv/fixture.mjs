// The fixture builder: a server data dir with users, a room, a playlist, likes and generated media
// in every type, all ready, plus .testenv/manifest.json describing it. Built on first use, and again
// when the key (key.mjs) changes or with --rebuild.
//
//   node tools/testenv/fixture.mjs [--rebuild] [--print-key]
//
// (use web/node/node when there's no Node on the PATH). Media is generated with ffmpeg's lavfi
// sources only.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FIXTURE_DIR, REPO_ROOT, apiClient, ensureJar, sleep, startServer } from './common.mjs';
import { computeKey } from './key.mjs';
import { ITEM_ROLES, MANIFEST_VERSION, checkManifest } from './manifest.mjs';

export const MANIFEST = path.join(FIXTURE_DIR, 'manifest.json');
export const DATA = path.join(FIXTURE_DIR, 'data');
const LOCK = FIXTURE_DIR + '.lock';

export const USERS = {
  A: { username: 'alice', password: 'alice-test-password', displayName: 'Alice', admin: true },
  B: { username: 'bob', password: 'bob-test-password', displayName: 'Bob', admin: false },
};
export const ROOM = { name: 'Test Room', slug: 'testroom', visibility: 'public' };
export const PLAYLIST = { name: 'Test Playlist', items: ['audioCover', 'audioNoCover', 'mvLandscape', 'mp3Headerless'] };
export const LIKED = { A: ['audioCover', 'mvPortrait'], B: ['audioNoCover'] };

/**
 * One generated source per role: file name, ffmpeg args, upload details. Videos need motion: a
 * still picture (e.g. plain colour bars) is taken as audio with a still image.
 */
const D = '4'; // seconds
const VIDEO_OUT = ['-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '128k', '-shortest'];
export const MEDIA = {
  audioCover: {
    file: 'audio-cover.mp3', title: 'Test Tone With Cover', artist: 'Test Artist',
    args: ['-f', 'lavfi', '-i', `sine=f=440:d=${D}`, '-i', 'cover.jpg', '-map', '0:a', '-map', '1:v',
      '-c:a', 'libmp3lame', '-q:a', '4', '-c:v', 'copy', '-disposition:v', 'attached_pic', '-id3v2_version', '3',
      '-metadata', 'title=Test Tone With Cover', '-metadata', 'artist=Test Artist'],
  },
  audioNoCover: {
    file: 'audio-plain.m4a', title: 'Test Tone Plain', artist: 'Test Artist',
    args: ['-f', 'lavfi', '-i', `sine=f=550:d=${D}`, '-c:a', 'aac', '-b:a', '128k'],
  },
  mvLandscape: {
    file: 'mv-landscape.mp4', title: 'Landscape Music Video', artist: 'Video Artist', mediaType: 'music_video',
    args: ['-f', 'lavfi', '-i', `testsrc2=s=1280x720:r=30:d=${D}`, '-f', 'lavfi', '-i', `sine=f=660:d=${D}`, ...VIDEO_OUT],
  },
  mvPortrait: {
    file: 'mv-portrait.mp4', title: 'Portrait Music Video', artist: 'Video Artist', mediaType: 'music_video',
    args: ['-f', 'lavfi', '-i', `testsrc2=s=720x1280:r=30:d=${D}`, '-f', 'lavfi', '-i', `sine=f=770:d=${D}`, ...VIDEO_OUT],
  },
  movie: {
    file: 'movie.mp4', title: 'Test Movie', mediaType: 'movie',
    args: ['-f', 'lavfi', '-i', `testsrc2=s=1280x720:r=30:d=${D}`, '-f', 'lavfi', '-i', `sine=f=330:d=${D}`, '-vf', 'hue=h=120', ...VIDEO_OUT],
  },
  showEpisode: {
    file: 'show-s01e02.mp4', title: 'Test Episode', mediaType: 'show', show: { name: 'Test Show', season: 1, episode: 2 },
    args: ['-f', 'lavfi', '-i', `testsrc=s=1280x720:r=30:d=${D}`, '-f', 'lavfi', '-i', `sine=f=380:d=${D}`, ...VIDEO_OUT],
  },
  video: {
    file: 'video.mp4', title: 'Test Video', mediaType: 'video',
    args: ['-f', 'lavfi', '-i', `mandelbrot=s=1280x720:r=30`, '-f', 'lavfi', '-i', `sine=f=420:d=${D}`, '-t', D, ...VIDEO_OUT],
  },
  uncategorized: {
    file: 'uncategorized.mp4', title: 'Uncategorized Clip', mediaType: 'uncategorized',
    args: ['-f', 'lavfi', '-i', `testsrc2=s=1280x720:r=30:d=${D}`, '-f', 'lavfi', '-i', `sine=f=480:d=${D}`, '-vf', 'negate', ...VIDEO_OUT],
  },
  mp3Headerless: {
    // VBR without a Xing/Info header and a silent first second: ffmpeg's bitrate estimate of the
    // length is far off, as in the MP3 length check step.
    file: 'headerless.mp3', title: 'Headerless MP3', artist: 'Test Artist',
    args: ['-f', 'lavfi', '-i', 'anoisesrc=r=44100:a=0.3:seed=7', '-af',
      "volume='if(lt(t,1),0,1)':eval=frame,aformat=channel_layouts=stereo", '-t', '5',
      '-c:a', 'libmp3lame', '-q:a', '2', '-write_xing', '0', '-id3v2_version', '0'],
  },
};

function ffmpeg(cwd, args) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { cwd, encoding: 'utf8' });
  if (r.error) throw new Error(`testenv: ffmpeg couldn't run (${r.error.message}); it must be on the PATH`);
  if (r.status !== 0) throw new Error(`testenv: ffmpeg ${args.join(' ')} failed:\n${r.stderr}`);
}

/** Generates every source file into `dir`. */
export function generateMedia(dir) {
  fs.mkdirSync(dir, { recursive: true });
  ffmpeg(dir, ['-f', 'lavfi', '-i', 'testsrc2=s=600x600', '-frames:v', '1', 'cover.jpg']);
  for (const m of Object.values(MEDIA)) ffmpeg(dir, [...m.args, m.file]);
}

async function upload(client, file, details) {
  const bytes = fs.readFileSync(file);
  const started = await client.call('POST', '/api/uploads', { filename: path.basename(file), size: bytes.length });
  const chunk = started.chunkSize || bytes.length;
  for (let off = 0; off < bytes.length; off += chunk) {
    const end = Math.min(off + chunk, bytes.length) - 1;
    const r = await client.raw('PUT', `/api/uploads/${started.uploadId}`, {
      body: bytes.subarray(off, end + 1), contentType: 'application/octet-stream',
      headers: { 'Content-Range': `bytes ${off}-${end}/${bytes.length}` },
    });
    if (r.status !== 200) throw new Error(`PUT upload → ${r.status} ${await r.text()}`);
    await r.arrayBuffer().catch(() => {});
  }
  const body = { title: details.title };
  if (details.artist) body.artist = details.artist;
  if (details.mediaType) body.mediaType = details.mediaType;
  if (details.show) body.show = details.show;
  const done = await client.call('POST', `/api/uploads/${started.uploadId}/complete`, body);
  return done.itemId;
}

/** Waits until every item is ready (any failed one is an error) and no ingest job is left. */
async function waitForIngest(admin, ids, log, timeoutMs = 15 * 60 * 1000) {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  for (;;) {
    const items = await Promise.all(ids.map((id) => admin.call('GET', `/api/items/${id}`)));
    const failed = items.filter((i) => i.status === 'failed');
    if (failed.length) throw new Error('testenv: ingest failed for ' + failed.map((i) => i.title).join(', '));
    const ready = items.filter((i) => i.status === 'ready').length;
    const jobs = (await admin.call('GET', '/api/admin/jobs?state=active&limit=200')).items;
    const line = `testenv: ingest ${ready}/${ids.length} ready, ${jobs.length} job(s) active`;
    if (line !== last) { log(line); last = line; }
    if (ready === ids.length && jobs.length === 0) return items;
    if (Date.now() > deadline) throw new Error('testenv: ingest timed out: ' + line);
    await sleep(1000);
  }
}

/** Builds the fixture into `dir` (data/, manifest.json). */
async function build(dir, key, log) {
  const t0 = Date.now();
  const media = path.join(dir, 'media-src');
  log('testenv: generating media…');
  generateMedia(media);
  const server = await startServer({ dataDir: path.join(dir, 'data'), workDir: dir, log });
  try {
    const base = server.base;
    const A = apiClient(base);
    const B = apiClient(base);
    const u = USERS;
    await A.call('POST', '/api/setup', { username: u.A.username, password: u.A.password, displayName: u.A.displayName });
    await A.call('POST', '/api/auth/login', { username: u.A.username, password: u.A.password });
    await A.call('POST', '/api/admin/users', { username: u.B.username, password: u.B.password, displayName: u.B.displayName });
    await B.call('POST', '/api/auth/login', { username: u.B.username, password: u.B.password });
    const meA = await A.call('GET', '/api/me');
    const meB = await B.call('GET', '/api/me');
    await A.call('PATCH', '/api/admin/settings', {
      'guests.enabled': true, 'guests.canVote': true, 'guests.canRequestLibrary': true, 'guests.canUpload': true,
    });
    const room = await A.call('POST', '/api/rooms', { name: ROOM.name, slug: ROOM.slug, visibility: ROOM.visibility });

    log('testenv: uploading…');
    const ids = {};
    for (const [role, m] of Object.entries(MEDIA)) ids[role] = await upload(A, path.join(media, m.file), m);
    const items = await waitForIngest(A, Object.values(ids), log);
    const byId = new Map(items.map((i) => [i.id, i]));

    const playlist = await A.call('POST', '/api/playlists', { name: PLAYLIST.name });
    for (const role of PLAYLIST.items) await A.call('POST', `/api/playlists/${playlist.id}/entries`, { itemId: ids[role] });
    for (const role of LIKED.A) await A.call('PUT', `/api/me/likes/${ids[role]}`);
    for (const role of LIKED.B) await B.call('PUT', `/api/me/likes/${ids[role]}`);

    const manifest = {
      version: MANIFEST_VERSION,
      key,
      builtAt: Date.now(),
      buildMs: 0,
      users: {
        A: { username: u.A.username, password: u.A.password, displayName: u.A.displayName, id: meA.id, admin: true },
        B: { username: u.B.username, password: u.B.password, displayName: u.B.displayName, id: meB.id, admin: false },
      },
      guests: { enabled: true, canVote: true, canRequestLibrary: true, canUpload: true },
      room: { id: room.id, slug: room.slug, name: room.name },
      playlist: { id: playlist.id, name: PLAYLIST.name, items: PLAYLIST.items },
      liked: LIKED,
      items: Object.fromEntries(Object.entries(ids).map(([role, id]) => {
        const it = byId.get(id);
        const out = { id, title: it.title, mediaType: it.mediaType, kind: it.kind, durationMs: it.durationMs };
        if (it.artist) out.artist = it.artist;
        if (it.show) out.show = it.show;
        return [role, out];
      })),
    };
    for (const [role, type] of Object.entries(ITEM_ROLES)) {
      if (manifest.items[role]?.mediaType !== type) {
        throw new Error(`testenv: ${role} came out as ${manifest.items[role]?.mediaType}, expected ${type}`);
      }
    }
    manifest.buildMs = Date.now() - t0;
    checkManifest(manifest);
    return manifest;
  } finally {
    await server.stop();
    fs.rmSync(media, { recursive: true, force: true });
  }
}

/** The stored manifest, or null when there is none (or it can't be read). */
export function readManifest() {
  try {
    return JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
  } catch {
    return null;
  }
}

async function withLock(fn, log) {
  const deadline = Date.now() + 20 * 60 * 1000;
  let said = false;
  for (;;) {
    try {
      fs.mkdirSync(LOCK);
      fs.writeFileSync(path.join(LOCK, 'pid'), String(process.pid));
      break;
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      // A lock whose owner is gone is stale.
      let pid = NaN;
      try { pid = Number(fs.readFileSync(path.join(LOCK, 'pid'), 'utf8')); } catch { /* being written */ }
      if (Number.isInteger(pid) && pid > 0) {
        try { process.kill(pid, 0); } catch { fs.rmSync(LOCK, { recursive: true, force: true }); continue; }
      }
      if (!said) { log('testenv: waiting for another fixture build…'); said = true; }
      if (Date.now() > deadline) throw new Error(`testenv: ${LOCK} is held too long; remove it if no build runs`);
      await sleep(500);
    }
  }
  try {
    return await fn();
  } finally {
    fs.rmSync(LOCK, { recursive: true, force: true });
  }
}

/**
 * The fixture's manifest, building the fixture first when it is missing, its key differs, or
 * `rebuild` is set. Safe to call from several processes at once (one builds, the others wait).
 */
export async function ensureFixture({ rebuild = false, log = console.log } = {}) {
  const key = computeKey(REPO_ROOT);
  const fresh = () => {
    const m = readManifest();
    return m && m.key === key && fs.existsSync(DATA) ? m : null;
  };
  if (!rebuild && fresh()) return fresh();
  return withLock(async () => {
    if (!rebuild && fresh()) return fresh(); // built while we waited
    ensureJar(log);
    const old = readManifest();
    log(old ? `testenv: rebuilding the fixture (${rebuild ? '--rebuild' : 'key changed'})` : 'testenv: building the fixture');
    // Under the lock, any other build or old dir is a leftover of an interrupted build.
    const parent = path.dirname(FIXTURE_DIR);
    for (const f of fs.readdirSync(parent)) {
      if (/^\.testenv\.(build|old)-\d+$/.test(f)) fs.rmSync(path.join(parent, f), { recursive: true, force: true });
    }
    const dir = `${FIXTURE_DIR}.build-${process.pid}`;
    fs.mkdirSync(dir, { recursive: true });
    try {
      const manifest = await build(dir, key, log);
      fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
      const trash = `${FIXTURE_DIR}.old-${process.pid}`;
      if (fs.existsSync(FIXTURE_DIR)) fs.renameSync(FIXTURE_DIR, trash);
      fs.renameSync(dir, FIXTURE_DIR);
      fs.rmSync(trash, { recursive: true, force: true });
      log(`testenv: fixture built in ${(manifest.buildMs / 1000).toFixed(1)} s`);
      return manifest;
    } catch (e) {
      log(`testenv: the build failed; its files are in ${dir}`);
      throw e;
    }
  }, log);
}

if (process.argv[1] && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  for (const a of args) {
    if (!['--rebuild', '--print-key'].includes(a)) {
      console.error('usage: node tools/testenv/fixture.mjs [--rebuild] [--print-key]');
      process.exit(2);
    }
  }
  if (args.includes('--print-key')) {
    console.log(computeKey(REPO_ROOT));
  } else {
    const m = await ensureFixture({ rebuild: args.includes('--rebuild') });
    console.log(`testenv: ${MANIFEST} (key ${m.key.slice(0, 12)}…)`);
  }
}
