package io.siggi.togethertunes.rooms;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The program outputs a room's page shows ({@code docs/api.md#websocket}: the {@code outputs} of
 * {@code state} and the {@code output.status} messages). Implemented by the output side
 * ({@code output.OutputStatusFeed}), which also pushes changes through
 * {@link RoomSocket#outputStatus}.
 */
public interface RoomOutputs {
    /**
     * One output as the room page sees it.
     *
     * @param kind   {@code rtmp}, {@code ts}, {@code hls} or {@code mp3}
     * @param status RTMP: {@code connecting}, {@code connected}, {@code retrying} or {@code error};
     *               {@code .ts}/HLS: {@code ready}; MP3: {@code ready}, or {@code error} without ffmpeg; {@code off} (only in {@code output.status}) when
     *               the output is no longer active
     * @param detail RTMP: the push's last ffmpeg message or why it can't run (stream key masked to
     *               {@code …} + last 4, URL credentials masked); sent as is to the owner, to
     *               moderators only through {@link #moderatorDetail}; may be null
     */
    record Status(String kind, String status, String detail) {
    }

    /** A URL in an ffmpeg message (without trailing punctuation, as in ffmpeg's {@code <url>: <error>}). */
    Pattern URL_IN_TEXT = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s'\"<>]*[^\\s'\"<>:,.;)\\]]");
    /** What a moderator sees instead of a URL in the push detail. */
    String URL_HIDDEN = "(ingest URL)";

    /**
     * The push detail as moderators see it: the stream key and the ingest URL are the owner's
     * (the URL can carry credentials too), so every URL is replaced by {@value #URL_HIDDEN} and
     * any rest of a masked key ({@code …} + last 4) by {@code …}.
     */
    static String moderatorDetail(String detail) {
        if (detail == null) {
            return null;
        }
        String s = URL_IN_TEXT.matcher(detail).replaceAll(java.util.regex.Matcher.quoteReplacement(URL_HIDDEN));
        return s.replaceAll("…[^\\s…]{1,4}", "…");
    }

    /** The room's active outputs (row enabled AND server switch), in the order rtmp, ts, hls, mp3. */
    List<Status> active(String roomId) throws Exception;

    /** No outputs (tests, a server without the output side). */
    RoomOutputs NONE = roomId -> List.of();
}
