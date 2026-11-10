-- A room has at most one output of each kind (rtmp, ts, hls): the outputs API creates, enables,
-- disables and deletes "the room's .ts output", and RTMP pushes are one per room.
CREATE UNIQUE INDEX stream_outputs_room_kind ON stream_outputs (room_id, kind);
