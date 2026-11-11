// Unit tests for web/src/fonts.ts (compiled), run with Node's test runner by FontsJsTest. The
// module path comes in FONTS_JS.
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const F = await import(pathToFileURL(process.env.FONTS_JS).href);

const info = (over = {}) => ({
  cardFont: "bundled", autoDownload: true, cjkVariant: "jp",
  fonts: [
    { id: "thai", name: "Noto Sans Thai", scripts: ["THAI"], size: 37780, status: "installed" },
    { id: "cjk-jp", name: "Noto Sans CJK JP", scripts: ["HAN"], size: 4533028, status: "not_downloaded" },
  ],
  totalBytes: 4570808, missingBytes: 4533028,
  choices: { bundled: { value: "bundled", label: "DejaVu Sans (built in)" }, system: ["Liberation Sans"],
    uploads: [{ id: "0123456789abcdef", family: "My Font", size: 1000, value: "upload:0123456789abcdef" }] },
  uncovered: [],
  ...over,
});

test("sizes", () => {
  assert.equal(F.formatSize(37780), "38 kB");
  assert.equal(F.formatSize(4533028), "4.5 MB");
  assert.equal(F.formatSize(10), "1 kB");
});

test("status texts", () => {
  const t = (ms) => "T" + ms;
  assert.equal(F.statusText({ status: "installed" }, t), "Installed");
  assert.equal(F.statusText({ status: "not_downloaded" }, t), "Not downloaded");
  assert.equal(F.statusText({ status: "downloading" }, t), "Downloading…");
  assert.equal(F.statusText({ status: "failed", error: "HTTP 404", retryAt: 5 }, t), "Failed: HTTP 404 (retrying at T5)");
  assert.equal(F.statusText({ status: "failed", error: "bad" }, t), "Failed: bad");
});

test("card font choices and the setting value", () => {
  const c = F.cardFontChoices(info());
  assert.deepEqual(c.map((x) => x.value), ["bundled", "upload:0123456789abcdef", "system:Liberation Sans"]);
  assert.equal(F.cardFontSetting("bundled"), null);
  assert.equal(F.cardFontSetting("system:Liberation Sans"), "system:Liberation Sans");
  const gone = F.cardFontChoices(info({ cardFont: "upload:ffffffffffffffff" }));
  assert.match(gone[gone.length - 1].label, /not available/);
});

test("download all asks with the size, and busy follows the downloads", () => {
  assert.equal(F.downloadAllText(info()), "Download 1 font (4.5 MB)?");
  assert.equal(F.busy(info()), false);
  assert.equal(F.busy(info({ fonts: [{ id: "x", status: "queued" }] })), true);
  assert.deepEqual(F.CJK_VARIANTS.map((v) => v.value), ["jp", "kr", "sc", "tc"]);
});
