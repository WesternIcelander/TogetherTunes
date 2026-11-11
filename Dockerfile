# TogetherTunes server image: the shaded jar plus ffmpeg/ffprobe, yt-dlp and what the card renderer
# needs (FreeType, fontconfig, one font). See INSTALL.md.
#
# Build the jar first (the SNAPSHOT dependencies only resolve from the local Maven repository, so
# the jar isn't built inside the image), then the image:
#   mvn package -DskipTests
#   docker build -t togethertunes .
#
# Base: Eclipse Temurin 21 JRE on Ubuntu 24.04 (noble), amd64 and arm64.
FROM eclipse-temurin:21-jre-noble

# ffmpeg is pinned to Ubuntu 24.04's 6.1.1, one of the versions in the skip-frame card version
# matrix (PLAN.md M5): skip-frame cards and the reference avcC were tested with it. Ubuntu's build
# has TLS (GnuTLS), so rtmps:// push works. Changing the version changes the reference card (the
# avcC differs between <= 6.1 and >= 7.1), which re-encodes the library's cards once.
# libfreetype6 + fontconfig + fonts-dejavu-core: Java2D text (cards) needs all three, also headless.
ARG FFMPEG_VERSION=7:6.1.1-3ubuntu5
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        "ffmpeg=${FFMPEG_VERSION}" \
        libfreetype6 fontconfig fonts-dejavu-core \
        ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

# Deno: yt-dlp needs a JavaScript runtime for YouTube (without one it warns that extraction is
# deprecated and some formats may be missing); deno on PATH is the one it uses by default.
ARG DENO_VERSION=v2.9.8
ARG TARGETARCH
RUN set -eu; \
    case "${TARGETARCH:-$(dpkg --print-architecture)}" in \
        amd64) asset=deno-x86_64-unknown-linux-gnu.zip ;; \
        arm64) asset=deno-aarch64-unknown-linux-gnu.zip ;; \
        *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    base="https://github.com/denoland/deno/releases/download/${DENO_VERSION}"; \
    tmp="$(mktemp -d)"; cd "$tmp"; \
    curl -fsSL -o "$asset" "$base/$asset"; \
    curl -fsSL -o "$asset.sha256sum" "$base/$asset.sha256sum"; \
    sha256sum -c "$asset.sha256sum"; \
    unzip -q "$asset" deno -d /usr/local/bin; chmod 755 /usr/local/bin/deno; \
    cd /; rm -rf "$tmp"; deno --version

# yt-dlp: the standalone release binary for the image's architecture, checked against the release's
# SHA2-256SUMS. It lives in a directory the server user owns, so the admin panel's "Update yt-dlp"
# (yt-dlp -U) can replace it; an update lasts until the container is recreated (rebuild with a newer
# YTDLP_VERSION, or press Update again).
ARG YTDLP_VERSION=2026.10.26
RUN set -eu; \
    case "${TARGETARCH:-$(dpkg --print-architecture)}" in \
        amd64) asset=yt-dlp_linux ;; \
        arm64) asset=yt-dlp_linux_aarch64 ;; \
        *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    base="https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_VERSION}"; \
    mkdir -p /opt/yt-dlp; cd /opt/yt-dlp; \
    curl -fsSL -o "$asset" "$base/$asset"; \
    curl -fsSL -o SHA2-256SUMS "$base/SHA2-256SUMS"; \
    grep " $asset\$" SHA2-256SUMS | sha256sum -c -; \
    mv "$asset" yt-dlp; rm SHA2-256SUMS; chmod 755 yt-dlp

# The server runs as an unprivileged user; /data is its data directory (database, media, cards,
# fonts, secret.key) and the working directory, so an optional /data/config.json is read.
RUN groupadd --gid 10001 togethertunes \
    && useradd --uid 10001 --gid togethertunes --home-dir /data --shell /usr/sbin/nologin togethertunes \
    && mkdir -p /data \
    && chown -R togethertunes:togethertunes /data /opt/yt-dlp

COPY target/TogetherTunes-1.0-SNAPSHOT.jar /opt/togethertunes/TogetherTunes.jar

ENV TOGETHERTUNES_DATA_DIR=/data \
    TOGETHERTUNES_TOOLS_FFMPEG=/usr/bin/ffmpeg \
    TOGETHERTUNES_TOOLS_FFPROBE=/usr/bin/ffprobe \
    TOGETHERTUNES_TOOLS_YTDLP=/opt/yt-dlp/yt-dlp

USER togethertunes
WORKDIR /data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/togethertunes/TogetherTunes.jar"]
