# Installing TogetherTunes

TogetherTunes runs either as a Docker image (ffmpeg and the font libraries included) or as a
plain jar on a host that provides them. Either way it needs a public **HTTPS** URL (normally a
reverse proxy in front of port 8080) and keeps everything in one data directory. YouTube links,
searches and downloads go through a separate service, **YTSucks**, in its own container (see
[YouTube](#youtube-the-ytsucks-service) below); without it everything else works and YouTube is
unavailable.

[DEPLOYMENT.md](DEPLOYMENT.md) has the rest of what a real deployment needs: HTTPS, the reverse
proxy (with an nginx example), trusted proxies, VRChat, card fonts on offline servers and backups.

## Docker

The image is built from this repository. The jar is built first, outside Docker, because the build
uses dependencies from the local Maven repository:

```sh
mvn package -DskipTests
docker build -t togethertunes .
```

`docker build --platform linux/amd64` or `linux/arm64` builds for the other architecture; both are
supported.

What the image contains:

- Eclipse Temurin 21 JRE on Ubuntu 24.04.
- **ffmpeg / ffprobe 6.1.1** (Ubuntu's package, pinned by `FFMPEG_VERSION`), built with TLS, so
  `rtmps://` push works. This is a version the card encoder was tested with; changing it changes
  the reference card, which makes the server re-encode the cards once.
- FreeType, fontconfig and DejaVu fonts, which the card renderer needs.

Run it:

```sh
docker run -d --name togethertunes \
  -p 8080:8080 \
  -v togethertunes-data:/data \
  -e TOGETHERTUNES_HTTP_PUBLIC_BASE_URL=https://music.example.com \
  -e TOGETHERTUNES_HTTP_TRUSTED_PROXIES=172.16.0.0/12 \
  togethertunes
```

- **`/data`** is the data directory and the working directory: database (SQLite by default),
  media, cards, fonts and `secret.key`. Keep it on a volume and back it up. The server runs as
  uid/gid **10001**; with a bind mount instead of a named volume, give that user the directory
  first (`chown -R 10001:10001 /srv/togethertunes`).
- **Configuration:** every config key can be set as an environment variable
  (`http.publicBaseUrl` → `TOGETHERTUNES_HTTP_PUBLIC_BASE_URL`, `db.url` → `TOGETHERTUNES_DB_URL`,
  and so on), or put a `config.json` (see `config.example.json`) into `/data`. Leave `data.dir` and
  the `tools.*` keys alone: the image sets them.
- **Behind a reverse proxy**, list the proxy's address range in `http.trustedProxies` (from inside
  the container the proxy is not loopback) and turn off response buffering for `/live/`.
- **PostgreSQL:** set `TOGETHERTUNES_DB_URL='jdbc:postgresql://db:5432/togethertunes?user=…&password=…'`.
  Media, cards and `secret.key` still live in `/data`.
- Logs go to the container's output (`docker logs togethertunes`). On first start open the site
  and create the admin account.

## Plain jar

Requirements on the host:

- **Java 21** (a JRE is enough).
- **ffmpeg and ffprobe**, 5.1 or newer; with TLS support if you push to `rtmps://` services
  (`ffmpeg -protocols` lists `rtmps`).
- **FreeType, fontconfig and at least one font**, also on a server without a display:
  - Debian/Ubuntu: `apt-get install libfreetype6 fontconfig fonts-dejavu-core`
  - Alpine: `apk add freetype fontconfig font-dejavu`
  - Fedora: `dnf install freetype fontconfig dejavu-sans-fonts`

  Without them the server exits at startup (code 3) and says what is missing.

Build and run:

```sh
mvn package -DskipTests            # target/TogetherTunes-1.0-SNAPSHOT.jar
cp config.example.json config.json # set http.publicBaseUrl at least
java -jar target/TogetherTunes-1.0-SNAPSHOT.jar
```

The server reads `./config.json` (or `--config <file>`, or `TOGETHERTUNES_CONFIG`), and environment
variables override it. ffmpeg and ffprobe are found on `PATH` unless `tools.ffmpeg` and
`tools.ffprobe` name them. (`tools.ytdlp` from older configs is ignored, with a warning at
startup.) `--help` prints the options. Run it as its own user
under a service manager (e.g. a systemd unit with `WorkingDirectory=` set to where `config.json`
is) and back up the data directory (`data.dir`, default `./data`).

## YouTube: the YTSucks service

TogetherTunes doesn't run yt-dlp itself. Every YouTube call (looking a link up, searching,
downloading the video) goes over HTTP to [YTSucks](https://github.com/WesternIcelander/YTSucks), a Docker
service that runs yt-dlp, its PO token provider, Deno and ffmpeg and keeps them up to date by
itself (each new set of tools is tested with a real download before it is used, without downtime).
Cookies, proxies and PO tokens are YTSucks's business; TogetherTunes passes none of them.

1. **Build and run YTSucks** from its repository (its README has the details). Keep it **off public
   interfaces**: put it on a Docker network shared with TogetherTunes without publishing its port,
   or publish it on loopback only:

   ```sh
   docker build -t ytsucks .                     # in the YTSucks checkout
   YT_TOKEN="$(openssl rand -hex 24)"            # TogetherTunes sends this token

   # next to the TogetherTunes container: a shared network, no published port
   docker network create togethertunes-net
   docker run -d --name ytsucks --restart unless-stopped \
     --network togethertunes-net -v ytsucks-data:/data \
     -e API_TOKEN="$YT_TOKEN" -e TZ="$(timedatectl show -p Timezone --value)" \
     ytsucks
   # and start TogetherTunes with --network togethertunes-net as well;
   # the service URL is then http://ytsucks:8080

   # next to a plain-jar install: only on the host's loopback (8081, since TogetherTunes has 8080)
   docker run -d --name ytsucks --restart unless-stopped \
     -p 127.0.0.1:8081:8080 -v ytsucks-data:/data \
     -e API_TOKEN="$YT_TOKEN" -e TZ="$(timedatectl show -p Timezone --value)" \
     ytsucks
   # the service URL is then http://127.0.0.1:8081
   ```

2. **Access:** YTSucks wants a token from every caller it doesn't `allow`. Either give TogetherTunes
   a token (`API_TOKEN` as above, or a `token <value>` line in YTSucks's
   `/data/config/access.txt`) and enter it in `youtube.serviceToken`, or add an
   `allow <TogetherTunes's address>` line to that access list and leave the token empty. Don't
   `allow` a Docker gateway address (with a published port, connections from the host or Docker
   Desktop can appear to come from it, which would let everyone in); see YTSucks's README,
   "Access control".

3. **Tell TogetherTunes:** Admin → Settings, Library group: `youtube.serviceUrl` (e.g.
   `http://ytsucks:8080` or `http://127.0.0.1:8081`; `http://` or `https://`, no user name, query
   or fragment) and, if used, `youtube.serviceToken` (a secret: never shown again, blank keeps the
   saved one, **Clear** removes it). `ytdlp.enabled` stays the switch that turns YouTube on or
   off. The **YouTube service** box on the same page shows whether the service answers, its tool
   versions, provider health and last update; **Test connection** checks the URL and that the
   token is accepted, **Update now** asks YTSucks to update its tools, and **Show update log**
   shows the last update's log.

**Cookies:** on a VPS or other datacenter address YouTube often asks for a sign-in ("confirm
you're not a bot"). Put a `cookies.txt` from a throwaway account into YTSucks's volume
(`docker cp cookies.txt ytsucks:/data/config/cookies.txt`; picked up on the next run, no restart),
or set YTSucks's `YTDLP_PROXY`. TogetherTunes has no cookies setting.
