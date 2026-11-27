// The fixture manifest (.testenv/manifest.json): what the fixture holds, so smokes read ids and
// passwords from here instead of hard-coding them.

export const MANIFEST_VERSION = 1;

/** Item roles and the media type each one has. Extending the fixture adds a role here. */
export const ITEM_ROLES = {
  audioCover: 'audio',      // audio with embedded cover art (MP3 with an attached picture)
  audioNoCover: 'audio',    // audio without cover art (AAC in M4A)
  mvLandscape: 'music_video', // 16:9 music video
  mvPortrait: 'music_video',  // 9:16 music video
  movie: 'movie',
  showEpisode: 'show',      // show "Test Show", season 1, episode 2
  video: 'video',
  uncategorized: 'uncategorized',
  mp3Headerless: 'audio',   // VBR MP3 without a Xing/Info header, silent first second
};

/**
 * Throws an Error naming every problem when `m` isn't a well-formed manifest; returns `m`.
 *
 * Shape: {version, key, builtAt, buildMs, base?, users: {A, B: {username, password, id, admin}},
 * guests: {enabled}, room: {id, slug, name}, playlist: {id, name, items: [role]},
 * liked: {A: [role], B: [role]}, items: {<role>: {id, title, artist?, mediaType, kind}}}.
 */
export function checkManifest(m) {
  const errors = [];
  const str = (v, where) => { if (typeof v !== 'string' || v === '') errors.push(`${where}: expected a non-empty string`); };
  if (!m || typeof m !== 'object') throw new Error('manifest: not an object');
  if (m.version !== MANIFEST_VERSION) errors.push(`version: expected ${MANIFEST_VERSION}`);
  str(m.key, 'key');
  if (!/^[0-9a-f]{64}$/.test(m.key ?? '')) errors.push('key: expected 64 hex digits');
  if (!Number.isFinite(m.builtAt)) errors.push('builtAt: expected epoch ms');
  for (const who of ['A', 'B']) {
    const u = m.users?.[who];
    if (!u) { errors.push(`users.${who}: missing`); continue; }
    str(u.username, `users.${who}.username`);
    str(u.password, `users.${who}.password`);
    str(u.id, `users.${who}.id`);
    if (typeof u.admin !== 'boolean') errors.push(`users.${who}.admin: expected a boolean`);
  }
  if (m.users?.A && m.users.A.admin !== true) errors.push('users.A.admin: A is the admin');
  if (m.guests?.enabled !== true) errors.push('guests.enabled: expected true');
  str(m.room?.id, 'room.id');
  str(m.room?.slug, 'room.slug');
  str(m.room?.name, 'room.name');
  str(m.playlist?.id, 'playlist.id');
  str(m.playlist?.name, 'playlist.name');
  const roles = Object.keys(ITEM_ROLES);
  const roleList = (v, where) => {
    if (!Array.isArray(v) || v.length === 0) { errors.push(`${where}: expected a non-empty list of roles`); return; }
    for (const r of v) if (!roles.includes(r)) errors.push(`${where}: unknown role ${r}`);
  };
  roleList(m.playlist?.items, 'playlist.items');
  roleList(m.liked?.A, 'liked.A');
  roleList(m.liked?.B, 'liked.B');
  const ids = new Set();
  for (const [role, type] of Object.entries(ITEM_ROLES)) {
    const it = m.items?.[role];
    if (!it) { errors.push(`items.${role}: missing`); continue; }
    str(it.id, `items.${role}.id`);
    str(it.title, `items.${role}.title`);
    if (it.mediaType !== type) errors.push(`items.${role}.mediaType: expected ${type}`);
    if (it.kind !== 'audio' && it.kind !== 'video') errors.push(`items.${role}.kind: expected audio or video`);
    if (ids.has(it.id)) errors.push(`items.${role}.id: used twice`);
    ids.add(it.id);
  }
  for (const role of Object.keys(m.items ?? {})) {
    if (!roles.includes(role)) errors.push(`items.${role}: not in ITEM_ROLES`);
  }
  if (errors.length) throw new Error('manifest:\n  ' + errors.join('\n  '));
  return m;
}
