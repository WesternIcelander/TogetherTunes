package io.siggi.togethertunes.rooms;

import java.util.List;

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
     * @param kind   {@code rtmp}, {@code ts} or {@code hls}
     * @param status RTMP: {@code connecting}, {@code connected}, {@code retrying} or {@code error};
     *               {@code .ts}/HLS: {@code ready}; {@code off} (only in {@code output.status}) when
     *               the output is no longer active
     * @param detail RTMP: the push's last ffmpeg message or why it can't run (stream key masked);
     *               sent to the owner and moderators only; may be null
     */
    record Status(String kind, String status, String detail) {
    }

    /** The room's active outputs (row enabled AND server switch), in the order rtmp, ts, hls. */
    List<Status> active(String roomId) throws Exception;

    /** No outputs (tests, a server without the output side). */
    RoomOutputs NONE = roomId -> List.of();
}
