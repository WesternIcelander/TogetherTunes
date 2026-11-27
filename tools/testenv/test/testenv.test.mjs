// Unit tests of the test environment's key and manifest (run by TestEnvToolsTest, or
// `web/node/node --test tools/testenv/test/`). Nothing here starts a server.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { REPO_ROOT } from '../common.mjs';
import { BUILDER_FILES, ENCODE_FILES, KEY_FILES, computeKey } from '../key.mjs';
import { ITEM_ROLES, MANIFEST_VERSION, checkManifest } from '../manifest.mjs';

function tempRoot(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'tt-key-'));
  for (const [f, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, f)), { recursive: true });
    fs.writeFileSync(path.join(root, f), content);
  }
  return root;
}

test('the key is a SHA-256 that follows every input file', () => {
  const root = tempRoot({ 'a.txt': 'one', 'dir/b.java': 'two' });
  try {
    const files = ['a.txt', 'dir/b.java'];
    const k = computeKey(root, files);
    assert.match(k, /^[0-9a-f]{64}$/);
    assert.equal(computeKey(root, files), k, 'deterministic');
    fs.writeFileSync(path.join(root, 'dir/b.java'), 'two!');
    const k2 = computeKey(root, files);
    assert.notEqual(k2, k, 'content change');
    assert.notEqual(computeKey(root, ['dir/b.java', 'a.txt']), k2, 'order and names count');
    // Moving bytes between files changes it too (lengths are part of the hash).
    const r2 = tempRoot({ 'a.txt': 'onetw', 'dir/b.java': 'o!' });
    try {
      assert.notEqual(computeKey(r2, files), k2);
    } finally {
      fs.rmSync(r2, { recursive: true, force: true });
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('a missing input is an error, not a silently different key', () => {
  const root = tempRoot({ 'a.txt': 'one' });
  try {
    assert.throws(() => computeKey(root, ['a.txt', 'gone.java']), /gone\.java is missing/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('the real inputs exist: the builder and what ingest output depends on', () => {
  assert.deepEqual(KEY_FILES, [...BUILDER_FILES, ...ENCODE_FILES]);
  for (const f of KEY_FILES) assert.ok(fs.existsSync(path.join(REPO_ROOT, f)), f);
  for (const f of ['tools/testenv/fixture.mjs', 'tools/testenv/key.mjs']) assert.ok(BUILDER_FILES.includes(f), f);
  assert.ok(!BUILDER_FILES.includes('tools/testenv/smoke.mjs'), 'changing the helper must not rebuild');
  for (const name of ['EncodeProfile.java', 'X264Preset.java', 'CardEncoder.java', 'IngestPipeline.java']) {
    assert.ok(ENCODE_FILES.some((f) => f.endsWith('/' + name)), name);
  }
  assert.ok(!ENCODE_FILES.some((f) => f.includes('db/migrations')), 'schema changes don\'t rebuild');
  assert.match(computeKey(REPO_ROOT), /^[0-9a-f]{64}$/);
});

function sample() {
  const items = {};
  let n = 0;
  for (const [role, type] of Object.entries(ITEM_ROLES)) {
    items[role] = { id: `id-${n++}`, title: role, mediaType: type, kind: type === 'audio' ? 'audio' : 'video' };
  }
  return {
    version: MANIFEST_VERSION,
    key: 'a'.repeat(64),
    builtAt: 1795800000000,
    buildMs: 12000,
    users: {
      A: { username: 'alice', password: 'pw-a', id: 'u-a', admin: true },
      B: { username: 'bob', password: 'pw-b', id: 'u-b', admin: false },
    },
    guests: { enabled: true },
    room: { id: 'r', slug: 'testroom', name: 'Test Room' },
    playlist: { id: 'p', name: 'Test Playlist', items: ['audioCover', 'mvLandscape'] },
    liked: { A: ['audioCover'], B: ['audioNoCover'] },
    items,
  };
}

test('a complete manifest passes', () => {
  const m = sample();
  assert.equal(checkManifest(m), m);
});

test('every item role has a media type the server knows', () => {
  const types = new Set(['audio', 'music_video', 'movie', 'show', 'video', 'uncategorized']);
  for (const t of Object.values(ITEM_ROLES)) assert.ok(types.has(t), t);
  assert.deepEqual(new Set(Object.values(ITEM_ROLES)), types, 'every type is covered');
});

test('a broken manifest names each problem', () => {
  const cases = [
    [(m) => { m.version = 0; }, /version/],
    [(m) => { m.key = 'short'; }, /key/],
    [(m) => { delete m.users.B; }, /users\.B: missing/],
    [(m) => { m.users.A.admin = false; }, /users\.A\.admin/],
    [(m) => { m.users.B.password = ''; }, /users\.B\.password/],
    [(m) => { m.guests.enabled = false; }, /guests\.enabled/],
    [(m) => { delete m.room.slug; }, /room\.slug/],
    [(m) => { m.playlist.items = ['nope']; }, /playlist\.items: unknown role nope/],
    [(m) => { m.liked.A = []; }, /liked\.A/],
    [(m) => { delete m.items.movie; }, /items\.movie: missing/],
    [(m) => { m.items.showEpisode.mediaType = 'video'; }, /items\.showEpisode\.mediaType: expected show/],
    [(m) => { m.items.video.id = m.items.movie.id; }, /used twice/],
    [(m) => { m.items.extra = { id: 'x', title: 'x', mediaType: 'audio', kind: 'audio' }; }, /items\.extra: not in ITEM_ROLES/],
  ];
  for (const [breakIt, error] of cases) {
    const m = sample();
    breakIt(m);
    assert.throws(() => checkManifest(m), error, error.toString());
  }
});

test('the built fixture\'s manifest (when there is one) has the shape', (t) => {
  const file = path.join(REPO_ROOT, '.testenv', 'manifest.json');
  if (!fs.existsSync(file)) {
    t.skip('no fixture built');
    return;
  }
  checkManifest(JSON.parse(fs.readFileSync(file, 'utf8')));
});
