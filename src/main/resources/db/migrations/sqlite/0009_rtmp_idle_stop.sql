-- Idle stop of RTMP pushes (docs/program-output.md#idle-stop): the server turns a room's RTMP
-- output off once the room has been idle (nothing playing, nothing queued) for its limit, and
-- records why so the owner sees it. Set when the server turned the output off; cleared by any
-- change of enabled (the owner turning it on or off).
-- stopped_reason: 'idle' (NULL: on, or turned off by the owner). stopped_at: epoch ms.
-- stopped_idle_minutes: the limit that applied.
ALTER TABLE stream_outputs ADD COLUMN stopped_reason TEXT NULL;
ALTER TABLE stream_outputs ADD COLUMN stopped_at BIGINT NULL;
ALTER TABLE stream_outputs ADD COLUMN stopped_idle_minutes INTEGER NULL;
