# User guide: watching a room's stream

External documentation for people using a TogetherTunes server. The software itself never names third-party games or services (see `CLAUDE.md`); how to use the stream in a particular game or with a particular streaming service lives here instead. M7's deployment notes can build on this page.

A room can have up to four **stream outputs** (see [program-output.md](program-output.md)): an **RTMP push** to a streaming service, a **direct MPEG-TS URL** (`.ts`), **HLS** (`.m3u8`) and **MP3 audio** (`.mp3`, for internet-radio players). **Manage Room → Outputs** shows their URLs with copy buttons (the room view has quick **Turn on** / **Copy link** buttons for the owner and moderators). The first three carry the same H.264/AAC program; MP3 carries its sound.

## In VRChat

- **Where the URL goes:** paste the stream URL into the world's video player (any player that plays live streams, e.g. one based on AVPro). In the room view this is the URL under **Watch on the stream** (the RTMP service's viewer URL), or the `.ts` / HLS URL from Manage Room → Outputs.
- **Allowlist:** VRChat by itself only plays URLs from its own allowlist, and a self-hosted server isn't on it. For the `.ts` and HLS URLs, every viewer must turn on **"Allow Untrusted URLs"** in VRChat's settings. In **public and group-public** instances the world must also list the server's domain in its **"Video Player Allowed Domains"**. (In the UI this is the generic note "Some players only play URLs from an allowlist…".)
- **Remote mode:** people who hear the room in VRChat can still open the room (or scan the QR code on the stream, which opens it in requests-only mode) or choose **Requests-only mode** in the player bar's menu. The page then plays nothing itself and shows what plays **through the stream**, delayed to match what the stream is showing; the −0.5 s / +0.5 s buttons fine-tune the delay.

## RTMP service (e.g. VRCDN)

The RTMP output pushes the program to an RTMP ingest, such as VRCDN, which then serves it to viewers from its own servers (often on a domain the game already allows).

1. Create a stream at the service and note its **ingest URL**, **stream key** and the **viewer URL** it gives out.
2. In **Manage room → Outputs**, set up the RTMP push with the ingest URL and the stream key.
3. Under **Manage room → Settings → Outputs**, save the service's viewer URL as the **RTMP viewer URL**. The room view then shows it to everyone under **Watch on the stream**, and that is the URL viewers paste into the world's video player.

**Security note:** a plain `rtmp://` ingest URL sends your stream key unencrypted to the service, so someone on the network path could read it. If the service offers an `rtmps://` ingest address, use that instead. If you think a key has leaked, reset it at the service and paste the new one.

## HLS and staying in sync

HLS players start about three segments behind the live edge, and not all at the same segment, so viewers on HLS (for example in a VRChat world) can be a few seconds apart from each other. When everyone in the same place should hear the same moment, use the `.ts` URL or an RTMP service instead. The room page shows a generic version of this note while HLS is on.

## Internet radio (MP3)

The **MP3 audio** output is a Shoutcast/Icecast-style MP3 stream (128 kb/s by default, 44.1 kHz stereo) that any internet-radio player can play: VLC, foobar2000, Winamp, a hardware radio, or a game's radio. The station name is the room's name, and players that show the "now playing" title get `Artist - Title` of what the stream is playing. The room page lists it as **MP3 audio** with its URL (`…/live/<room-link-name>.mp3` for a public or unlisted room, `…/live/<token>.mp3` for a private one) and a Copy button; in a private room, Rotate URL makes a new one (the old one stops at once). Outputs turn off by themselves after 10 minutes with an empty queue or nobody listening, unless the room owner sets them to always on (Manage room → Settings → Outputs); turn it on again on the room page.

### Euro Truck Simulator 2 and American Truck Simulator

Both games play internet radio from a list in `live_streams.sii`:

1. Turn on the room's **MP3 audio** output and copy its URL.
2. Close the game and open `Documents\Euro Truck Simulator 2\live_streams.sii` (or `Documents\American Truck Simulator\live_streams.sii`) in a text editor. The game writes this file after you have opened its radio once.
3. In the `live_stream_def` block, raise the count in `stream_data: N` by one and add a line with the next index (the first index is 0, so the new line's index is the old count):

   ```
   stream_data[N]: "<the MP3 URL>|<room name>|TogetherTunes|EN|128|0"
   ```

   The fields are URL, station name, genre, language, bitrate (kb/s; use the server's MP3 bitrate, 128 by default) and favourite (`0` or `1`). Don't use `|` or `"` in the name.
4. Start the game, open the radio (R by default), choose the station.

What to expect: the station plays through item changes and pauses (a pause is silence, not a disconnect), and the game shows the station name. **Plain HTTP needed:** the games' audio library has no TLS. Given an `https://` URL it connects to port 80 over plain HTTP anyway, so the stream only plays if the server serves it there: the server admin sets `http.insecureBaseUrl` (e.g. `http://music.example.com`) and makes the reverse proxy forward port 80 to TogetherTunes as well as 443. The room page then shows a second URL under the MP3 URL, "For players that can't use HTTPS": put that `http://` URL in `live_streams.sii`. This works for private rooms too, but their URL then carries its token unencrypted: the room page warns once it has been used that way, and Rotate URL makes a new one. Community reports say 128 kb/s MP3 is the safe choice, so keep the server's MP3 bitrate at 128 for the games.
