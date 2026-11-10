# User guide: watching a room's stream

External documentation for people using a TogetherTunes server. The software itself never names third-party games or services (see `CLAUDE.md`); how to use the stream in a particular game or with a particular streaming service lives here instead. M7's deployment notes can build on this page.

A room can have up to three **stream outputs** (see [program-output.md](program-output.md)): an **RTMP push** to a streaming service, a **direct MPEG-TS URL** (`.ts`) and **HLS** (`.m3u8`). The room page's **Stream outputs** panel shows their URLs with copy buttons. All three carry the same H.264/AAC program.

## In VRChat

- **Where the URL goes:** paste the stream URL into the world's video player (any player that plays live streams, e.g. one based on AVPro). On the room page this is the URL under **Watch on the stream** (the RTMP service's viewer URL), or the `.ts` / HLS URL from the outputs panel.
- **Allowlist:** VRChat by itself only plays URLs from its own allowlist, and a self-hosted server isn't on it. For the `.ts` and HLS URLs, every viewer must turn on **"Allow Untrusted URLs"** in VRChat's settings. In **public and group-public** instances the world must also list the server's domain in its **"Video Player Allowed Domains"**. (In the UI this is the generic note "Some players only play URLs from an allowlist…".)
- **Remote mode:** people who hear the room in VRChat can still open the room page (or scan the QR code on the stream) and choose **"I'm listening through the stream (remote mode)"**. The page then plays nothing itself and shows the queue and progress delayed to match what the stream is showing; the −0.5 s / +0.5 s buttons fine-tune the delay.

## RTMP service (e.g. VRCDN)

The RTMP output pushes the program to an RTMP ingest, such as VRCDN, which then serves it to viewers from its own servers (often on a domain the game already allows).

1. Create a stream at the service and note its **ingest URL**, **stream key** and the **viewer URL** it gives out.
2. On the room page, **Set up RTMP push** with the ingest URL and the stream key.
3. Under **Manage room → Settings → Outputs**, save the service's viewer URL as the **RTMP viewer URL**. The room page then shows it to everyone under **Watch on the stream**, and that is the URL viewers paste into the world's video player.

## HLS and staying in sync

HLS players start about three segments behind the live edge, and not all at the same segment, so viewers on HLS (for example in a VRChat world) can be a few seconds apart from each other. When everyone in the same place should hear the same moment, use the `.ts` URL or an RTMP service instead. The room page shows a generic version of this note while HLS is on.
