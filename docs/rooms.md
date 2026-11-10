# Rooms

## Room state

Each room has authoritative state that lives in memory, inside the room's single-threaded executor. Every change is persisted and broadcast with an incremented `version`.

```
Room {
  id, slug, name, ownerId, visibility, controlMode, settings
  queue: List<QueueEntry>        // upcoming entries, in play order (plus suggestions in owner mode)
  history: last N played entries
  timeline: { state, entryId | null, anchorServerMs, anchorPositionMs }
  members: online connections (actor: user|guest|anonymous, mode: listen|remote)
  version: long
}
```

`state` is an enum, `PlaybackState { IDLE, PLAYING, PAUSED }`. `IDLE` means `entryId` is null. The enum is meant to be extended (for example with `LIVE`, see [future-ideas.md](future-ideas.md)). Code that switches on it must handle unknown values explicitly, never with an `else` that assumes "playing". Clients treat an unknown state like `PAUSED`, which freezes the position.

**Current position** = `state == PLAYING ? anchorPositionMs + (serverNow − anchorServerMs) : anchorPositionMs`.

Every timeline change (play, pause, seek, skip, advance) **re-anchors** the timeline: `anchorServerMs = now`, `anchorPositionMs = newPosition`. Clients always work the position out from the latest anchor.

**Advancing:** the executor schedules a task for when the current entry ends. That task moves to the next entry, chosen according to the control mode, or sets `state = IDLE` (and `entryId = null`) if the queue is empty. The task is rescheduled on every re-anchor.

**Entries that aren't ready:** an entry whose item is still being ingested (for example a YouTube request that is still downloading) stays in the queue, marked "processing", and is **skipped over** when choosing the next entry. It becomes eligible as soon as its item is ready: every status change of an item (ingest done or failed, a re-ingest starting, a purge) is announced to each room that has it queued, suggested or current, which refreshes the entry for its clients and, if the room is idle, starts the next eligible entry. A failed entry stays in the queue, marked failed, and is skipped; a current entry whose item failed or was purged is skipped.

**Restart:** the timeline is persisted. On startup, a room that was playing resumes from its computed position, or from the start of the entry if the server was down for longer than the entry's remaining time.

## Control modes

| `controlMode` | Who adds to the queue | Order | Who can skip |
|---|---|---|---|
| `owner` | Owner and moderators. Others **suggest**, and the owner accepts or rejects | Manual | Owner and moderators |
| `vote` | Members with request rights | By votes (descending), ties broken by time added. Owner and moderators can pin an entry to the top | Owner and moderators, or the vote-skip threshold |
| `open` | Members with request rights | Manual; anyone with request rights can reorder or remove | Anyone with request rights |

Details (as implemented):

- "Who can skip" also covers `play`, `pause`, `seek` and `previous`.
- Votes and vote-skip exist only in `vote` mode. Pinned entries play first, in the order they were pinned; owner and moderators can unpin.
- Vote-skip needs `ceil(voteSkipThreshold × present identified members)` skip votes (at least 1). Each voter counts once per entry; skip votes reset when the entry changes, and a drop in presence can reach the threshold.
- Changing the control mode keeps the current play order: queue positions are rewritten to it and pins are cleared. Suggestions left from `owner` mode stay, and the owner and moderators can still accept or reject them in any mode. An accepted suggestion joins the end of the queue.

## Room settings (`settings_json`)

| Setting | Default | Notes |
|---|---|---|
| `requestScope` | `owner` | `owner` \| `owner+self` \| `none` (see [libraries.md](libraries.md#visibility)) |
| `requestsPerActor` | 3 | Max queued entries per user or guest at a time; owner and moderators exempt |
| `allowDownvotes` | false | Vote mode only |
| `voteSkipThreshold` | 0.5 | Fraction of present identified members (listen + remote), or `off` |
| `noRepeatWindow` | 10 | Refuse an item that was played in the last N entries |
| `transitionCard`, `gapMs` | off, 0 | |
| `guests.*` | see below | |
| `outputs.rtmp`, `outputs.ts`, `outputs.hls` | off | Each one available only if the server enables it |
| `overlay.*` | off | See [overlay.md](overlay.md) |
| `estimatedDelayMs` | per output | Used by remote mode; the owner can override it |

As implemented (`RoomSettings`): on the wire and in storage, dotted names are nested objects (`{"guests": {"canVote": true}}`). The room object (`GET /api/rooms…`) carries every setting with its value; `settings_json` stores only the values that differ from the defaults, so a changed default reaches rooms that never set it. Stored JSON is read leniently (unknown or invalid values fall back to the default). Only the owner changes settings, with `PATCH /api/rooms/{id} {"settings": {…}}`, a merge patch: nested objects change only what they name, `null` resets a setting or a whole group; strict (`400 unknown_setting` / `invalid_setting` / `invalid_settings`), all or nothing; the audit entry names the changed settings, not their values. The complete list:

| Setting | Default | Values |
|---|---|---|
| `requestScope` | `owner` | `owner` \| `owner+self` \| `none` |
| `requestsPerActor` | 3 | 1–1000 |
| `allowDownvotes` | false | |
| `voteSkipThreshold` | 0.5 | (0, 1], or `"off"` |
| `noRepeatWindow` | 10 | 0 (off)–1000 |
| `transitionCard` | false | |
| `gapMs` | 0 | 0–10000 |
| `guests.canVote`, `guests.canRequestLibrary` | true | |
| `guests.canUpload`, `guests.canYoutube` | false | |
| `overlay.enabled` | false | |
| `overlay.corner` | `br` | `br` \| `bl` \| `tr` \| `tl` |
| `overlay.heightPct` | 22 | 5–50 (% of the frame height) |
| `overlay.margin` | 3 | 0–20 (% of the frame height) |
| `outputs.rtmp.playbackUrl` | `""` (none) | http(s), rtmp(s) or rtsp(t) URL with a host, at most 2048 characters |
| `outputs.ts.join` | `next` | `next` \| `latest` |
| `estimatedDelayMs.rtmp` / `.ts` / `.hls` | 5000 / 2000 / 8000 | 0–120000 |

Whether an output is on is **not** in `settings_json`: it is the `stream_outputs` row and its `enabled` flag (`/api/rooms/{id}/outputs`), so there is one source of truth. Server switches (`guests.*`, `overlay.enabled`, `outputs.*.enabled`) are not checked when a room setting is saved; the effective value is server AND room, computed where it is used.

Roles in a room: `owner` (exactly one; transferable), `moderator`, `member`, `banned`. Users with `rooms.moderateAny` act as moderators everywhere (even in a room that banned them). Visibility: `public` (listed), `unlisted` (anyone with the link), `private` (invited users only; **no guests**). New rooms default to `unlisted` and `vote`.

Who does what with the room itself (see [api.md](api.md#rooms) for the details): only the **owner** changes its name, slug, visibility and control mode, and hands it over (`PUT …/members/{userId}` with role `owner`; the old owner becomes a moderator). The owner or `rooms.moderateAny` deletes it. Owner and moderators see the member list; the owner gives any role, moderators only make users members or ban them (never touching the owner or other moderators). Nobody changes their own role, but anyone except the owner and banned users can leave. `rooms.owner_id` is the owner; `room_members` mirrors it with an `owner` row.

## Guests in rooms

Guests can join `public` and `unlisted` rooms. Just listening needs no identity. Each guest capability is effective only when the **server** setting and the **room** setting are both on:

| Room setting | Server setting it depends on |
|---|---|
| `guests.canVote` (default on) | `guests.canVote` |
| `guests.canRequestLibrary` (default on) | `guests.canRequestLibrary` |
| `guests.canUpload` (default off) | `guests.canUpload` |
| `guests.canYoutube` (default off) | `guests.canYoutube` |

In the room settings UI, options the server has disabled are shown greyed out, with "disabled by the server admin".

As implemented (web, `web/src/roommanage.ts` + `roomsettings.ts`): the **management view** is its own page, `/rooms/{idOrSlug}/manage`, linked as "Manage room" from the room page for the owner and moderators. Tabs: **Settings** (name, link name, visibility, control mode and every room setting, grouped; the owner saves only what changed, as one `PATCH`; moderators see them read-only; a setting whose server switch is off (`GET /api/rooms/{id}/server-switches`) is greyed out and disabled with "… disabled by the server admin" and is left out of the patch: the guest capabilities by `guests.enabled` / `guests.*`, every `overlay.*` setting by `overlay.enabled`, `outputs.<kind>.*` and `estimatedDelayMs.<kind>` by `outputs.<kind>.enabled`; vote-skip is a percentage, empty = off; "Delete room" for the owner or `rooms.moderateAny`), **Members** (role select as the viewer may give roles, "Make owner" for the owner, Remove / Lift ban, Leave for oneself, add by username with `POST /api/rooms/{id}/members`), **Guest bans** (list with the IP-mode note, and the address for `ip.view` only; Unban) and **Request history** (filter by source and, via "Only theirs", by requester; "Add to my library" per row or for a selection, owner only; "Ban guest" on a guest's request, after `GET …/guest-bans/{guestId}` and a confirmation that repeats the IP-mode warning). Guests are banned from the request history, where moderators see who they are.

- The first action that needs an identity creates the guest (see [auth.md](auth.md#guests)). With custom names allowed, the UI asks for a name first, pre-filled with a generated one.
- Owners and moderators can **ban a guest** from the room (`room_guest_bans`). In IP mode this bans everyone sharing that IP or /64. The UI warns about this, but doesn't show the IP unless the viewer has `ip.view`.
- Guests count towards vote-skip thresholds and presence once they have an identity. Anonymous listeners are counted separately ("+4 anonymous").

As implemented (`RoomGuests`, `RoomPresence`, `PresenceOps`):

- With `guests.enabled` off, or for a guest banned server-wide, no capability is effective. `guests.canYoutube` also needs `ytdlp.enabled`. An anonymous visitor is shown what it would get as a guest.
- A request needs the capability of its path: by item id `canRequestLibrary`, by upload `canUpload`, by link `canYoutube` (`403 guest_library_requests_disabled` / `guest_uploads_disabled` / `guest_youtube_disabled`). "Request rights" (remove own entries, `open`-mode moves and playback) means any of the three. Users get upload/link rights from `library.upload` / `library.youtube`.
- A guest banned from a room is treated like a banned user: `403 banned_from_room`, the room isn't listed for it, and it can't upload or add links for it. Banning removes its connections from presence (the WebSocket layer closes them). Its queue entries and votes stay, as for a banned user; moderators can remove entries.
- Presence counts each identified actor once, however many sockets it has: `listening` if any of them listens, else `remote`. `anonymous` counts sockets. A change of the counts is broadcast with a new `version`; a drop in identified members re-checks the vote-skip threshold. Presence is in memory only (kept when the executor reloads its state, empty after a restart).

## Requests and the owner's request history

A **request** is any attempt to add an item to a room's queue (or to suggest one) by someone other than the owner or a moderator acting in owner mode. Requests come from three sources:

- **Library:** search within `requestScope`.
- **Upload:** the file is ingested into a new item (or deduplicated). The queue entry is "processing" until the item is ready.
- **YouTube:** the link is normalised to a video ID and served from the cache when possible ([library-and-ingest.md](library-and-ingest.md#youtube-caching)).

Each request writes a `room_requests` row. The owner reviews them in the request history and can add items to their library from there ([libraries.md](libraries.md#room-owners-request-history)). Items that nobody adds to a library are deleted by retention after the configured period.

## Outputs

The room owner turns each of the three outputs on or off independently: RTMP push, direct `.ts` and HLS. An output can only be turned on if the server enables it. They are equal features, built from the same packager stream ([program-output.md](program-output.md)).

- **RTMP:** the owner enters the ingest URL and stream key (e.g. from VRCDN). The room page shows push status. The URL that viewers paste into VRChat comes from the RTMP service, so the owner can save it in the room settings (`outputs.rtmp.playbackUrl`), and the room page then shows it.
- **`.ts` and HLS:** the room page shows the tokenised URLs, with copy buttons, and buttons to rotate the token or disable the output.
- The page also notes the VRChat allowlist requirement for self-hosted URLs.

## Clock sync (web and app)

The client estimates `offset = serverClock − clientClock` over the WebSocket:

1. The client sends `{type:"time", t0}`. Use a monotonic clock: `performance.timeOrigin + performance.now()`.
2. The server replies with `{type:"time", t0, ts: serverNowMs}`.
3. When the reply arrives at `t1`: `rtt = t1 − t0`, `offset = ts − (t0 + t1)/2`.
4. Do 8 exchanges on connect, keep the sample with the lowest RTT, and repeat every 30 s. Smooth with an EMA, ignoring samples whose RTT is above 2× the minimum.

As implemented (web, `web/src/clocksync.ts`): the 8 exchanges of a round go one after the other (2 s reply timeout); a round runs on every (re)connect, every 30 s, and when the page becomes visible again. EMA weight 0.3 over the rounds' best samples. The minimum RTT is taken over the last 10 rounds, and a round passes when its best RTT is at most `max(2 × min, min + 5 ms)`. A sample more than 1 s away from the smoothed offset replaces it (the client's clock stepped, e.g. after sleep). Until the first round the client uses its own wall clock.

## Web and app playback

Each connection is in one of two modes, chosen with a toggle and remembered per room in localStorage. Visiting through the QR link (`/r/{slug}`) defaults to remote mode.

### Listen mode

The client plays the media itself, in sync with the timeline. There is a **video / audio-only** toggle:

- Audio items: the **original** file (`originalUrl`) in an `<audio>` element, with `originalGainDb` applied through the element's `GainNode`, when the item has a web-playable original and `canPlayType` agrees. Otherwise `audio.m4a`. The cover art is shown.
- Video items with `presentAsAudio`: `audio.m4a` in an `<audio>` element, with the cover art shown.
- Video items in video mode: `mezz.mp4` in a `<video>` element.
- Video items in audio-only mode: `audio.m4a` only. The video is never requested, so it uses no bandwidth.

Sync loop (every 250 ms, on every state message, and on the elements' `playing` / `seeked` events):

```
target = position(timeline, serverNow = clientNow + offset)
drift  = el.currentTime*1000 − target
|drift| > 750 ms → el.currentTime = (target + lead)/1000          (hard seek, both modes)
rate mode (Chromium, Firefox):
  rate 1 and |drift| > 40 ms → rate 1 ∓ 3 % (∓ 5 % if |drift| > 300 ms), sign against the drift
  nudging → keep that rate until |drift| ≤ 10 ms or the drift changed sign, then rate 1
seek mode (WebKit: Safari and every iOS/iPadOS browser):
  rate always 1; |drift| > 60 ms (80 ms after a seek settled within ±60 ms)
    → seek to target + lead, at most every 2.5 s
  (doubling up to 8 s only while seeks scatter; a seek that misses by the same amount
   every time is a wrong lead, which the lead learns, so it keeps the 2.5 s)
no correction while a seek's landing settles (~1.4 s after `seeked`), except a hard seek
```

Why two modes (found in the first manual test, 2026-11-10): in Safari the pitch rises with `playbackRate` although `preservesPitch`/`webkitPreservesPitch` is set (the elements go through `createMediaElementSource`), and every `playbackRate` change briefly stalls the video. So WebKit never nudges the rate, and the rate mode changes it only twice per correction instead of every tick. The room page's `?sync=rate|seek` overrides the choice for testing.

**Seek lead:** a seek takes time, so a playing element lands late. In WebKit the seek itself is quick (`seeked` after 60–100 ms for buffered data, reporting the new position) but the media clock then **stands still for another ~300 ms** (pipeline refill) before playing on at normal speed; found in the diagnostics of the second manual test (2026-11-10: iPad Pro iPadOS 26.1 and iPhone X iOS 15.1 both read ~0 ms drift at `seeked` and settled 280–340 ms behind). So a landing is judged on the **settled** drift (`SeekLanding`): drift samples from 0.8 s after `seeked` on, at least 200 ms apart; when the last three lie within 20 ms, their median (stable); if they haven't come to rest 3 s after `seeked`, the median of the last three (not stable, counts as scatter). Each element learns how far ahead to aim from these (first sample as is, then halfway towards each new one, limited to −200…2000 ms), but only from seeks into **buffered** data (`el.buffered`): a seek to unbuffered data (start, an owner seek far away) also waits for the download (1.4 s seen on the iPhone) and would spoil the lead; before the first real sample its settled drift is only a first guess, which the first real sample replaces. A drift above 750 ms at or after `seeked` is a lost seek (iOS dropped it), not a latency. A landing that settles more than 150 ms off is followed at once by another seek with the corrected lead (at most two in a row), so a fresh element converges in two seeks. Scatter: the delay of a seek is lead used − settled drift; when the last three delays spread more than 100 ms (or the drift didn't come to rest), the seek-mode cooldown doubles, up to 8 s. The 80 ms threshold once converged: seven identical correction seeks settled within 33 ms (iPad) / 39 ms (iPhone) of each other, so a seek is good to about ±20 ms and can't reliably improve a drift below about twice the spread; rate-1 playback then held its offset to ±1 ms for 30 s, so a converged element seeks only every few minutes at most (each WebKit seek is a ~300 ms audible stall).

**Settled:** drift is judged only while the element plays, isn't seeking and has `readyState ≥ HAVE_FUTURE_DATA` *or advances* (iOS may report less while playing; before this, such an element was never corrected). A seek the browser dropped (iOS resets a seek made before playback starts) simply shows as drift and is done again; a seek still pending after 5 s reloads the element (a fresh media pipeline, then the usual start seek and `play()`; re-seeking didn't help on the iPod touch 7, report `f552z8`), and the "Click to listen" gesture doesn't seek an element whose start seek is still pending. **End of an item:** no seek (correction, hard, resume) and no start once the seek's aim (target + lead) is within the last 1 s of the file; the element plays out and the next entry starts on time (a seek aimed past the end used to be clamped to the last 50 ms, end the element at once, and be redone every tick). The loop catches its own errors (recorded for diagnostics) and keeps ticking.

If an original keeps needing hard seeks (drift still above 750 ms after two hard seeks within 10 s; typical for VBR MP3s without a seek table), the client switches that item to `audio.m4a` for the rest of the session.

Set `preservesPitch = true`. Preload the next entry about 10 s before the current one ends (in the mode the client will use for it), and show a "Click to listen" overlay until the browser allows autoplay.

As implemented (web, `web/src/listen.ts` + `web/src/listensync.ts`): a fixed pool of two `<audio>` and two `<video playsinline>` elements is reused for every entry (one plays, one holds the preloaded next entry), so the "Click to listen" click can touch every element once (iOS asks per element). Each element is routed through its own `GainNode` (`createMediaElementSource`, once per element): the playing one gets `10^(originalGainDb/20)` for an original and 1 otherwise, the preloaded one 0; without Web Audio the element's `volume` is used (capped at 1). The overlay shows while the timeline is playing and the `AudioContext` isn't `running` or `play()` was refused (`NotAllowedError`); clicking resumes the context and plays. The original is used only if the item view carries `originalUrl` and `canPlayType(originalType)` isn't `""`; an original whose element fires `error`, or that needs a third hard seek within 10 s of two others (counted only while the element is playing and has data, reset on every re-anchor and reload), is played from `audio.m4a` for the rest of the page's life. Drift is judged only while the element plays with `readyState ≥ HAVE_FUTURE_DATA` and isn't seeking; a paused or not-yet-started element is first moved to the target without counting as a hard seek; when the timeline is paused the element is paused and kept within 100 ms of the position. The next entry (first `ready` queue entry in play order) is preloaded once ≤ 10 s remain and kept while it stays next. Video items without `presentAsAudio` play `mezz.mp4` in the visible `<video>`, or `audio.m4a` in audio-only mode (below). `window.togetherTunesPlayer.debug()` shows source, gain, rate, drift, sync mode, the element's state, seek lead and seek counts, and preloads for manual checks. **Diagnostics without a console:** the page keeps rings in memory from load on (`web/src/diagnostics.ts`: the last 200 sync-loop decisions with drift, action, rate, `readyState`, `seeking`, `paused`; the last 150 player events such as seeks and their landings, rate changes, `play()` failures and media events; the last 50 page errors, `error` and `unhandledrejection`, and errors the loop caught); "Send diagnostics" in listen mode posts them with the user agent, `debug()` and the clock state to `POST /api/diagnostics`, which logs one line `Client diagnostics from <user|guest id|anonymous>: {json}` (256 KiB limit, rate-limited per IP, no IPs; URL parameters like `token=` are masked). The page shows a short `ref` to find the line.

**Audio health and the Web Audio fallback** (`web/src/audiohealth.ts`; from the iPod touch 7 report `dfh3l7`, where the sound stuttered and then went mute while sync stayed perfect; this matches WebKit bug 221334, "Audio passed through WebAudio is delayed and glitchy on Safari", unfixed, on A10/A11-class iPhones): each element's `GainNode` feeds an `AnalyserNode` (in the chain to the destination), read every 40 ms while the element plays (2048-sample windows, so they overlap). Once a second a health sample goes into a fourth ring (300 samples = 5 minutes, compact keys): per element `out: [peak dB, RMS dB, windows, gaps, silent windows]` (null dB = exact silence; a gap is a run of ≥ 128 exact zeros inside a window that has signal), the context state and `ck` (audio-clock ms per 1000 ms of wall clock), the active element's position, drift, `readyState`, gain, `wk` (per-second deltas of `webkitAudioDecodedByteCount`, `webkitVideoDecodedByteCount`, `webkitDecodedFrameCount`, `webkitDroppedFrameCount`) and `vq` (`getVideoPlaybackQuality` totals). Events also record `visibilitychange`/`pagehide`/`pageshow`, every `load`/`clear`/`reload`/`handoff` per element, and context resumes; `debug()` and the `player` event show `sampleRate`, `baseLatency` and `outputLatency`. **Mute detection:** an element that should be heard (playing and advancing, not seeking or muted, gain > 0, context running) whose meter saw only exact zeros for 10 s in a row (seconds with fewer than 15 windows don't count, nor do seconds where the element decoded no audio, where the engine counts that), after it had been heard once, trips it: the overlay shows **"Tap to restore sound"**, and that tap empties and drops the four elements, closes the context and builds a fresh pool without Web Audio inside the gesture (the current entry restarts with the usual start seek), for the rest of the page's life (`debug().webAudioOff: "mute"`). **`?webaudio=0`** on the room URL starts that way (A/B test; `webAudioOff: "url"`). Without Web Audio there is no gain: originals play without `originalGainDb`, and where `volume` is read-only (iOS) the audio-only switch can't crossfade. **Stuck seek on a routed element:** the entry moves to the other element of its kind (a preload there is dropped and redone later) instead of `load()` on the stuck one; without Web Audio, or during a switch, it reloads as before. **Interruptions:** a context that had been running and reports `suspended`/`interrupted` is asked to resume (at most every 5 s; also when the page becomes visible again).

**Sample-rate mismatch and the "Web Audio" setting** (`WebAudioCheck` and `webAudioPlan` in `audiohealth.ts`; from the iPod touch 7 report `uhmxlu`, where the context reported 44100 Hz but its clock ran 1086–1091 ms per wall second, i.e. the hardware pulled 48 kHz, with 5–11 dropouts a second while sync was perfect): once a second the check looks at `ck` and the playing element's gaps. **Clock:** off by more than 20 ms per 1000 (2 %) in 3 samples in a row while the context runs (samples outside 500–2000, stalls and catch-ups, are ignored) → verdict `clock` with the implied rate (`sampleRate × median ratio`, snapped to a standard rate within 1.5 %). **Gaps:** ≥ 3 gaps a second for 3 s in a row while the element should sound, in seconds where at most 20 % of the windows were exact silence (a click track's sparse clicks aren't dropouts), and only with the clock measured fine → verdict `gaps`. **ok** after 5 clean clock samples. **What happens:** on `clock` (once per page) the player (a) closes the context, makes `new AudioContext({ sampleRate: implied })` (Safari 14.1+; if the option throws, a plain context) and moves playback onto a fresh pool routed through it (an element can only have one `MediaElementAudioSourceNode`); the current entry starts on the new element at the timeline's position, as after a stuck-seek handoff; then it checks again. If that fails (`clock` again, or the new context isn't running 4 s later, since WebKit may want a gesture for it), or on `gaps`, **Auto** (b) rebuilds the pool without Web Audio and remembers it; **On** keeps Web Audio (event `webaudio-kept`). Spare elements (4 per kind) are touched in every "Click to listen" gesture, so iOS lets a pool rebuilt without a gesture play; if it still refuses, the overlay asks for a tap. **Setting:** "Web Audio" next to "Audio only" in listen mode, per browser (`localStorage` `togethertunes.webAudio`: absent = Auto, `on`, `off`); Off shows what it costs (no crossfade on the audio-only switch, no loudness adjustment for originals where the browser can't set the volume). Precedence: `?webaudio=0/1` (testing) > setting > Auto's memory (`togethertunes.webAudioAuto`: `{rate}` after a confirmed rate fix, so the next visit creates the context at that rate at once; `{direct: true}` after (b) or a "Tap to restore sound" under Auto, so the next visit starts direct; forgotten after 30 days or when the setting is changed). The note next to the setting shows "Sound: direct (no volume control)" without Web Audio and "Sound: Web Audio at 48 kHz" after a rate fix. Events: `webaudio-check` (verdict), `rebuild`/`rebuilt`, `rate-fix-start`, `webaudio-kept`, `webaudio-plan`; health samples carry `wa` (the verdict); `debug()` shows `webAudioPlan`, `webAudioCheck`, `rateFix`, `reserve`; the report adds `webAudioSetting` and `webAudioMemory`.

### Audio-only switching

Switching between video and audio-only mode must not interrupt the audio.

**Why not HLS:** in principle HLS supports this, using a demuxed audio rendition plus an audio-only variant, and native Safari can switch between them. But browsers other than Safari play HLS through Media Source Extensions, which can't easily drop the video SourceBuffer mid-playback. hls.js therefore **removes audio-only variants** from a playlist that also contains video variants. A custom MSE player could do it, but it adds a lot of complexity for one feature.

**What to do instead:** the web client already syncs plain files to the timeline, so it switches by crossfading between two synced elements:

1. **Video → audio-only:** create an `<audio>` element for `audio.m4a`, at volume 0, and let the sync loop bring it to the timeline position.
2. When its drift has stayed under 40 ms for 3 consecutive ticks (or after a 3 s timeout), ramp the audio element's volume from 0 to 1 and the video element's from 1 to 0 over 150 ms.
3. Pause the video element and clear its `src` to stop the download. Show the cover art in its place.
4. **Audio-only → video:** the same steps in reverse. The video element starts muted and hidden; when it is synced, show it and crossfade.

Both elements play the same samples from the same loudness-normalised encode, and are aligned to within about 40 ms, so a 150 ms crossfade is inaudible. On iOS, `volume` can't be set from script, so use a Web Audio `GainNode` per element (via `MediaElementAudioSourceNode`) for the ramps.

As implemented (web, `ListenPlayer` + `SwitchGate` in `listensync.ts`): the toggle is an "Audio only" checkbox above the player in listen mode, remembered per browser (`localStorage` `togethertunes.audioOnly`, all rooms). The two elements come from the same pool of 2 `<audio>` + 2 `<video>`. The steady ticks are counted at most every 200 ms (the loop also ticks on bursts of state and load events), and the 3 s timeout crossfades only if the incoming element is actually playing, so a slow or failing load keeps the old element audible instead of leaving a gap. The ramps are `linearRampToValueAtTime` on each element's `GainNode` (stepped `volume` without Web Audio). Right after the ramp the outgoing element is emptied (`src` removed, `load()`), which stops a video download. When nothing can be heard (timeline paused, autoplay blocked or context suspended, outgoing element not playing) the switch happens at once. Toggling back mid-switch swaps the roles. A video stays in view until its fade-out starts and comes into view when its fade-in starts; in audio-only mode the cover art in the now-playing panel stands in for it.

### Remote mode

For users who hear the room in VRChat. The client:

- loads **no media** at all;
- shows now-playing info, the queue, votes and request controls;
- shows a progress bar delayed by `estimatedDelayMs` for the room's active output. Defaults: `.ts` 2 s, HLS 8 s, RTMP 5 s; the owner can override them. Each user can nudge the delay by ±0.5 s, stored locally.

Remote members count as present for vote-skip and presence. The UI shows them separately ("3 listening, 5 in VRChat").

As implemented (web, `web/src/remote.ts` + `room.ts`): the mode is a link-style toggle in the room header ("I'm listening in VRChat (remote mode)" / "Listen here instead"), sent to the server as `{type:"mode"}` and remembered per room (`localStorage` `togethertunes.mode.<roomId>`, explicit choices only). `?mode=` in the URL wins over the remembered mode; toggling removes it from the address (`history.replaceState`) so a reload keeps the choice. Remote mode creates no player at all (no `AudioContext`, no media elements); switching from listen to remote stops and empties the player's elements. Only cover art (`cover.jpg`) is loaded. **Active output:** the most preferred of RTMP, `.ts`, HLS among `state.outputs` that isn't `error`/`disabled`/`stopped`/`off` (all failing: still the most preferred); its delay is the output's own `estimatedDelayMs`, else the room setting `estimatedDelayMs.<kind>`, else the default. With no outputs listed (M3) RTMP is assumed. **Nudge:** −0.5 s / +0.5 s buttons and Reset, stored per room (`togethertunes.remoteNudge.<roomId>`, whole 0.5 s steps); output delay + nudge stays within 0–120 s. **Delayed view:** every timeline change (another entry, anchor or state) is recorded with the server time it happened (its `anchorServerMs` if that is at most 10 s before arrival, else the arrival time); the now-playing panel shows the entry and position that were in effect at `serverNow − delay`, so a skip appears there `delay` later. The state received on joining counts as always in effect. Controls (skip, pause, vote, seek) act on the live room at once.

## Program output and the timeline

The program output ([program-output.md](program-output.md)) **follows** the timeline and is allowed to lag it:

- It applies changes at the next GOP boundary, so there is up to 2 s of extra delay on skip, seek or pause.
- Seeks into a music video start at the keyframe at or before the target. Seeks into an audio item are exact to the AAC frame.
- The program output never feeds back into the timeline.

## Queue operations

These are performed by the executor; each one checks the actor's permissions and the mode's rules.

- `add(itemId | uploadId | youtubeUrl)`: checks `requestsPerActor`, `noRepeatWindow`, the guest capabilities and the request scope. In `owner` mode, a request from anyone else becomes a suggestion. Writes a `room_requests` row.
- `addPlaylist(playlistId, shuffle)`: owner and moderators only.
- `remove(entryId)`: owner and moderators; the requester while the entry is still queued; or anyone with request rights in `open` mode.
- `move(entryId, beforeEntryId)`: `owner` and `open` modes only. `pin(entryId)`: in `vote` mode.
- `vote(entryId, ±1 | 0)`, `voteSkip()`.
- `play()`, `pause()`, `seek(ms)`, `next()`, `previous()`.

Details (as implemented):

- `requestsPerActor` counts the actor's waiting entries (queued and suggested, not the one playing); `noRepeatWindow` refuses an item that started playing in the last N entries, the current one included. The owner and moderators are exempt from both.
- Uploads and YouTube links are turned into items before the entry is made, outside the room's executor; the cheap checks (identity, request rights, `requestsPerActor`) run first, so a refused request starts no import. A YouTube request doesn't add the item to the requester's library.
- `remove` works on queued entries and suggestions; the current entry is skipped with `next` instead.
- `previous`: more than 3 s into the current entry restarts it. Otherwise the last played (or skipped) entry plays again from the start, and the current entry goes back to the front of the queue (in `vote` mode as the top pin). A paused room stays paused; an idle room replays the last entry.

## Broadcasting

On every change, broadcast a full `state` (under about 200 queue entries) or typed deltas, with `version`. A client that sees a gap in `version` sends `{type:"sync"}`.

Actors in messages are `{kind:"user"|"guest", id, name, isGuest}`. They **never** include IP addresses or anything derived from them.
