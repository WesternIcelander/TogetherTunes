-- TogetherTunes no longer runs yt-dlp: YouTube goes through the YouTube service
-- (youtube.serviceUrl / youtube.serviceToken), which keeps its own cookies and PO token provider.
-- The stored values of the yt-dlp cookies file and PO token provider settings would be ignored;
-- drop them. Audit rows that mention them stay.
DELETE FROM settings WHERE key IN ('ytdlp.cookiesFile', 'ytdlp.poTokenProviderUrl');
