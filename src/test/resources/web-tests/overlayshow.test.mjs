// Unit tests for web/src/overlayshow.ts (compiled), run with Node's test runner by OverlayShowJsTest.
// The module path comes in OVERLAYSHOW_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const O = await import(pathToFileURL(process.env.OVERLAYSHOW_JS).href);

const layout = (mode, enabled = true) => ({
  version: 3, mode, enabled, available: enabled && mode !== "off",
  frame: { width: 1280, height: 720 }, corner: "br", url: "https://t.example/r/x",
  bounds: { x: 718, y: 510, width: 540, height: 188 },
  qr: { x: 1110, y: 510, width: 12, height: 12, radius: 7, modules: 3, modulePx: 4, quietZone: 4,
    rows: ["101", "011", "000"] },
  text: { value: "Requests at t.example/r/x", font: "DejaVu Sans", bold: true, size: 23.68, x: 723.8,
    baseline: 688, advance: 527.5, backdrop: { x: 718, y: 665, width: 540, height: 33, radius: 7.1 } },
  colors: { backdrop: "rgba(0, 0, 0, 0.6902)", modules: "rgba(255, 255, 255, 1)", text: "rgba(255, 255, 255, 1)" },
});
const idle = { timeline: { state: "idle" } };
const playing = (item, extra = {}) => ({
  timeline: { state: "playing" },
  current: Object.assign({ status: "ready", item }, extra),
});
const song = { kind: "audio" };
const musicVideo = { kind: "video", mediaType: "music_video" };
const movie = { kind: "video", mediaType: "movie" };
const videoAsAudio = { kind: "video", presentAsAudio: true };

test("off, the server's switch off, or no layout: never", () => {
  assert.equal(O.overlayShown(idle, layout("off")), false);
  assert.equal(O.overlayShown(idle, layout("always", false)), false);
  assert.equal(O.overlayShown(idle, undefined), false);
  assert.equal(O.overlayShown(undefined, layout("always")), false);
});

test("the queue-empty card has it in always and idleOnly", () => {
  assert.equal(O.overlayShown(idle, layout("always")), true);
  assert.equal(O.overlayShown(idle, layout("idleOnly")), true);
  assert.equal(O.overlayShown({ timeline: { state: "playing" } }, layout("idleOnly")), true, "no current entry");
});

test("idleOnly: nothing while something plays, is paused or loads", () => {
  const l = layout("idleOnly");
  assert.equal(O.overlayShown(playing(song, { overlay: "off" }), l), false);
  assert.equal(O.overlayShown(playing(musicVideo, { overlay: "video" }), l), false);
  assert.equal(O.overlayShown({ timeline: { state: "paused" }, current: { status: "ready", item: song } }, l), false);
  assert.equal(O.overlayShown(playing(song, { status: "processing" }), l), false);
});

test("always: paused and loading cards have it whatever the entry", () => {
  const l = layout("always");
  assert.equal(O.overlayShown({ timeline: { state: "paused" }, current: { status: "ready", item: movie, overlay: "off" } }, l), true);
  assert.equal(O.overlayShown(playing(movie, { status: "processing", overlay: "off" }), l), true);
});

test("always: a playing entry follows its decision", () => {
  const l = layout("always");
  // Item cards: cards or video.
  assert.equal(O.overlayShown(playing(song, { overlay: "cards" }), l), true);
  assert.equal(O.overlayShown(playing(song, { overlay: "video" }), l), true);
  assert.equal(O.overlayShown(playing(song, { overlay: "off" }), l), false, "recorded off");
  assert.equal(O.overlayShown(playing(videoAsAudio, { overlay: "cards" }), l), true);
  assert.equal(O.overlayShown(playing(musicVideo, { overlay: "cards", audioOnly: true }), l), true, "audio-only entry");
  // Played as video: only with the re-encode (video).
  assert.equal(O.overlayShown(playing(musicVideo, { overlay: "video" }), l), true);
  assert.equal(O.overlayShown(playing(musicVideo, { overlay: "cards" }), l), false);
  // Movies and shows: off.
  assert.equal(O.overlayShown(playing(movie, { overlay: "off" }), l), false);
  // Unknown decision: not shown.
  assert.equal(O.overlayShown(playing(song), l), false);
});

test("layoutKey changes with the slug and the overlay settings only", () => {
  const a = O.layoutKey("r1", "chill", { overlay: { mode: "always", corner: "br" }, guests: { x: 1 } });
  assert.equal(a, O.layoutKey("r1", "chill", { overlay: { mode: "always", corner: "br" }, guests: { x: 2 } }));
  assert.notEqual(a, O.layoutKey("r1", "chill2", { overlay: { mode: "always", corner: "br" } }));
  assert.notEqual(a, O.layoutKey("r1", "chill", { overlay: { mode: "always", corner: "tl" } }));
});

test("frameRect: the card fills a frame-aspect rectangle in the box", () => {
  const f = O.frameRect({ x: 0, y: 0, width: 1600, height: 900 }, { width: 1280, height: 720 });
  assert.deepEqual(f, { x: 0, y: 0, width: 1600, height: 900 });
  const tall = O.frameRect({ x: 0, y: 0, width: 800, height: 900 }, { width: 1280, height: 720 });
  assert.equal(tall.width, 800);
  assert.equal(tall.height, 450);
  assert.equal(tall.y, 225);
});

test("frameRect: a 16:9 video letterboxed in a tall box", () => {
  const f = O.frameRect({ x: 10, y: 20, width: 1000, height: 1000 }, { width: 1280, height: 720 }, { width: 1920, height: 1080 });
  assert.equal(f.x, 10);
  assert.equal(f.width, 1000);
  assert.equal(f.height, 562.5);
  assert.equal(f.y, 20 + (1000 - 562.5) / 2);
});

test("frameRect: a 4:3 video gets the frame around it, as the stream pillarboxes it", () => {
  const f = O.frameRect({ x: 0, y: 0, width: 1920, height: 1080 }, { width: 1280, height: 720 }, { width: 640, height: 480 });
  assert.deepEqual(f, { x: 0, y: 0, width: 1920, height: 1080 });
  // A narrow box: the frame around the video wouldn't fit, so the frame is fitted into the box.
  const n = O.frameRect({ x: 0, y: 0, width: 400, height: 800 }, { width: 1280, height: 720 }, { width: 640, height: 480 });
  assert.equal(n.width, 400);
  assert.equal(n.height, 225);
});

test("modulePath draws each run of 1s as one rectangle", () => {
  assert.equal(O.modulePath(["101", "011", "000"]), "M0 0h1v1h-1zM2 0h1v1h-1zM1 1h2v1h-2z");
  assert.equal(O.modulePath(["000"]), "");
});

test("overlaySvg: frame viewBox, crisp modules, text at the stream's width, escaped", () => {
  const l = layout("always");
  l.text.value = "Requests at a<b>&c";
  const svg = O.overlaySvg(l);
  assert.match(svg, /viewBox="0 0 1280 720"/);
  assert.match(svg, /shape-rendering="crispEdges"/);
  assert.match(svg, /transform="translate\(1110 510\) scale\(4\)"/);
  assert.match(svg, /textLength="527.5"/);
  assert.match(svg, /font-weight="bold"/);
  assert.match(svg, /'DejaVu Sans'/);
  assert.match(svg, /a&lt;b&gt;&amp;c/);
  assert.ok(!svg.includes("<script"));
});
