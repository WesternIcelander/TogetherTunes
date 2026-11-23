// soundwindow.ts: one window with sound per person (docs/ui.md). Run by ListenJsTest; SOUNDWINDOW_JS
// is the compiled module.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const m = await import(pathToFileURL(process.env.SOUNDWINDOW_JS).href);

test("the holder: unknown until the socket says, anonymous and no window id always here", () => {
  assert.equal(m.initialSoundHolder("abcdefgh"), "unknown");
  assert.equal(m.initialSoundHolder(undefined), "here");
  assert.equal(m.soundHolderOnIdentity("unknown", true), "here");
  assert.equal(m.soundHolderOnIdentity("elsewhere", true), "here", "signed out: no sound messages any more");
  assert.equal(m.soundHolderOnIdentity("unknown", false), "unknown");
  assert.equal(m.soundHolderOnIdentity("elsewhere", false), "elsewhere");
  assert.equal(m.soundHolderOnIdentity("here", false), "here");
  assert.equal(m.soundHolderOnMessage(true), "here");
  assert.equal(m.soundHolderOnMessage(false), "elsewhere");
  assert.equal(m.loadsMedia("here"), true);
  assert.equal(m.loadsMedia("elsewhere"), false);
  assert.equal(m.loadsMedia("unknown"), false);
});

const base = { holder: "here", hasEntry: true, playing: true, locked: false, needsRestore: false };

test("the overlay: Move player here while another window plays, the sound buttons only here", () => {
  assert.deepEqual(m.overlayPlan(base), { shown: false, text: "Click to enable sound" });
  assert.deepEqual(m.overlayPlan({ ...base, locked: true }), { shown: true, text: "Click to enable sound" });
  assert.deepEqual(m.overlayPlan({ ...base, locked: true, playing: false }).shown, false);
  assert.deepEqual(m.overlayPlan({ ...base, needsRestore: true }), { shown: true, text: "Tap to restore sound" });
  assert.deepEqual(m.overlayPlan({ ...base, holder: "elsewhere", playing: false }), { shown: true, text: "Move player here" });
  assert.deepEqual(m.overlayPlan({ ...base, holder: "elsewhere", locked: true, needsRestore: true }),
    { shown: true, text: "Move player here" }, "Move player here wins over the autoplay block");
  assert.equal(m.overlayPlan({ ...base, holder: "elsewhere", hasEntry: false }).shown, false, "nothing to move");
  assert.equal(m.overlayPlan({ ...base, holder: "unknown", locked: true }).shown, false);
});

test("the click: claim only from elsewhere, then restore or unlock", () => {
  assert.deepEqual(m.overlayClickPlan("elsewhere", false), { claim: true, then: "unlock" });
  assert.deepEqual(m.overlayClickPlan("elsewhere", true), { claim: true, then: "restore" });
  assert.deepEqual(m.overlayClickPlan("here", false), { claim: false, then: "unlock" });
  assert.deepEqual(m.overlayClickPlan("here", true), { claim: false, then: "restore" });
});
