-- Room overlay modes (docs/media-types.md#qr-overlay-modes): the room setting overlay.enabled
-- becomes overlay.mode (off | always | idleOnly). Stored values move: overlay.enabled true ->
-- overlay.mode "always", false -> nothing (off is the default); an overlay group left empty is
-- removed. Rows whose settings aren't a valid JSON object are left alone (they read as the defaults).
UPDATE rooms
SET settings_json = (CASE WHEN (settings_json::jsonb #> '{overlay,enabled}') = 'true'::jsonb
                          THEN jsonb_set(settings_json::jsonb #- '{overlay,enabled}', '{overlay,mode}', '"always"'::jsonb)
                          ELSE settings_json::jsonb #- '{overlay,enabled}' END)::text
WHERE CASE WHEN settings_json IS JSON OBJECT
           THEN jsonb_typeof(settings_json::jsonb -> 'overlay') = 'object'
                AND (settings_json::jsonb #> '{overlay,enabled}') IS NOT NULL
           ELSE false END;
UPDATE rooms
SET settings_json = (settings_json::jsonb - 'overlay')::text
WHERE CASE WHEN settings_json IS JSON OBJECT
           THEN (settings_json::jsonb -> 'overlay') = '{}'::jsonb
           ELSE false END;
