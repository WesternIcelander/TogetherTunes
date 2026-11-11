package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What {@code yt-dlp -J} says about a video, reduced to what an import needs
 * ({@code docs/library-and-ingest.md#youtube-caching}).
 *
 * <p><b>Title and artist:</b> {@code track} and {@code artist} when YouTube has them (music
 * releases), otherwise {@code title} and {@code uploader}. For the artist, yt-dlp's
 * {@code artists} list (newer versions) counts like {@code artist}, joined with ", ". An uploader
 * named "<i>Name</i> - Topic" (YouTube's auto-generated music channels) becomes "<i>Name</i>". The
 * album comes from {@code album}. Values are cleaned like upload metadata
 * ({@link UploadMetadata#clean}); a title that ends up blank becomes the video ID.
 *
 * <p><b>Live:</b> {@code is_live}, or a {@code live_status} of {@code is_live},
 * {@code is_upcoming} or {@code post_live} (a stream that just ended and isn't processed yet).
 *
 * @param id           the video ID yt-dlp reports
 * @param title        never blank
 * @param artist       null if unknown
 * @param album        null if unknown
 * @param durationSec  the duration in seconds, or a negative number if unknown
 * @param liveStatus   yt-dlp's {@code live_status}, or null
 * @param live         whether this is a live stream (or one that hasn't started or been processed)
 * @param uploader     the raw {@code uploader} (or {@code channel}), or null
 * @param series       yt-dlp's {@code series} (a show's name), or null
 * @param seasonNumber yt-dlp's {@code season_number} (≥ 0), or null
 * @param episodeNumber yt-dlp's {@code episode_number} (≥ 0), or null
 * @param track        yt-dlp's own {@code track} (YouTube's music metadata), or null
 * @param musicArtist  yt-dlp's own {@code artist} / {@code artists} (not the uploader), or null
 */
public record YoutubeMetadata(String id, String title, String artist, String album, double durationSec,
                              String liveStatus, boolean live, String uploader, String series,
                              Integer seasonNumber, Integer episodeNumber, String track, String musicArtist) {
    private static final Set<String> LIVE_STATUSES = Set.of("is_live", "is_upcoming", "post_live");
    private static final String TOPIC_SUFFIX = " - Topic";

    /**
     * Parses yt-dlp's JSON.
     *
     * @param fallbackId used as the ID (and last-resort title) when the JSON has none
     * @throws IllegalArgumentException if it isn't a JSON object
     */
    public static YoutubeMetadata parse(String json, String fallbackId) {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException("yt-dlp's metadata isn't a JSON object");
            }
            o = e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("yt-dlp's metadata isn't valid JSON", e);
        }
        String id = string(o, "id");
        if (id == null) {
            id = fallbackId;
        }
        String uploader = string(o, "uploader");
        if (uploader == null) {
            uploader = string(o, "channel");
        }

        String track = UploadMetadata.clean(string(o, "track"));
        String title = track;
        if (title == null) {
            title = UploadMetadata.clean(string(o, "title"));
        }
        if (title == null) {
            title = id;
        }
        String artist = UploadMetadata.clean(string(o, "artist"));
        if (artist == null) {
            artist = UploadMetadata.clean(joined(o, "artists"));
        }
        String musicArtist = artist;
        if (artist == null) {
            artist = UploadMetadata.clean(withoutTopic(uploader));
        }
        String album = UploadMetadata.clean(string(o, "album"));

        double duration = -1;
        JsonElement d = o.get("duration");
        if (d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
            duration = d.getAsDouble();
            if (!(duration >= 0) || Double.isInfinite(duration)) {
                duration = -1;
            }
        }
        String liveStatus = string(o, "live_status");
        JsonElement isLive = o.get("is_live");
        boolean live = (isLive != null && isLive.isJsonPrimitive() && isLive.getAsJsonPrimitive().isBoolean()
                && isLive.getAsBoolean())
                || (liveStatus != null && LIVE_STATUSES.contains(liveStatus.toLowerCase(Locale.ROOT)));
        return new YoutubeMetadata(id, title, artist, album, duration, liveStatus, live, uploader,
                UploadMetadata.clean(string(o, "series")), number(o, "season_number"), number(o, "episode_number"),
                track, musicArtist);
    }

    /** {@code "Name - Topic"} → {@code "Name"}; anything else unchanged. */
    static String withoutTopic(String uploader) {
        if (uploader != null && uploader.endsWith(TOPIC_SUFFIX) && uploader.length() > TOPIC_SUFFIX.length()) {
            return uploader.substring(0, uploader.length() - TOPIC_SUFFIX.length());
        }
        return uploader;
    }

    /** The duration in milliseconds, or null if unknown. */
    public Long durationMs() {
        return durationSec < 0 ? null : Math.round(durationSec * 1000);
    }

    /** What is kept in {@code items.metadata_json}: {@code {"youtube": {…}}} with the raw fields. */
    public JsonObject toItemMetadata() {
        JsonObject yt = new JsonObject();
        yt.addProperty("id", id);
        if (uploader != null) {
            yt.addProperty("uploader", UploadMetadata.clean(uploader));
        }
        if (durationSec >= 0) {
            yt.addProperty("durationSec", durationSec);
        }
        // What the classify suggestion reads (docs/media-types.md#media-types).
        if (series != null) {
            yt.addProperty("series", series);
        }
        if (seasonNumber != null) {
            yt.addProperty("seasonNumber", seasonNumber);
        }
        if (episodeNumber != null) {
            yt.addProperty("episodeNumber", episodeNumber);
        }
        if (track != null) {
            yt.addProperty("track", track);
        }
        if (musicArtist != null) {
            yt.addProperty("artist", musicArtist);
        }
        JsonObject out = new JsonObject();
        out.add("youtube", yt);
        return out;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    /** A whole number 0..{@link MediaType.Show#MAX_NUMBER} (as a number or a numeric string), or null. */
    private static Integer number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        double d;
        try {
            d = e.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
        if (!Double.isFinite(d) || d < 0 || d > MediaType.Show.MAX_NUMBER || d != Math.rint(d)) {
            return null;
        }
        return (int) d;
    }

    private static String joined(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        List<String> parts = new ArrayList<>();
        for (JsonElement x : a) {
            if (x.isJsonPrimitive() && !x.getAsString().isBlank()) {
                parts.add(x.getAsString().strip());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }
}
