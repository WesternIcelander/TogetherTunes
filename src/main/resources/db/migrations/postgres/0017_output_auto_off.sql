-- Auto turn-off for every output (docs/program-output.md#auto-turn-off): the RTMP-only idle stop
-- becomes the room setting outputs.autoOff / outputs.autoOffMinutes and the server settings
-- outputs.forceAutoOff / outputs.maxAutoOffMinutes. Stored values move to the new names:
-- rooms.settings_json outputs.rtmp.idleStopMinutes -> outputs.autoOffMinutes, and the server
-- setting outputs.rtmp.maxIdleStopMinutes -> outputs.maxAutoOffMinutes. Rows whose settings
-- aren't a valid JSON object are left alone (they read as the defaults anyway).
UPDATE rooms
SET settings_json = (jsonb_set(settings_json::jsonb #- '{outputs,rtmp,idleStopMinutes}', '{outputs,autoOffMinutes}',
        settings_json::jsonb #> '{outputs,rtmp,idleStopMinutes}'))::text
WHERE CASE WHEN settings_json IS JSON OBJECT
           THEN jsonb_typeof(settings_json::jsonb -> 'outputs') = 'object'
                AND (settings_json::jsonb #> '{outputs,rtmp,idleStopMinutes}') IS NOT NULL
           ELSE false END;
UPDATE rooms
SET settings_json = (settings_json::jsonb #- '{outputs,rtmp}')::text
WHERE CASE WHEN settings_json IS JSON OBJECT
           THEN (settings_json::jsonb #> '{outputs,rtmp}') = '{}'::jsonb
           ELSE false END;
UPDATE settings SET key = 'outputs.maxAutoOffMinutes' WHERE key = 'outputs.rtmp.maxIdleStopMinutes';
