# TogetherTunes server image: the shaded jar plus ffmpeg/ffprobe and what the card renderer needs
# (FreeType, fontconfig, one font). YouTube goes through a separate YouTube service (YTSucks, its own
# container; the youtube.serviceUrl setting), so the image has no YouTube downloader. See INSTALL.md.
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
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# The server runs as an unprivileged user; /data is its data directory (database, media, cards,
# fonts, secret.key) and the working directory, so an optional /data/config.json is read.
RUN groupadd --gid 10001 togethertunes \
    && useradd --uid 10001 --gid togethertunes --home-dir /data --shell /usr/sbin/nologin togethertunes \
    && mkdir -p /data \
    && chown -R togethertunes:togethertunes /data

COPY target/TogetherTunes-1.0-SNAPSHOT.jar /opt/togethertunes/TogetherTunes.jar

ENV TOGETHERTUNES_DATA_DIR=/data \
    TOGETHERTUNES_TOOLS_FFMPEG=/usr/bin/ffmpeg \
    TOGETHERTUNES_TOOLS_FFPROBE=/usr/bin/ffprobe

USER togethertunes
WORKDIR /data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/togethertunes/TogetherTunes.jar"]
