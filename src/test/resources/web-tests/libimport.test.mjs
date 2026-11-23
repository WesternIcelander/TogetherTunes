// Unit tests for web/src/libimport.ts (compiled): the admin user page's "Import a music library"
// section (what shows per state, the count texts, the progress text, problems, the upload maths).
// Run with Node's test runner by LibImportJsTest; the module path comes in LIBIMPORT_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const L = await import(pathToFileURL(process.env.LIBIMPORT_JS).href);

function counts(over = {}) {
  return {
    tracks: 10, matched: 8, missing: 2, skipped: { protected: 1 }, alreadyInLibrary: 3, videos: 0,
    playlists: { regular: 2, smart: 3, folders: 1 }, unreferencedFiles: 4, toImport: 7, done: 0,
    imported: 0, already: 0, liked: 0, playlistsMade: 0, playlistsUpdated: 0, problems: 3, ...over,
  };
}

function imp(state, over = {}) {
  return {
    id: "i1", userId: "u1", state, source: { uploadId: "x" }, smartPlaylists: false, counts: counts(),
    problems: [{ kind: "missing", reason: "not_found", title: "Song", artist: "Band" }],
    createdAt: 1, updatedAt: 2, ...over,
  };
}

const OFF = { enabled: false, allowedPaths: [] };
const ON = { enabled: true, allowedPaths: ["/srv/exports", "/mnt/music"] };

test("no import yet: the form, the path option only when allowed, with the paths as a hint", () => {
  let v = L.importView({ pathOption: OFF });
  assert.equal(v.phase, "idle");
  assert.equal(v.showForm, true);
  assert.equal(v.showPathOption, false);
  assert.equal(v.pathHint, undefined);
  assert.deepEqual(v.actions, []);
  v = L.importView({ pathOption: ON });
  assert.equal(v.showPathOption, true);
  assert.equal(v.pathHint, "Allowed: /srv/exports, /mnt/music");
});

test("checking: no form, Cancel, the counts with the library count still pending", () => {
  const v = L.importView({ import: imp("checking"), pathOption: ON });
  assert.equal(v.phase, "checking");
  assert.equal(v.showForm, false);
  assert.deepEqual(v.actions, ["cancel"]);
  assert.ok(v.lines.includes("Already in the library: still checking…"));
});

test("ready: the check's counts, then Import / Cancel", () => {
  const v = L.importView({ import: imp("ready"), pathOption: OFF });
  assert.equal(v.phase, "ready");
  assert.equal(v.showForm, false);
  assert.deepEqual(v.actions, ["import", "cancel"]);
  assert.equal(v.showProblems, true);
  assert.deepEqual(v.lines, [
    "Tracks: 10",
    "Matched to a file: 8",
    "Missing (no file for it): 2",
    "Skipped: 1 (1 protected)",
    "Already in the library: 3",
    "To import: 7 files",
    "Playlists: 2 regular, 3 smart, 1 folder (smart ones not included)",
    "Files not in the list: 4",
  ]);
  const smart = L.importView({ import: imp("ready", { smartPlaylists: true, counts: counts({ videos: 2 }) }), pathOption: OFF });
  assert.ok(smart.lines.includes("Playlists: 2 regular, 3 smart, 1 folder (smart ones included)"));
  assert.ok(smart.lines.includes("Videos (imported as uncategorized): 2"));
});

test("skipped by reason, most first; none", () => {
  assert.equal(L.skippedText({ unreadable: 1, protected: 3, suspicious_compression: 1 }),
    "Skipped: 5 (3 protected, 1 suspicious compression, 1 unreadable)");
  assert.equal(L.skippedText({}), "Skipped: 0");
  assert.equal(L.skippedText(undefined), "Skipped: 0");
  assert.equal(L.reasonText("something_new"), "something new");
});

test("importing and paused: progress bar, the counts so far, Cancel import", () => {
  const running = imp("importing", { counts: counts({ done: 3, imported: 2, already: 1 }), startedAt: 5 });
  let v = L.importView({ import: running, pathOption: OFF });
  assert.equal(v.phase, "running");
  assert.deepEqual(v.actions, ["cancelImport"]);
  assert.deepEqual(v.progress, { value: 3, max: 7, text: "Importing: 3 of 7 files" });
  assert.deepEqual(v.lines, ["Imported: 2", "Already there: 1", "Skipped: 1 (1 protected)"]);
  v = L.importView({ import: { ...running, state: "paused" }, pathOption: OFF });
  assert.equal(v.heading, "Import paused");
  assert.equal(v.progress.text, "Paused for disk space: 3 of 7 files. It goes on by itself when there is room.");
  assert.equal(L.progressText(imp("importing", { counts: counts({ toImport: 1, done: 0 }) })), "Importing: 0 of 1 file");
  // nothing to import: the bar still has a maximum
  v = L.importView({ import: imp("importing", { counts: counts({ toImport: 0 }) }), pathOption: OFF });
  assert.equal(v.progress.max, 1);
});

test("finished: the result and the problems, and the form again", () => {
  const done = imp("done", { startedAt: 3, finishedAt: 9,
    counts: counts({ done: 7, imported: 5, already: 1, playlistsMade: 2, liked: 1 }) });
  let v = L.importView({ import: done, pathOption: OFF });
  assert.equal(v.phase, "finished");
  assert.equal(v.showForm, true);
  assert.equal(v.heading, "Last import finished");
  assert.deepEqual(v.lines, ["Imported: 5", "Already there: 1", "Skipped: 1 (1 protected)", "Missing: 2",
    "Playlists made: 2", "Added to Liked Songs: 1"]);
  assert.equal(v.showProblems, true);
  assert.deepEqual(L.resultLines(counts({ playlistsMade: 0, playlistsUpdated: 3 })).slice(4), ["Playlists made: 0, updated: 3"]);
  v = L.importView({ import: imp("failed", { error: "The source can't be read" }), pathOption: OFF });
  assert.equal(v.heading, "Last import failed");
  assert.equal(v.error, "The source can't be read");
  assert.deepEqual(v.lines, [], "a check that failed or was cancelled imported nothing");
  v = L.importView({ import: imp("cancelled"), pathOption: OFF });
  assert.equal(v.heading, "Last import was cancelled");
  assert.equal(v.showProblems, false);
});

test("another user's import: the notice and no form (no Check)", () => {
  const other = { userId: "u2", username: "alice", displayName: "Alice", state: "importing" };
  let v = L.importView({ otherImport: other, pathOption: ON });
  assert.equal(v.phase, "other");
  assert.equal(v.showForm, false);
  assert.equal(v.otherText, "Another import is running (for Alice (alice)).");
  v = L.importView({ import: imp("done", { startedAt: 1 }), otherImport: { ...other, displayName: "alice" }, pathOption: ON });
  assert.equal(v.phase, "finished");
  assert.equal(v.showForm, false);
  assert.equal(v.otherText, "Another import is running (for alice).");
  // the user's own running import wins
  v = L.importView({ import: imp("importing"), otherImport: other, pathOption: ON });
  assert.equal(v.phase, "running");
  assert.equal(v.otherText, undefined);
});

test("polling: often while something runs", () => {
  assert.equal(L.pollDelayMs({ import: imp("importing"), pathOption: OFF }), 3000);
  assert.equal(L.pollDelayMs({ import: imp("checking"), pathOption: OFF }), 3000);
  assert.equal(L.pollDelayMs({ import: imp("paused"), pathOption: OFF }), 3000);
  assert.equal(L.pollDelayMs({ otherImport: { state: "checking" }, pathOption: OFF }), 3000);
  assert.equal(L.pollDelayMs({ import: imp("ready"), pathOption: OFF }), 15000);
  assert.equal(L.pollDelayMs(undefined), 15000);
});

test("problem texts", () => {
  assert.equal(L.problemText({ kind: "missing", reason: "not_found", title: "Song", artist: "Band" }),
    "Missing: “Song” by Band: no file for it in the export");
  assert.equal(L.problemText({ kind: "missing", reason: "ambiguous", title: "Song" }),
    "Missing: “Song”: several files could be it");
  assert.equal(L.problemText({ kind: "skipped", reason: "protected", title: "Song", path: "Media/a.m4p" }),
    "Skipped: “Song” (Media/a.m4p): protected");
  assert.equal(L.problemText({ kind: "skipped", reason: "unreadable", path: "Media/b.mp3" }),
    "Skipped: Media/b.mp3: unreadable");
  assert.equal(L.problemText({ kind: "playlist", reason: "too_many_entries", playlist: "Dir / Evening" }),
    "Playlist “Dir / Evening”: too many entries, only the first ones were kept");
  assert.equal(L.problemText({ kind: "playlist", reason: "too_many_playlists", playlist: "X" }),
    "Playlist “X” wasn't made: the user has too many playlists");
  assert.equal(L.problemText({ kind: "liked", reason: "x", title: "Song" }), "Not added to Liked Songs: “Song”");
  assert.equal(L.moreProblemsText(imp("ready")), "Show all 3 problems");
  assert.equal(L.moreProblemsText(imp("ready", { counts: counts({ problems: 1 }) })), undefined);
});

test("source texts", () => {
  assert.equal(L.sourceText({ uploadId: "abc" }), "From an uploaded zip file");
  assert.equal(L.sourceText({ path: "/srv/exports/a" }), "From the server: /srv/exports/a");
});

test("the zip upload: resume key, chunks, texts", () => {
  const f = { name: "export.zip", size: 20, lastModified: 7 };
  assert.equal(L.uploadKey(f), "tt.libimport.upload.20:7:export.zip");
  assert.notEqual(L.uploadKey({ ...f, lastModified: 8 }), L.uploadKey(f));
  assert.equal(L.nextChunk(0, 8), 0);
  assert.equal(L.nextChunk(16, 8), 2);
  assert.equal(L.nextChunk(17, 8), 2);
  assert.deepEqual(L.chunkRange(2, 8, 20), [16, 20]);
  assert.deepEqual(L.chunkRange(0, 8, 20), [0, 8]);
  assert.equal(L.uploadText("uploading", 1.5e9, 3e9), "Uploading: 1.5 GB of 3.0 GB (50%)");
  assert.equal(L.uploadText("uploading", 0, 820e6), "Uploading: 0 kB of 820 MB (0%)");
  assert.match(L.uploadText("retrying", 1e6, 2e6, "Connection lost"), /Connection lost; trying again/);
  assert.equal(L.uploadText("failed", 0, 1, "Not a zip"), "Upload failed: Not a zip");
  assert.equal(L.bytesText(250e9), "250 GB");
  assert.equal(L.isZipName("Export.ZIP"), true);
  assert.equal(L.isZipName("export.tar"), false);
});
