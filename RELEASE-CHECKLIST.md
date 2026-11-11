# VRChat release checklist

Manual checks to run in VRChat before every release (`docs/milestones.md#testing-strategy` on the
`plan` branch). The automated tests cover the packager, timestamps, HLS playlists and permissions;
this list covers what only a real VRChat client, a real RTMP service and a real phone can show.

Copy the result table at the end into the release notes (or the release's issue) and fill it in.
A release ships only when every row is a pass, or a failure is understood and written down.

## Setup

- **Server:** the release build (Docker image or jar) behind HTTPS, deployed as in
  [DEPLOYMENT.md](DEPLOYMENT.md): public `https://` `http.publicBaseUrl`, `proxy_buffering off`
  for `/live/`, WebSocket on `/ws`. Note the version, the commit and the ffmpeg version
  (`ffmpeg -version`) in the result table.
- **Server config:** `outputs.rtmp.enabled`, `outputs.ts.enabled`, `outputs.hls.enabled` and
  `overlay.enabled` on. For the music-video overlay, the room owner needs a role with
  `output.overlayVideo`.
- **Room:** a **public** room with a short slug (e.g. `release-test`), and a second, **private**
  room for the token checks. Turn on, under Manage room → Settings: the QR overlay (default corner
  and size), and leave auto turn-off on with its default 10 minutes.
- **Queue material:** at least two music videos (one with **bright, mostly white** scenes, one
  dark), two audio-only items (one with cover art, one without), all `ready` in the library. One
  YouTube import is fine as a music video.
- **RTMP service:** a VRCDN stream (or another RTMP service), with its ingest URL (prefer
  `rtmps://`), stream key and viewer URL. Save the viewer URL as the room's RTMP viewer URL.
- **Clients:**
  - **PC:** VRChat on Windows (desktop or VR), with **Allow Untrusted URLs** on.
  - **Quest:** VRChat on a Quest (Android), with **Allow Untrusted URLs** on.
  - A world with an **AVPro-based video player that plays live streams** (e.g. ProTV or
    USharpVideo in stream mode), in an **Invite** or **Friends** instance, so the world's
    "Video Player Allowed Domains" doesn't get in the way of the `.ts`/HLS checks. (One extra
    check below uses a public instance.)
  - A **phone** with a camera QR scanner (one Android, one iPhone if at hand).
  - A desktop browser (Chrome or Firefox) logged in as the room owner, to drive the room.

Run every section on **PC first, then Quest**. Where a row says "PC + Quest", each client gets its
own result.

## 1. Outputs come up

Turn on RTMP, `.ts` and HLS on the public room (Stream outputs panel on the room page). Queue one
music video and one audio item and press play.

- [ ] **RTMP status** goes to *connected* within a few seconds; the RTMP service's dashboard shows
      the stream live (720p30, H.264 + AAC).
- [ ] **`.ts` URL** (`https://<host>/live/<slug>.ts`) plays in VLC on the desktop (quick sanity
      check before going into VRChat).
- [ ] **HLS URL** (`https://<host>/live/<slug>/index.m3u8`) plays in VLC.

## 2. Each output in VRChat (PC + Quest)

For each of **RTMP viewer URL**, **`.ts` URL** and **HLS URL**: paste it into the world's video
player and let it play through at least one full item and the change to the next.

- [ ] **Starts** within about 10 s of pasting (RTMP: depends on the service; `.ts`: about 2 s
      after the next unit boundary).
- [ ] **Picture and sound** are right: no green or grey frames, no corrupted blocks, no
      A/V drift over a whole item.
- [ ] **Item change** (video → video, video → audio card, audio card → video) is seamless: no
      black flash, no stall, no player reset, no audio pop or gap beyond the item's own silence.
- [ ] **Audio-only item** plays as its card (cover art, or the generated card without one) with
      sound, and the card stays still (no flicker, no smear).
- [ ] **Runs for 15 minutes** without a stall, a rebuffer or the player reconnecting (glance at
      the server log for `.ts` reconnects).
- [ ] **Two clients** on `.ts` in the same instance hear the same moment (within about a second);
      on HLS a few seconds apart is expected (documented in `docs/user-guide.md`).
- [ ] **PC only:** VRChat's bundled yt-dlp probes the URL first (HEAD or a short GET); playback
      still starts promptly, on every URL. (If only PC fails to start, suspect this probe.)
- [ ] **Quest only:** the URL plays at all (Quest needs HTTPS and a codec it can decode; a failure
      here usually means the certificate chain or the proxy, not the stream).

Additionally, once per release:

- [ ] **Public instance:** in a public or group-public instance of a world that does **not** list
      the server's domain, the `.ts` URL is refused while the RTMP viewer URL plays (confirms the
      allowlist notes in `DEPLOYMENT.md` and the user guide are still right).
- [ ] **Private room token:** the private room's `.ts` URL plays; after **Rotate URL** the playing
      client stops within a few seconds and the old URL no longer plays; the new URL plays.
- [ ] **Slug change:** renaming the public room's link name ends the playing `.ts` client; the
      new URL from the panel plays.

## 3. QR overlay

With the overlay on (default corner `br`, default size), in VRChat on **PC + Quest**:

- [ ] The QR code and the "Requests at …" text show on **cards** (audio items, Paused, Idle).
- [ ] With the owner holding `output.overlayVideo`, they also show on **music videos**; without
      it, music videos play clean and cards still show it.
- [ ] **Scan from the phone** at a normal viewing distance (standing in front of the in-world
      screen, as a player would), on:
  - [ ] a **dark** scene / dark card,
  - [ ] a **bright, mostly white** scene (the bright music video, or a white cover card),
  - [ ] a busy, high-motion scene.
- [ ] The scan opens `https://<host>/r/<slug>`, which lands on the room page in **remote mode**
      ("I'm listening through the stream").
- [ ] The **text** is readable in-world and the URL in it is the same short URL.
- [ ] Change the corner (e.g. to `tl`) and the size in Manage room: the next item (not the
      current one) shows the new placement, and it still scans.

## 4. Join while playing (PC + Quest)

With a music video halfway through:

- [ ] A **new client** pasting the `.ts` URL starts at the live edge, in sync with the clients
      already watching (default `join=next`), with no picture garbage at the start.
- [ ] Same on **HLS** and on the **RTMP viewer URL** (starts at the live edge; a few seconds of
      offset on HLS is expected).
- [ ] A client that **leaves and rejoins** the world picks the stream up again the same way.

## 5. Skip

From the room page (owner), while VRChat clients play the `.ts` and RTMP outputs:

- [ ] **Skip** to the next item: the stream shows the next item **from 0:00** within about
      2–4 s plus the player's buffer, with no glitch, no frozen frame and no audio pop.
- [ ] **Skip several times quickly** (3 skips within 5 s): the stream ends up on the right item,
      still from 0:00, and never stalls or reconnects.
- [ ] **Seek** within a music video: the stream jumps there cleanly.
- [ ] **Previous** (back to the start of the item): clean, from 0:00.
- [ ] **Remote mode** on the phone (from the QR scan): the now-playing panel changes about when the
      stream does; adjust with −0.5 s / +0.5 s if needed and note the delay that matched per output
      (compare with the defaults: RTMP 5 s, `.ts` 2 s, HLS 8 s).

## 6. Pause

- [ ] **Pause:** the stream switches to the **Paused** card within a few seconds, with silence,
      and keeps running (the player doesn't stop or reconnect).
- [ ] **Stay paused 5 minutes:** no reconnect, no drift; RTMP stays *connected*.
- [ ] **Resume:** the item continues from where it paused, cleanly, in sync between clients.

## 7. Going idle

- [ ] **Let the queue run out:** after the last item the stream shows the room's **Idle** card
      (room name, QR overlay), silent, and keeps running.
- [ ] **Queue an item while idle and play:** it starts from 0:00 on the stream without the player
      reconnecting.
- [ ] **Transitions** (if the room uses `gapMs` / a transition card): the gap shows the next
      item's card or a blank card for the set time, then the item starts from 0:00.
- [ ] **Auto turn-off:** idle for the auto-off limit (set it to 1 minute for this check): every
      output turns off, RTMP push stops, VRChat players stop; the outputs panel says why. Turning
      them on again works and the same URLs play.
- [ ] **Packager restart:** with no listeners and no RTMP push, the program output stops after
      60 s; the next `.ts` request starts it again and the client plays from the live edge.

## 8. Wrap-up

- [ ] The server log has no exceptions, no avcC mismatch warnings and no full stream tokens or
      RTMP keys (search for the private room's token and the stream key).
- [ ] Reset any settings changed for the test (auto-off minutes, overlay corner) and turn the RTMP
      push off, or the service keeps receiving the stream.

## Result table

| Section | PC | Quest | Notes |
|---|---|---|---|
| 1. Outputs come up | n/a | n/a | |
| 2. RTMP in VRChat | | | |
| 2. `.ts` in VRChat | | | |
| 2. HLS in VRChat | | | |
| 2. Public instance / token / slug | | | |
| 3. QR overlay (dark / bright / busy) | | | |
| 4. Join while playing | | | |
| 5. Skip / seek / previous / remote delay | | | |
| 6. Pause | | | |
| 7. Going idle / auto turn-off | | | |
| 8. Log check | n/a | n/a | |

Release: ____ · Commit: ____ · ffmpeg: ____ · RTMP service: ____ · World / player: ____ ·
Tested by: ____ · Date: ____
