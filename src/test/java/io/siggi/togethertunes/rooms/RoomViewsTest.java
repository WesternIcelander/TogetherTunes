package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.library.Covers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoomViewsTest {
    private static final String ID = "01900000-0000-7000-8000-000000000001";

    private static JsonObject item(String source, String file, boolean web, Double gain) {
        RoomViews.ItemRow row = new RoomViews.ItemRow(ID, "audio", "T", null, 1000L, "ready", source, false, file, web,
                gain);
        return RoomViews.item(new RoomViews.Lookup(Map.of(ID, row), Map.of()), ID);
    }

    @Test
    void webPlayableOriginalsCarryTheirTypeForCanPlayType() {
        JsonObject o = item("upload", "original.flac", true, -3.5);
        assertEquals("/media/" + ID + "/original", o.get("originalUrl").getAsString());
        assertEquals(-3.5, o.get("originalGainDb").getAsDouble());
        assertEquals("audio/flac", o.get("originalType").getAsString());
        assertEquals("audio/mp4; codecs=\"mp4a.40.2\"", item("upload", "original.m4a", true, null)
                .get("originalType").getAsString());
        assertEquals(0.0, item("upload", "original.m4a", true, null).get("originalGainDb").getAsDouble());
        assertEquals("audio/ogg; codecs=\"opus\"", RoomViews.originalType("original.opus"));
        assertEquals("audio/webm; codecs=\"opus\"", RoomViews.originalType("original.webm"));
        assertEquals("audio/mpeg", RoomViews.originalType("original.mp3"));
        assertEquals("audio/wav", RoomViews.originalType("original.wav"));
        assertEquals("audio/aac", RoomViews.originalType("original.aac"));
        assertNull(RoomViews.originalType("original.bin"));
        assertNull(RoomViews.originalType(null));
        // An unlisted extension still offers the original (ingest said web-playable), without a type.
        JsonObject bin = item("upload", "original.bin", true, null);
        assertEquals("/media/" + ID + "/original", bin.get("originalUrl").getAsString());
        assertFalse(bin.has("originalType"));
    }

    /** The album (system media controls): given when the item has one, left out when empty or unknown. */
    @Test
    void albumWhenKnown() {
        RoomViews.ItemRow with = new RoomViews.ItemRow(ID, "audio", "T", "A", 1000L, "ready", "upload", false, null,
                false, null, 0, null, true, "audio", null, "Record");
        assertEquals("Record", RoomViews.item(new RoomViews.Lookup(Map.of(ID, with), Map.of()), ID)
                .get("album").getAsString());
        RoomViews.ItemRow empty = new RoomViews.ItemRow(ID, "audio", "T", "A", 1000L, "ready", "upload", false, null,
                false, null, 0, null, true, "audio", null, "");
        assertFalse(RoomViews.item(new RoomViews.Lookup(Map.of(ID, empty), Map.of()), ID).has("album"));
        assertFalse(item("upload", null, false, null).has("album"));
    }

    @Test
    void mediaUrlsNameTheCurrentVersion() {
        RoomViews.ItemRow row = new RoomViews.ItemRow(ID, "video", "T", null, 1000L, "ready", "youtube", false, null,
                false, null, 4);
        JsonObject o = RoomViews.item(new RoomViews.Lookup(Map.of(ID, row), Map.of()), ID);
        assertEquals("/media/" + ID + "/v/4/mezz.mp4", o.get("videoUrl").getAsString());
        assertEquals("/media/" + ID + "/v/4/audio.m4a", o.get("audioUrl").getAsString());
        assertEquals("/media/" + ID + "/v/4/cover.jpg", o.get("coverUrl").getAsString());
    }

    /** An item without a {@code cover.jpg} (e.g. an upload with no picture) gets no {@code coverUrl}. */
    @Test
    void coverUrlOnlyWhenTheVersionHasACover() {
        RoomViews.ItemRow row = new RoomViews.ItemRow(ID, "video", "T", null, 1000L, "ready", "upload", false, null,
                false, null, 3, null, false);
        JsonObject o = RoomViews.item(new RoomViews.Lookup(Map.of(ID, row), Map.of()), ID);
        assertFalse(o.has("coverUrl"));
        assertEquals("/media/" + ID + "/v/3/audio.m4a", o.get("audioUrl").getAsString());
    }

    @Test
    void coversChecksTheVersionsCoverFile(@TempDir Path dir) throws Exception {
        DataDir data = DataDir.create(dir.resolve("data"));
        Covers covers = new Covers(data);
        Path v3 = MediaVersions.versionDir(data, ID, 3);
        Files.createDirectories(v3);
        Files.writeString(v3.resolve("audio.m4a"), "x");
        assertFalse(covers.exists(ID, 3));
        assertFalse(covers.exists(ID, 4), "a version that doesn't exist");
        Files.writeString(v3.resolve("cover.jpg"), "x");
        assertTrue(covers.exists(ID, 3));
        assertFalse(covers.exists("../etc", 3), "not an item id");
        assertTrue(Covers.ALWAYS.exists(ID, 3));
    }

    /**
     * Audio-only rooms: an entry that plays audio-only gives web clients audio URLs only (no
     * {@code videoUrl}, so {@code mezz.mp4} is never requested); a video with an audio trim plays
     * its {@code audio-only.m4a} for that file's length. Normal entries are unchanged.
     */
    @Test
    void audioOnlyEntriesCarryAudioUrlsOnly() {
        RoomViews.ItemRow trimmed = new RoomViews.ItemRow(ID, "video", "T", null, 60_000L, "ready", "youtube", false,
                null, false, null, 4, 20_000L);
        String plainId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
        RoomViews.ItemRow plain = new RoomViews.ItemRow(plainId, "video", "T", null, 45_000L, "ready", "youtube",
                false, null, false, null, 2);
        RoomViews.Lookup l = new RoomViews.Lookup(Map.of(ID, trimmed, plainId, plain), Map.of());

        JsonObject o = RoomViews.item(l, ID, true);
        assertFalse(o.has("videoUrl"));
        assertEquals("/media/" + ID + "/v/4/audio-only.m4a", o.get("audioUrl").getAsString());
        assertEquals(20_000, o.get("durationMs").getAsLong());
        o = RoomViews.item(l, plainId, true);
        assertFalse(o.has("videoUrl"));
        assertEquals("/media/" + plainId + "/v/2/audio.m4a", o.get("audioUrl").getAsString());
        assertEquals(45_000, o.get("durationMs").getAsLong());
        // the same item in a normal entry
        o = RoomViews.item(l, ID);
        assertEquals("/media/" + ID + "/v/4/mezz.mp4", o.get("videoUrl").getAsString());
        assertEquals("/media/" + ID + "/v/4/audio.m4a", o.get("audioUrl").getAsString());
        assertEquals(60_000, o.get("durationMs").getAsLong());

        QueueEntry e = new QueueEntry("e1", ID, "u", null,
                1, QueueEntry.State.PLAYING, 1, false, 1L, "ready", 60_000L, true,
                20_000L, true);
        JsonObject cur = RoomViews.current(l, e);
        assertTrue(cur.get("audioOnly").getAsBoolean());
        assertFalse(cur.getAsJsonObject("item").has("videoUrl"));
        assertFalse(RoomViews.current(l, e.withAudioOnly(false)).has("audioOnly"));
        assertTrue(RoomViews.current(l, e.withAudioOnly(false)).getAsJsonObject("item").has("videoUrl"));
    }

    @Test
    void noOriginalUnlessWebPlayableUpload() {
        assertFalse(item("upload", "original.wma", false, -1.0).has("originalUrl"));
        assertFalse(item("upload", "original.wma", false, -1.0).has("originalType"));
        assertFalse(item("youtube", "original.m4a", true, -1.0).has("originalUrl"));
    }
    /**
     * From the database: an audio upload's original is offered only when its personal data was
     * removed (or it is a format that wasn't cleaned); an .m4a not cleaned yet, or any whose
     * cleaning failed, plays audio.m4a (docs/library-and-ingest.md#looking-details-up-online).
     */
    @Test
    void originalsWithPersonalDataAreNotOffered(@TempDir Path dir) throws Exception {
        io.siggi.togethertunes.db.Database db = io.siggi.togethertunes.db.Database.open(
                "jdbc:sqlite:" + dir.resolve("t.db"), new java.util.Properties(),
                io.siggi.togethertunes.db.Database.PoolSettings.defaults(io.siggi.togethertunes.db.SqliteDialect.INSTANCE));
        try {
            io.siggi.togethertunes.db.MigrationRunner.migrate(db);
            db.write(conn -> {
                try (java.sql.PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, username, display_name,"
                        + " created_at, disabled) VALUES ('u1', 'uploader', 'uploader', 1, 0)")) {
                    ps.executeUpdate();
                }
                return null;
            });
            String[][] cases = {{"original.m4a", null, "false"}, {"original.m4a", "0", "false"}, {"original.m4a", "1", "true"},
                    {"original.flac", "0", "false"}, {"original.mp3", null, "true"}};
            java.util.List<QueueEntry> entries = new java.util.ArrayList<>();
            for (String[] c : cases) {
                String id = io.siggi.togethertunes.db.UuidV7.newId();
                db.write(conn -> {
                    try (java.sql.PreparedStatement ps = conn.prepareStatement("INSERT INTO items (id, kind, title, source,"
                            + " added_by_user_id, added_at, status, output_profile, last_referenced_at, duration_ms, original_file,"
                            + " original_web, original_cleaned) VALUES (?, 'audio', 'T', 'upload', 'u1', 1, 'ready', '720p30', 1,"
                            + " 1000, ?, 1, ?)")) {
                        ps.setString(1, id);
                        ps.setString(2, c[0]);
                        ps.setObject(3, c[1] == null ? null : Integer.valueOf(c[1]));
                        ps.executeUpdate();
                    }
                    return null;
                });
                c[1] = id; // from here: the item id
                entries.add(new QueueEntry("e" + entries.size(), id, null, null, 1, QueueEntry.State.QUEUED, 1, false,
                        null, "ready", 1000L, true, null, false));
            }
            RoomViews.Lookup l = new RoomViews(db).lookup(entries, java.util.List.of());
            for (String[] c : cases) {
                assertEquals(Boolean.parseBoolean(c[2]), RoomViews.item(l, c[1]).has("originalUrl"), c[0]);
            }
        } finally {
            db.close();
        }
    }
}
