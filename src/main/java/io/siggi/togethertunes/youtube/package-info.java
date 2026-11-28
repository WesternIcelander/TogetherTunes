/**
 * The YouTube service: every YouTube call (lookup, search, download, the admin's status and update)
 * goes over HTTP to a YTSucks instance ({@code youtube.serviceUrl}), which runs yt-dlp, its PO token
 * provider and ffmpeg and keeps them updated ({@code docs/library-and-ingest.md#youtube-caching}).
 */
package io.siggi.togethertunes.youtube;
