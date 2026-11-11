# Deploying TogetherTunes

Notes for running a TogetherTunes server for real: HTTPS, the reverse proxy, what the host needs,
VRChat, and backups. How to build and start the server (Docker image or plain jar) is in
[INSTALL.md](INSTALL.md).

## HTTPS

- The server must be **publicly reachable over HTTPS**. Web users fetch pages, media and the
  WebSocket directly from it, and so do players using the `.ts`, HLS and MP3 outputs. VRChat on
  Android/Quest refuses plain HTTP. Only the RTMP push needs nothing inbound: viewers fetch that
  stream from the RTMP service.
- The server speaks plain HTTP on `http.port` (default 8080). TLS is terminated by a **reverse
  proxy** in front of it (nginx, Caddy, …) with a certificate for your domain (e.g. Let's Encrypt).
- Set **`http.publicBaseUrl`** to the public `https://` address (no trailing path), e.g.
  `https://music.example.com`. Stream URLs, QR codes and login redirect URIs are built from it.
- **The upload pre-check only works over HTTPS.** Before uploading, the browser hashes the file
  and asks whether the server already has it; if so, nothing is uploaded. The browser can only do
  that hashing (`crypto.subtle`) on a secure page, so the pre-check is on only when
  `http.publicBaseUrl` is `https://`. Plain-HTTP deployments are **not supported** for it: there,
  every upload sends every byte, even for files the server already has.
- If the host has no public IPv4 address (e.g. behind CGNAT), run the server on a VPS, or
  reverse-proxy or tunnel to it from one.
- **Players that can't do HTTPS at all** (some in-game radio players only speak plain HTTP): set
  **`http.insecureBaseUrl`** to the `http://` address of the same server and forward port 80 to
  it as well. Plain HTTP then serves only the stream URLs and answers everything else with a
  `301` to HTTPS. The outputs panel shows the extra plain-HTTP URL; a private room's token that
  was used over plain HTTP is flagged there (rotate it if that matters). Leave it unset otherwise.
  For local testing an `http://localhost:8080` `http.publicBaseUrl` works, without the pre-check.

## Reverse proxy

What the proxy must do:

- **Turn off response buffering for `/live/`.** The `.ts` and MP3 streams never end; a buffering
  proxy holds them back and players stall. In nginx: `proxy_buffering off`. (The YouTube import
  progress stream sends `X-Accel-Buffering: no` by itself.)
- **Pass WebSocket upgrades** on `/ws` (rooms are live over it).
- **Long timeouts** for `/live/` and `/ws`, which stay open for hours.
- **Allow 8 MiB request bodies at least**: uploads are sent in 8 MiB chunks (nginx's default
  `client_max_body_size` is only 1 MiB).
- **Send `X-Forwarded-For` and `X-Forwarded-Proto`** (`https`, or `http` for requests that came
  in on port 80).
- **Access logs:** a private room's stream URL contains its secret token
  (`/live/<token>.ts`). The server never logs it, but a proxy's access log does. Turn the access
  log off for `/live/`, or keep those logs private.

**Trusted proxies.** The server takes the client's address from `X-Forwarded-For`, and the scheme
from `X-Forwarded-Proto`, only when the request comes from an address in **`http.trustedProxies`**
(a list of CIDRs; loopback is always trusted). Behind a proxy on the same host the default
(`127.0.0.1/32`, `::1/128`) is right. If the proxy is on another host, or the server runs in a
container (where the proxy is not loopback), list the proxy's address or range, e.g.
`["172.16.0.0/12"]` for Docker's default networks
(env `TOGETHERTUNES_HTTP_TRUSTED_PROXIES=172.16.0.0/12`). Without it every guest seems to come
from the proxy's address (guest limits and bans then hit everyone at once), and plain-HTTP
requests can't be told apart from HTTPS ones. Never list ranges that ordinary clients can connect
from: they could then fake their address.

Example nginx site (TLS set up as usual, e.g. with certbot):

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name music.example.com;
    # ssl_certificate / ssl_certificate_key ...

    client_max_body_size 16m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /live/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 24h;
        access_log off;   # private rooms' stream URLs carry their token
    }

    location = /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 24h;
    }
}

# Only with http.insecureBaseUrl set: pass plain HTTP through (the server redirects all but
# stream URLs). Without it, redirect port 80 to HTTPS here as usual.
server {
    listen 80;
    server_name music.example.com;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 24h;
        access_log off;
    }
}
```

Caddy needs no buffering setting for these streams (it flushes streaming responses) and handles
WebSocket by itself: `music.example.com { reverse_proxy 127.0.0.1:8080 }`.

## VRChat

- A self-hosted domain is **not on VRChat's video allowlist**. For the `.ts` and HLS URLs every
  viewer has to turn on **"Allow Untrusted URLs"** in VRChat's settings, and in public and
  group-public instances the world must list your domain in its **"Video Player Allowed
  Domains"**. The server's UI only says this generically ("Some players only play URLs from an
  allowlist…").
- An **RTMP service** such as VRCDN avoids both: the room pushes to the service, and viewers use
  the service's viewer URL, which VRChat usually allows. Prefer an `rtmps://` ingest address so
  the stream key doesn't travel in clear text.
- Android/Quest clients need HTTPS (above).
- More for viewers (remote mode, HLS delay): `docs/user-guide.md` on the `plan` branch.
- Before a release, run the manual VRChat checks in [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md).

## Runtime requirements

- **Java 21**, **ffmpeg and ffprobe** (5.1 or newer; with TLS if you push to `rtmps://`),
  **yt-dlp** (kept current) with **Deno** for YouTube. The Docker image has all of them; see
  [INSTALL.md](INSTALL.md).
- **FreeType, fontconfig and at least one installed font**, for the card renderer (title cards
  and the paused/idle/loading cards are drawn with Java2D, also on a headless server). The cards
  use the server's bundled DejaVu Sans, but Java's font code needs all three anyway:
  - Debian/Ubuntu: `apt-get install libfreetype6 fontconfig fonts-dejavu-core`
  - Alpine: `apk add freetype fontconfig font-dejavu`
  - Fedora: `dnf install freetype fontconfig dejavu-sans-fonts`

  Without them the server exits at startup with code 3 and names what is missing.
  `java -cp TogetherTunes-1.0-SNAPSHOT.jar io.siggi.togethertunes.fonts.TextRenderingCheck` runs
  the same check alone.
- **Script fonts are downloaded on demand from GitHub.** Titles in scripts DejaVu Sans doesn't
  cover (Chinese, Japanese, Korean, Thai, Indic scripts, Arabic, Hebrew, emoji, …) make the server
  fetch the matching Noto font (pinned URLs on `raw.githubusercontent.com`, checked by size and
  SHA-256) into `<data>/fonts/`, then redraw the affected cards. **Offline servers** (or
  ones that may not reach GitHub) can't do that: the downloads fail and are retried. There, either
  **upload the fonts** the titles need (Admin → Fonts, TrueType/OpenType up to 32 MiB) or
  **install system fonts** (e.g. `fonts-noto-cjk`, `fonts-noto-core`), which the cards use as a
  last resort. Admin → Fonts lists the characters no available font has, and auto-download can be
  turned off there.
- **Disk:** media, encodes and caches all live in the data directory. Adding media pauses while
  the free space is below `storage.minFreeBytes`.

## Backups

Everything the server keeps is in the **data directory** (`data.dir`; `/data` in the Docker image)
plus, with PostgreSQL, the database. What matters:

| What | Where | Back up? |
| --- | --- | --- |
| Database (SQLite) | `<data>/db/media.db` (with `media.db-wal`, `media.db-shm` while running) | **Yes** |
| Database (PostgreSQL) | the server named by `db.url` | **Yes** (`pg_dump`) |
| Secret key | `<data>/secret.key` (or the `TOGETHERTUNES_SECRET_KEY` env var) | **Yes, and keep it secret** |
| Media | `<data>/media/` | **Yes** (the largest part) |
| Fonts | `<data>/fonts/`, uploaded fonts in `<data>/fonts/uploads/` | **Yes** for `uploads/`; downloaded fonts are fetched again |
| Config | `config.json` / environment | Yes |
| Filler cards, room overlays and their caches | `<data>/cards/`, `<data>/rooms/` | Optional: made again when missing |
| Uploads in progress, ingest scratch | `<data>/tmp/` | No |

- **`secret.key`** encrypts the login providers' client secrets in the database. A database
  restored without its key still works, but those secrets can't be decrypted: each login provider
  must be given its client secret again. Back it up with the database, but not next to it in the
  same unencrypted place: it is a secret (the file is created with permissions 0600).
- **The database backup is sensitive too:** it holds RTMP stream keys (in plain text), private
  rooms' stream tokens, password hashes and guests' IP addresses. Store and transfer it
  accordingly.
- **Media:** each item's directory holds its encodes and, for audio uploads, the original file.
  Video and YouTube originals are not kept, so an uploaded video lost from `media/` can't be
  remade; YouTube items can be fetched again.
- **SQLite:** don't copy `media.db` alone while the server runs (it is in WAL mode, and recent
  changes may still be in `media.db-wal`). Either stop the server and copy the whole data
  directory, or take a live snapshot with `sqlite3 <data>/db/media.db ".backup '/backup/media.db'"`
  (or `VACUUM INTO`) and then copy the rest.
- **PostgreSQL:** `pg_dump -Fc togethertunes > togethertunes.dump` (restore with `pg_restore`).
  Media, cards, fonts and `secret.key` are still in the data directory and need backing up too.
- **Consistency:** the database names which encode of each item is current, and old encodes are
  deleted once nobody plays them. For a backup that matches exactly, stop the server, back up
  database and data directory, start it again. A live backup (database snapshot first, then
  `media/` right after) is fine in practice; an item re-encoded in between may be missing its
  files after a restore and needs a re-encode or re-import.
- **Restore:** put the data directory back (owned by the server's user; uid 10001 in Docker),
  restore the database, use the same `secret.key` and config, and start the server. It applies any
  newer migrations on start.
