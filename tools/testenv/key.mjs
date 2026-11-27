// The fixture key: when it differs from the one stored in .testenv/manifest.json, the fixture is
// rebuilt. It hashes the builder's own files and the source files that decide what ingest writes
// (so stored media would be stale after a change there). Schema changes are left out on purpose:
// the server migrates the copied database when it starts.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/** The builder's files (tools/testenv/, relative to the repo root). The smoke helper isn't one. */
export const BUILDER_FILES = [
  'tools/testenv/common.mjs',
  'tools/testenv/fixture.mjs',
  'tools/testenv/key.mjs',
  'tools/testenv/manifest.mjs',
];

/**
 * What ingest's output depends on: the shared x264 argument constant and the colour description
 * (EncodeProfile), the per-preset table (X264Preset), how presets are picked (EncodePresets), the
 * card format and drawing (CardEncoder with CARD_VERSION, CardRenderer), loudness targets
 * (Loudness), still images and covers (StillImage, CoverBars), the pipeline and its verifier
 * (IngestPipeline, IngestVerifier) and the output profile and its encoding (OutputProfile,
 * OutputEncoding: the default 720p30 and bitrate cap the fixture's server runs with). Whole files
 * are hashed, so a comment change rebuilds too: rarely needed, never wrong.
 */
export const ENCODE_FILES = [
  'src/main/java/io/siggi/togethertunes/media/EncodeProfile.java',
  'src/main/java/io/siggi/togethertunes/config/X264Preset.java',
  'src/main/java/io/siggi/togethertunes/config/OutputProfile.java',
  'src/main/java/io/siggi/togethertunes/config/OutputEncoding.java',
  'src/main/java/io/siggi/togethertunes/ingest/EncodePresets.java',
  'src/main/java/io/siggi/togethertunes/ingest/CardEncoder.java',
  'src/main/java/io/siggi/togethertunes/ingest/CardRenderer.java',
  'src/main/java/io/siggi/togethertunes/ingest/Loudness.java',
  'src/main/java/io/siggi/togethertunes/ingest/StillImage.java',
  'src/main/java/io/siggi/togethertunes/ingest/CoverBars.java',
  'src/main/java/io/siggi/togethertunes/ingest/IngestPipeline.java',
  'src/main/java/io/siggi/togethertunes/ingest/IngestVerifier.java',
];

/** Every input of the key, in hashing order. */
export const KEY_FILES = [...BUILDER_FILES, ...ENCODE_FILES];

/**
 * The key over `files` (relative to `root`): SHA-256 over each path and its content. A missing
 * file is an error (a renamed source must be renamed here too, or the key would silently miss it).
 *
 * @returns {string} hex
 */
export function computeKey(root, files = KEY_FILES) {
  const h = crypto.createHash('sha256');
  h.update('testenv-key-v1\0');
  for (const f of files) {
    const p = path.join(root, f);
    if (!fs.existsSync(p)) throw new Error(`testenv key: ${f} is missing (update tools/testenv/key.mjs)`);
    const data = fs.readFileSync(p);
    h.update(f);
    h.update('\0');
    h.update(String(data.length));
    h.update('\0');
    h.update(data);
  }
  return h.digest('hex');
}
