-- Room overlay modes (docs/media-types.md#qr-overlay-modes): the room setting overlay.enabled
-- becomes overlay.mode (off | always | idleOnly). Stored values move: overlay.enabled true ->
-- overlay.mode "always", false -> nothing (off is the default); an overlay group left empty is
-- removed. Rows whose settings aren't valid JSON are left alone (they read as the defaults).
UPDATE rooms
SET settings_json = CASE WHEN json_type(settings_json, '$.overlay.enabled') = 'true'
                         THEN json_set(json_remove(settings_json, '$.overlay.enabled'), '$.overlay.mode', 'always')
                         ELSE json_remove(settings_json, '$.overlay.enabled') END
WHERE CASE WHEN json_valid(settings_json)
           THEN json_type(settings_json, '$.overlay') = 'object'
                AND json_type(settings_json, '$.overlay.enabled') IS NOT NULL
           ELSE 0 END;
UPDATE rooms
SET settings_json = json_remove(settings_json, '$.overlay')
WHERE CASE WHEN json_valid(settings_json)
           THEN json_extract(settings_json, '$.overlay') = '{}'
           ELSE 0 END;
