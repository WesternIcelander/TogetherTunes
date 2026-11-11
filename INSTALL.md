# Installing TogetherTunes

TogetherTunes runs either as a Docker image (ffmpeg, yt-dlp and the font libraries included) or as a
plain jar on a host that provides them. Either way it needs a public **HTTPS** URL (normally a
reverse proxy in front of port 8080) and keeps everything in one data directory.

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
- **yt-dlp** (the standalone release binary, `YTDLP_VERSION`, checked against the release's
  checksums) and **Deno** (`DENO_VERSION`), the JavaScript runtime yt-dlp needs for YouTube.
  yt-dlp lives in `/opt/yt-dlp/`, owned by the server's user, so **Admin → Settings → Update
  yt-dlp** works; the update lasts until the container is recreated. Rebuild with a newer
  `--build-arg YTDLP_VERSION=…` to make it permanent.
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
- **yt-dlp**, kept up to date (the admin panel can run `yt-dlp -U` when the server's user may write
  to its file), and **Deno** on `PATH` for YouTube.
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
variables override it. ffmpeg, ffprobe and yt-dlp are found on `PATH` unless `tools.ffmpeg`,
`tools.ffprobe` and `tools.ytdlp` name them. `--help` prints the options. Run it as its own user
under a service manager (e.g. a systemd unit with `WorkingDirectory=` set to where `config.json`
is) and back up the data directory (`data.dir`, default `./data`).
