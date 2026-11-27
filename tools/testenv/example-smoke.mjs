// Example smoke: /songs as A on a phone (390×844, touch) and on a desktop (1400×900), printing the
// rects of a few elements, the visible song titles, and any page errors.
//
//   web/node/node tools/testenv/example-smoke.mjs [--shots <dir>]
import { start } from './smoke.mjs';

const shotsAt = process.argv.indexOf('--shots');
const shots = shotsAt > 0 ? process.argv[shotsAt + 1] : null;
const SELECTORS = ['.app-header', '#app-sidebar', '#app-panel', '#player-bar', '#m-tabs'];

const t0 = Date.now();
const env = await start({ viewport: { width: 390, height: 844 }, touch: true });
let failed = false;
try {
  console.log(`server ${env.base} up in ${Date.now() - t0} ms`);
  const page = env.page;
  await env.login(page, 'A');
  for (const [label, vp] of [['phone', { width: 390, height: 844, touch: true }], ['desktop', { width: 1400, height: 900 }]]) {
    await page.setViewport(vp);
    await page.goto('/songs');
    const title = env.manifest.items.audioCover.title;
    await page.waitFor((t) => document.body.textContent.includes(t), { args: [title] });
    console.log(label, JSON.stringify(await page.rects(SELECTORS)));
    if (shots) console.log('  ', await page.screenshot(`${shots}/songs-${label}.png`));
  }
} catch (e) {
  failed = true;
  console.error('FAILED:', e.message);
} finally {
  const report = await env.stop();
  console.log('page errors:', JSON.stringify(report.pageErrors));
  console.log('console errors:', JSON.stringify(report.consoleErrors));
  console.log(`done in ${Date.now() - t0} ms`);
}
process.exit(failed ? 1 : 0);
