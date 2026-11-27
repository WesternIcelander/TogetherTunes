# Test environment

A ready-made server state and a headless-Chrome helper for smoke checks of the web UI, so a step
doesn't set up a server, users and media from scratch. Node built-ins only; run it with the Node
the build downloaded (`web/node/node`) or any Node ≥ 22. Needs ffmpeg on the PATH (for building
the fixture) and Chrome or Chromium (`CHROME=/path/to/chrome` if it isn't in the usual place).

## The fixture

`web/node/node tools/testenv/fixture.mjs` builds `.testenv/` at the repo root (git-ignored, kept
by `mvn clean`): a data dir plus `manifest.json`. It starts the jar (`mvn package -DskipTests`
first if it's missing) on an empty data dir and creates, through the normal APIs:

- **A** `alice` / `alice-test-password` (admin) and **B** `bob` / `bob-test-password`;
- guests allowed (vote, request from the library, upload);
- a public room `testroom` ("Test Room", owner A);
- media generated with ffmpeg's `lavfi` sources (a few seconds each), all `ready`, in A's library:
  `audioCover` (MP3 with cover art), `audioNoCover` (M4A), `mvLandscape` / `mvPortrait` (music
  videos 16:9 and 9:16), `movie`, `showEpisode` ("Test Show" S1E2), `video`, `uncategorized`,
  `mp3Headerless` (VBR MP3 without a Xing header);
- A's playlist "Test Playlist", A's and B's Liked Songs.

Read ids, slugs and passwords from the manifest (`env.manifest.items.movie.id`), never hard-code
them. A build takes about 15 s.

It is built on first use and rebuilt automatically when its **key** changes: a hash of the
builder's files and of the sources ingest's output depends on (`key.mjs`, `ENCODE_FILES`). Schema
changes don't rebuild: the server migrates the copy when it starts. `--rebuild` forces a build,
`--print-key` prints the key. To add fixture content, extend `fixture.mjs` (and `ITEM_ROLES` in
`manifest.mjs` for a new item role); that changes the key, so every fixture rebuilds.

## Smokes

```js
import { start } from '/path/to/repo/tools/testenv/smoke.mjs';

const env = await start({ viewport: { width: 390, height: 844 }, touch: true });
try {
  const page = env.page;
  await env.login(page, 'A');                  // or 'B'; env.guest(page) for a guest
  await page.goto('/songs');
  await page.waitFor('.app-header');           // a selector, or a function run in the page
  await page.tap('#m-tabs [data-m-tab=room]'); // page.click(...) with the mouse
  console.log(await page.rects(['.app-header', '#player-bar']));
  await page.screenshot('/some/dir/songs.png');
  const b = await env.newPage({ viewport: { width: 1400, height: 900 } }); // own cookies
  await env.login(b, 'B');
} finally {
  const { pageErrors, consoleErrors, dialogs } = await env.stop();
}
```

- `start()` copies `.testenv/data` to a fresh temp dir (`cp -c` clone on APFS), starts the server
  on a free port and waits for `200`, then starts headless Chrome with its own profile. Several
  smokes can run at once. Options: `viewport`, `touch`, `autoplay` (media plays without a gesture),
  `headless: false`, `keep` (keep the temp dir with `server.log` and `chrome.log`), `timeoutMs`,
  `rebuild`, `chromeArgs`.
- Page actions: `goto`, `reload`, `click(selector | [x, y], {button, clickCount})`,
  `tap(selector | [x, y], {holdMs})`, `type(selector, text)`, `press(key)`, `eval(fn, ...args)`,
  `waitFor(selector | fn, {timeoutMs, args})`, `screenshot(file)`, `rects(selectors)`,
  `setViewport({width, height, touch})`, `close()`. Each has a timeout (10 s by default) and fails
  with an error naming the action.
- `env.api('A' | 'B')` is a signed-in API client (`call(method, path, body)`), `env.api()` an
  anonymous one.
- **Dialogs** are answered at once: alerts OK, confirms OK (`newPage({confirm: false})` cancels
  them), prompts with the text from `page.answerPrompt(text)` given before the click, else
  cancelled and reported as a page error. Never trigger a `prompt()` without an answer.
- `stop()` kills Chrome and the server and removes the copy; it also happens on SIGINT/SIGTERM and
  when Node exits. It returns the page errors, console errors and dialogs of every page. Missing
  cover art shows up as 404 console errors (the UI shows placeholders).

`web/node/node tools/testenv/example-smoke.mjs [--shots <dir>]` opens `/songs` as A at 390×844
and 1400×900 and prints a few rects.

## Tests

`TestEnvToolsTest` runs `test/testenv.test.mjs` (key computation and manifest shape) with
`node --test`; it starts no server.
