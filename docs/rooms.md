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

**Entries that aren't ready:** an entry whose item is still being ingested (for example a YouTube request that is still downloading) stays in the queue, marked "processing", and is **skipped over** when choosing the next entry. It becomes eligible as soon as its item is ready.

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

- The first action that needs an identity creates the guest (see [auth.md](auth.md#guests)). With custom names allowed, the UI asks for a name first, pre-filled with a generated one.
- Owners and moderators can **ban a guest** from the room (`room_guest_bans`). In IP mode this bans everyone sharing that IP or /64. The UI warns about this, but doesn't show the IP unless the viewer has `ip.view`.
- Guests count towards vote-skip thresholds and presence once they have an identity. Anonymous listeners are counted separately ("+4 anonymous").

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

## Web and app playback

Each connection is in one of two modes, chosen with a toggle and remembered per room in localStorage. Visiting through the QR link (`/r/{slug}`) defaults to remote mode.

### Listen mode

The client plays the media itself, in sync with the timeline. There is a **video / audio-only** toggle:

- Audio items: the **original** file (`originalUrl`) in an `<audio>` element, with `originalGainDb` applied through the element's `GainNode`, when the item has a web-playable original and `canPlayType` agrees. Otherwise `audio.m4a`. The cover art is shown.
- Video items with `presentAsAudio`: `audio.m4a` in an `<audio>` element, with the cover art shown.
- Video items in video mode: `mezz.mp4` in a `<video>` element.
- Video items in audio-only mode: `audio.m4a` only. The video is never requested, so it uses no bandwidth.

Sync loop (every 250 ms, and on every state message):

```
target = position(timeline, serverNow = clientNow + offset)
drift  = el.currentTime*1000 − target
|drift| > 750 ms       → el.currentTime = target/1000      (hard seek)
40 ms < |drift| ≤ 750  → el.playbackRate = clamp(1 − drift/2000, 0.95, 1.05)
|drift| ≤ 40 ms        → el.playbackRate = 1
```

If an original keeps needing hard seeks (drift still above 750 ms after two hard seeks within 10 s; typical for VBR MP3s without a seek table), the client switches that item to `audio.m4a` for the rest of the session.

Set `preservesPitch = true`. Preload the next entry about 10 s before the current one ends (in the mode the client will use for it), and show a "Click to listen" overlay until the browser allows autoplay.

### Audio-only switching

Switching between video and audio-only mode must not interrupt the audio.

**Why not HLS:** in principle HLS supports this, using a demuxed audio rendition plus an audio-only variant, and native Safari can switch between them. But browsers other than Safari play HLS through Media Source Extensions, which can't easily drop the video SourceBuffer mid-playback. hls.js therefore **removes audio-only variants** from a playlist that also contains video variants. A custom MSE player could do it, but it adds a lot of complexity for one feature.

**What to do instead:** the web client already syncs plain files to the timeline, so it switches by crossfading between two synced elements:

1. **Video → audio-only:** create an `<audio>` element for `audio.m4a`, at volume 0, and let the sync loop bring it to the timeline position.
2. When its drift has stayed under 40 ms for 3 consecutive ticks (or after a 3 s timeout), ramp the audio element's volume from 0 to 1 and the video element's from 1 to 0 over 150 ms.
3. Pause the video element and clear its `src` to stop the download. Show the cover art in its place.
4. **Audio-only → video:** the same steps in reverse. The video element starts muted and hidden; when it is synced, show it and crossfade.

Both elements play the same samples from the same loudness-normalised encode, and are aligned to within about 40 ms, so a 150 ms crossfade is inaudible. On iOS, `volume` can't be set from script, so use a Web Audio `GainNode` per element (via `MediaElementAudioSourceNode`) for the ramps.

### Remote mode

For users who hear the room in VRChat. The client:

- loads **no media** at all;
- shows now-playing info, the queue, votes and request controls;
- shows a progress bar delayed by `estimatedDelayMs` for the room's active output. Defaults: `.ts` 2 s, HLS 8 s, RTMP 5 s; the owner can override them. Each user can nudge the delay by ±0.5 s, stored locally.

Remote members count as present for vote-skip and presence. The UI shows them separately ("3 listening, 5 in VRChat").

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

## Broadcasting

On every change, broadcast a full `state` (under about 200 queue entries) or typed deltas, with `version`. A client that sees a gap in `version` sends `{type:"sync"}`.

Actors in messages are `{kind:"user"|"guest", id, name, isGuest}`. They **never** include IP addresses or anything derived from them.
