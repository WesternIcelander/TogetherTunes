-- Auto turn-off for every output (docs/program-output.md#auto-turn-off): the RTMP-only idle stop
-- becomes the room setting outputs.autoOff / outputs.autoOffMinutes and the server settings
-- outputs.forceAutoOff / outputs.maxAutoOffMinutes. Stored values move to the new names:
-- rooms.settings_json outputs.rtmp.idleStopMinutes -> outputs.autoOffMinutes, and the server
-- setting outputs.rtmp.maxIdleStopMinutes -> outputs.maxAutoOffMinutes. Rows whose settings
-- aren't valid JSON are left alone (they read as the defaults anyway).
UPDATE rooms
SET settings_json = json_remove(
        json_set(settings_json, '$.outputs.autoOffMinutes', json_extract(settings_json, '$.outputs.rtmp.idleStopMinutes')),
        '$.outputs.rtmp.idleStopMinutes')
WHERE CASE WHEN json_valid(settings_json)
           THEN json_type(settings_json, '$.outputs.rtmp.idleStopMinutes') IS NOT NULL
           ELSE 0 END;
UPDATE rooms
SET settings_json = json_remove(settings_json, '$.outputs.rtmp')
WHERE CASE WHEN json_valid(settings_json)
           THEN json_extract(settings_json, '$.outputs.rtmp') = '{}'
           ELSE 0 END;
UPDATE settings SET key = 'outputs.maxAutoOffMinutes' WHERE key = 'outputs.rtmp.maxIdleStopMinutes';
