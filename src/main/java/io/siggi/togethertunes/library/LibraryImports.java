package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.storage.DiskSpace;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Library imports ({@code docs/library-and-ingest.md#library-import}): an admin imports a music
 * library export ({@code Library.xml} and its media folder, a directory or a zip) into a user's
 * library. {@link #check} parses and matches (state {@code checking}, then {@code ready});
 * {@link #start} imports the tracks one file at a time as uploads by that user, then makes the
 * playlists. One import at a time server-wide; the state lives in {@code library_imports} and
 * {@code library_import_tracks}, so an import resumes after a restart ({@link #resume}).
 */
public final class LibraryImports {
    public static final String AUDIT_STARTED = "library.import_started";
    public static final String AUDIT_FINISHED = "library.import_finished";
    public static final String AUDIT_CANCELLED = "library.import_cancelled";

    /** Most problems kept per import. */
    public static final int MAX_PROBLEMS = 1000;
    /** Problems in {@code GET}. */
    public static final int FIRST_PROBLEMS = 20;
    /** Problems per page of {@code GET …/problems}. */
    public static final int PROBLEMS_PAGE = 100;

    static final Set<String> AUDIO = Set.of("m4a", "mp3", "wav", "aiff", "aif", "flac", "ogg", "opus");
    static final Set<String> VIDEO = Set.of("mp4", "m4v", "mov", "mkv", "webm");
    static final Set<String> PROTECTED = Set.of("m4p");

    /** The session id the import's uploads are made with (no real session). */
    static final String SESSION = "library-import";

    private static final Set<String> ACTIVE = Set.of("checking", "importing", "paused");
    private static final System.Logger LOG = System.getLogger(LibraryImports.class.getName());

    private final Database db;
    private final Uploads uploads;
    private final Playlists playlists;
    private final Path importsDir;
    private final List<Path> allowedPaths;
    private final Clock clock;
    private volatile Supplier<DiskSpace> diskSpace = DiskSpace::unlimited;
    private volatile BooleanSupplier ingestBusy = () -> false;
    private volatile Duration busyPoll = Duration.ofSeconds(1);
    private volatile Duration spacePoll = Duration.ofSeconds(10);

    private final Object lock = new Object();
    private Thread worker;

    /**
     * @param importsDir   where uploaded zips are ({@code <data>/tmp/imports}, {@code <uploadId>.zip})
     * @param allowedPaths {@code libraryImport.allowedPaths}
     */
    public LibraryImports(Database db, Uploads uploads, Playlists playlists, Path importsDir, List<Path> allowedPaths,
                          Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.uploads = Objects.requireNonNull(uploads);
        this.playlists = Objects.requireNonNull(playlists);
        this.importsDir = Objects.requireNonNull(importsDir);
        this.allowedPaths = List.copyOf(allowedPaths);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The free-space check ({@code storage.minFreeBytes}): below it the import pauses. */
    public void diskSpace(DiskSpace space) {
        Objects.requireNonNull(space);
        this.diskSpace = () -> space;
    }

    /** Whether a new-item ingest job is queued: the import waits while one is. */
    public void ingestBusy(BooleanSupplier busy) {
        this.ingestBusy = Objects.requireNonNull(busy);
    }

    /** How often a waiting import looks again (tests). */
    void polls(Duration busy, Duration space) {
        this.busyPoll = busy;
        this.spacePoll = space;
    }

    /** Whether importing from a path on the server is on (and where from). */
    public List<Path> allowedPaths() {
        return allowedPaths;
    }

    // ------------------------------------------------------------------ views

    /** One import as the API shows it. */
    public record Import(String id, String userId, String startedByUserId, JsonObject source, boolean smartPlaylists,
                         String state, JsonObject counts, JsonArray problems, String error, long createdAt,
                         long updatedAt, Long startedAt, Long finishedAt) {
    }

    /** An import running for someone (for "Another import is running"). */
    public record Running(String id, String userId, String username, String displayName, String state) {
    }

    private static final String COLUMNS = "id, user_id, started_by_user_id, source_json, smart_playlists, state,"
            + " counts_json, problems_json, error, created_at, updated_at, started_at, finished_at";

    private static Import row(ResultSet rs) throws SQLException {
        long started = rs.getLong(12);
        Long startedAt = rs.wasNull() ? null : started;
        long finished = rs.getLong(13);
        Long finishedAt = rs.wasNull() ? null : finished;
        return new Import(rs.getString(1), rs.getString(2), rs.getString(3),
                JsonParser.parseString(rs.getString(4)).getAsJsonObject(), rs.getInt(5) != 0, rs.getString(6),
                JsonParser.parseString(rs.getString(7)).getAsJsonObject(),
                JsonParser.parseString(rs.getString(8)).getAsJsonArray(), rs.getString(9), rs.getLong(10),
                rs.getLong(11), startedAt, finishedAt);
    }

    private Import byId(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM library_imports WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        });
    }

    /** The user's current or last import, or null. */
    public Import latest(String userId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM library_imports"
                    + " WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        });
    }

    private Import requireLatest(String userId) throws SQLException {
        Import i = latest(userId);
        if (i == null) {
            throw new HttpError(404, "import_not_found", "There is no import for this user");
        }
        return i;
    }

    /** The import running (checking, importing or paused) for another user than {@code userId}, or null. */
    public Running runningForOther(String userId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.user_id, u.username, u.display_name,"
                    + " i.state FROM library_imports i JOIN users u ON u.id = i.user_id"
                    + " WHERE i.state IN ('checking', 'importing', 'paused') AND i.user_id <> ?"
                    + " ORDER BY i.created_at LIMIT 1")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Running(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)) : null;
                }
            }
        });
    }

    /** A page of the problems of the user's last import: {@code [items, nextCursor or null]}. */
    public Object[] problems(String userId, String cursor) throws SQLException {
        Import i = requireLatest(userId);
        int from = 0;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                from = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                throw new HttpError(400, "invalid_cursor", "Unknown cursor");
            }
            if (from < 0) {
                throw new HttpError(400, "invalid_cursor", "Unknown cursor");
            }
        }
        JsonArray all = i.problems();
        JsonArray page = new JsonArray();
        for (int k = from; k < all.size() && k < from + PROBLEMS_PAGE; k++) {
            page.add(all.get(k));
        }
        String next = from + PROBLEMS_PAGE < all.size() ? Integer.toString(from + PROBLEMS_PAGE) : null;
        return new Object[] {page, next};
    }

    // ------------------------------------------------------------------ sources

    /**
     * Resolves a path on the server's disk under {@code libraryImport.allowedPaths}: checked
     * lexically (normalised), then as a real path (symlinks resolved) against the allowed
     * directories' real paths.
     *
     * @throws HttpError {@code 403 path_not_allowed} (also while the option is off),
     *                   {@code 400 invalid_path}, {@code 404 path_not_found}
     */
    public Path resolvePath(String raw) {
        if (allowedPaths.isEmpty()) {
            throw notAllowed();
        }
        Path p;
        try {
            p = raw == null || raw.isBlank() ? null : Path.of(raw.strip());
        } catch (java.nio.file.InvalidPathException e) {
            p = null;
        }
        if (p == null || !p.isAbsolute()) {
            throw new HttpError(400, "invalid_path", "The path must be an absolute path on the server");
        }
        Path norm = p.normalize();
        // lexically under an allowed directory, as configured or as its real path
        boolean under = false;
        for (Path a : allowedPaths) {
            if (norm.startsWith(a.normalize())) {
                under = true;
            } else {
                try {
                    under |= norm.startsWith(a.toRealPath());
                } catch (IOException e) {
                    // a missing allowed directory allows nothing
                }
            }
        }
        if (!under) {
            throw notAllowed();
        }
        Path real;
        try {
            real = norm.toRealPath();
        } catch (IOException e) {
            throw new HttpError(404, "path_not_found", "There is no folder or file at that path");
        }
        for (Path a : allowedPaths) {
            try {
                if (real.startsWith(a.toRealPath())) {
                    return real;
                }
            } catch (IOException e) {
                // an allowed directory that doesn't exist allows nothing
            }
        }
        throw notAllowed();
    }

    private static HttpError notAllowed() {
        return new HttpError(403, "path_not_allowed", "Importing from that path isn't allowed on this server");
    }

    /** {@code <importsDir>/<uploadId>.zip}. */
    Path uploadedZip(String uploadId) {
        if (!UuidV7.isValid(uploadId)) {
            throw new HttpError(400, "invalid_source", "source needs a path or an uploadId");
        }
        Path zip = importsDir.resolve(uploadId + ".zip");
        if (!Files.isRegularFile(zip)) {
            throw new HttpError(404, "upload_not_found", "No such upload");
        }
        return zip;
    }

    private ImportSource openSource(JsonObject source) throws IOException {
        String type = source.get("type").getAsString();
        if (type.equals("path")) {
            return ImportSource.open(resolvePath(source.get("path").getAsString()));
        }
        return ImportSource.open(uploadedZip(source.get("uploadId").getAsString()));
    }

    /** The source as the audit log names it: its type and top folder or file name only. */
    private static Map<String, Object> auditSource(JsonObject source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source.get("type").getAsString());
        if (source.has("path")) {
            Path name = Path.of(source.get("path").getAsString()).getFileName();
            out.put("name", name == null ? "/" : name.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ check

    /** The extension of a path inside the source, lower case, or "". */
    static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Starts an import for {@code userId}: resolves the source, parses {@code Library.xml} and
     * matches the files (the errors are thrown here), stores it as {@code checking} and counts the
     * tracks already in the user's library in the background, then {@code ready}.
     *
     * @param source {@code {"path"}} or {@code {"uploadId"}}
     * @throws HttpError {@code 409 import_running}, {@code 404 user_not_found}, the source's errors,
     *                   {@code 400 no_library_xml} / {@code several_library_xml} /
     *                   {@code invalid_library_xml} / {@code library_xml_too_large}
     */
    public Import check(String actorUserId, String userId, JsonObject source, boolean smartPlaylists)
            throws SQLException, IOException {
        requireUser(userId);
        JsonObject stored = new JsonObject();
        Path resolved;
        if (source != null && source.has("path") && source.get("path").isJsonPrimitive()) {
            resolved = resolvePath(source.get("path").getAsString());
            stored.addProperty("type", "path");
            stored.addProperty("path", resolved.toString());
        } else if (source != null && source.has("uploadId") && source.get("uploadId").isJsonPrimitive()) {
            resolved = uploadedZip(source.get("uploadId").getAsString());
            stored.addProperty("type", "upload");
            stored.addProperty("uploadId", source.get("uploadId").getAsString());
        } else {
            throw new HttpError(400, "invalid_source", "source needs a path or an uploadId");
        }
        synchronized (lock) {
            if (activeId() != null) {
                throw new HttpError(409, "import_running", "Another import is running; try again when it is done");
            }
            String id = UuidV7.newId();
            try (ImportSource src = ImportSource.open(resolved)) {
                Plan plan = plan(src, smartPlaylists);
                long now = EpochMs.now(clock);
                db.write(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET state = 'cancelled',"
                            + " finished_at = ?, updated_at = ? WHERE state = 'ready'")) {
                        ps.setLong(1, now);
                        ps.setLong(2, now);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_imports (id, user_id,"
                            + " started_by_user_id, source_json, smart_playlists, state, counts_json, problems_json,"
                            + " playlists_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'checking', ?, ?, ?, ?, ?)")) {
                        ps.setString(1, id);
                        ps.setString(2, userId);
                        ps.setString(3, actorUserId);
                        ps.setString(4, stored.toString());
                        ps.setInt(5, smartPlaylists ? 1 : 0);
                        ps.setString(6, plan.counts.toString());
                        ps.setString(7, plan.problems.toString());
                        ps.setString(8, plan.playlists.toString());
                        ps.setLong(9, now);
                        ps.setLong(10, now);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_import_tracks (import_id,"
                            + " position, track_id, path, size, details_json, state, reason)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        for (int i = 0; i < plan.tracks.size(); i++) {
                            PlannedTrack t = plan.tracks.get(i);
                            ps.setString(1, id);
                            ps.setInt(2, i);
                            ps.setString(3, t.trackId);
                            ps.setString(4, t.path);
                            if (t.size == null) {
                                ps.setNull(5, java.sql.Types.BIGINT);
                            } else {
                                ps.setLong(5, t.size);
                            }
                            ps.setString(6, t.details.toString());
                            ps.setString(7, t.state);
                            ps.setString(8, t.reason);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    return null;
                });
            }
            spawn(id, false);
            return byId(id);
        }
    }

    /** @throws HttpError {@code 404 user_not_found} */
    void requireUser(String userId) throws SQLException {
        boolean exists = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
        if (!exists) {
            throw new HttpError(404, "user_not_found", "No such user");
        }
    }

    /** The id of the import that is checking, importing or paused, or null. */
    private String activeId() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM library_imports"
                    + " WHERE state IN ('checking', 'importing', 'paused') ORDER BY created_at LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private static final class PlannedTrack {
        String trackId;
        String path;
        Long size;
        JsonObject details;
        String state;
        String reason;
    }

    private record Plan(List<PlannedTrack> tracks, JsonObject counts, JsonArray problems, JsonArray playlists) {
    }

    /** Parses and matches; the tracks' rows, the counts so far, the problems and the playlists to make. */
    private static Plan plan(ImportSource src, boolean smartPlaylists) throws IOException {
        String xmlPath = src.libraryXml();
        LibraryXml xml;
        try (InputStream in = src.open(xmlPath)) {
            xml = LibraryXml.parse(in);
        }
        List<LibraryXml.Track> tracks = new ArrayList<>(xml.tracks().values());
        List<String> paths = new ArrayList<>();
        for (LibraryXml.Track t : tracks) {
            paths.add(t.location());
        }
        List<String> files = new ArrayList<>(src.files().keySet());
        files.remove(xmlPath);
        ImportMatcher.Result match = ImportMatcher.match(paths, files);

        JsonObject counts = new JsonObject();
        JsonObject skipped = new JsonObject();
        JsonArray problems = new JsonArray();
        int problemCount = 0;
        int missing = 0;
        int videos = 0;
        int toImport = 0;
        Set<String> used = new HashSet<>();
        List<PlannedTrack> planned = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            LibraryXml.Track t = tracks.get(i);
            ImportMatcher.Match m = match.matches().get(i);
            PlannedTrack p = new PlannedTrack();
            p.trackId = t.id();
            p.details = details(t);
            if (m.file() == null) {
                p.state = "missing";
                p.reason = m.reason();
                missing++;
            } else {
                used.add(m.file());
                p.path = m.file();
                p.size = src.files().get(m.file());
                String ext = extension(m.file());
                String kind = t.kind() == null ? "" : t.kind().toLowerCase(Locale.ROOT);
                if (PROTECTED.contains(ext) || kind.contains("protected")) {
                    p.state = "skipped";
                    p.reason = "protected";
                } else if (VIDEO.contains(ext) || (kind.contains("video") && !AUDIO.contains(ext))) {
                    p.state = "pending";
                    p.details.addProperty("video", true);
                    videos++;
                } else if (AUDIO.contains(ext)) {
                    p.state = "pending";
                } else {
                    p.state = "skipped";
                    p.reason = "unsupported_format";
                }
                if (p.state.equals("skipped")) {
                    skipped.addProperty(p.reason, skipped.has(p.reason) ? skipped.get(p.reason).getAsInt() + 1 : 1);
                } else {
                    toImport++;
                }
            }
            if (!p.state.equals("pending")) {
                problemCount++;
                if (problems.size() < MAX_PROBLEMS) {
                    problems.add(problem(p.state, p.reason, p.details, p.path));
                }
            }
            planned.add(p);
        }

        // Playlists: names with their folders, the regular ones and (if asked) the smart ones.
        Map<String, LibraryXml.Playlist> byId = new HashMap<>();
        for (LibraryXml.Playlist p : xml.playlists()) {
            if (p.persistentId() != null) {
                byId.put(p.persistentId(), p);
            }
        }
        int regular = 0;
        int smart = 0;
        int folders = 0;
        JsonArray lists = new JsonArray();
        for (LibraryXml.Playlist p : xml.playlists()) {
            if (p.folder()) {
                folders++;
                continue;
            }
            if (p.master() || p.distinguished()) {
                continue;
            }
            if (p.smart()) {
                smart++;
            } else {
                regular++;
            }
            if (p.smart() && !smartPlaylists) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("persistentId", p.persistentId() != null ? p.persistentId() : "name:" + p.name());
            o.addProperty("name", fullName(p, byId));
            o.addProperty("smart", p.smart());
            JsonArray ids = new JsonArray();
            p.trackIds().forEach(ids::add);
            o.add("trackIds", ids);
            lists.add(o);
        }
        JsonObject pl = new JsonObject();
        pl.addProperty("regular", regular);
        pl.addProperty("smart", smart);
        pl.addProperty("folders", folders);

        counts.addProperty("tracks", tracks.size());
        counts.addProperty("matched", match.matched());
        counts.addProperty("missing", missing);
        counts.add("skipped", skipped);
        counts.addProperty("alreadyInLibrary", 0);
        counts.addProperty("videos", videos);
        counts.add("playlists", pl);
        counts.addProperty("unreferencedFiles", files.size() - used.size());
        counts.addProperty("toImport", toImport);
        counts.addProperty("done", 0);
        counts.addProperty("imported", 0);
        counts.addProperty("already", 0);
        counts.addProperty("liked", 0);
        counts.addProperty("playlistsMade", 0);
        counts.addProperty("playlistsUpdated", 0);
        counts.addProperty("problems", problemCount);
        return new Plan(planned, counts, problems, lists);
    }

    /** "Folder / Sub / Name" (outermost folder first). */
    static String fullName(LibraryXml.Playlist p, Map<String, LibraryXml.Playlist> byId) {
        StringBuilder name = new StringBuilder(p.name() == null ? "Untitled playlist" : p.name());
        Set<String> seen = new HashSet<>();
        String parent = p.parentPersistentId();
        while (parent != null && seen.add(parent)) {
            LibraryXml.Playlist f = byId.get(parent);
            if (f == null) {
                break;
            }
            name.insert(0, (f.name() == null ? "Folder" : f.name()) + " / ");
            parent = f.parentPersistentId();
        }
        return name.toString();
    }

    private static JsonObject details(LibraryXml.Track t) {
        JsonObject d = new JsonObject();
        put(d, "title", t.name());
        put(d, "artist", t.artist());
        put(d, "albumArtist", t.albumArtist());
        put(d, "album", t.album());
        put(d, "genre", t.genre());
        put(d, "track", t.track());
        put(d, "trackTotal", t.trackCount());
        put(d, "disc", t.disc());
        put(d, "discTotal", t.discCount());
        put(d, "date", t.date());
        if (t.dateAdded() != null) {
            d.addProperty("dateAdded", t.dateAdded());
        }
        if (t.loved()) {
            d.addProperty("loved", true);
        }
        return d;
    }

    private static void put(JsonObject o, String key, Object v) {
        if (v instanceof String s) {
            o.addProperty(key, s);
        } else if (v instanceof Number n) {
            o.addProperty(key, n);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Integer integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsInt();
    }

    /** A problem: what, why, the track's title and artist, its path inside the source. */
    static JsonObject problem(String kind, String reason, JsonObject details, String path) {
        JsonObject p = new JsonObject();
        p.addProperty("kind", kind);
        if (reason != null) {
            p.addProperty("reason", reason);
        }
        if (details != null) {
            String title = UploadMetadata.clean(str(details, "title"));
            String artist = UploadMetadata.clean(str(details, "artist"));
            if (title != null) {
                p.addProperty("title", title);
            }
            if (artist != null) {
                p.addProperty("artist", artist);
            }
        }
        if (path != null) {
            p.addProperty("path", path);
        }
        return p;
    }

    // ------------------------------------------------------------------ start / cancel

    /**
     * Imports the user's checked import.
     *
     * @throws HttpError {@code 404 import_not_found}, {@code 409 import_not_ready}
     */
    public Import start(String actorUserId, String userId) throws SQLException {
        synchronized (lock) {
            Import i = requireLatest(userId);
            if (!i.state().equals("ready")) {
                throw new HttpError(409, "import_not_ready", "The import isn't checked and waiting to start");
            }
            long now = EpochMs.now(clock);
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET state = 'importing',"
                        + " started_at = ?, updated_at = ?, started_by_user_id = ? WHERE id = ? AND state = 'ready'")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setString(3, actorUserId);
                    ps.setString(4, i.id());
                    ps.executeUpdate();
                }
                Map<String, Object> details = auditSource(i.source());
                details.put("userId", userId);
                details.put("counts", i.counts());
                details.put("smartPlaylists", i.smartPlaylists());
                AuditLog.write(c, now, actorUserId, null, null, AUDIT_STARTED, i.id(), details);
                return null;
            });
            spawn(i.id(), true);
            return byId(i.id());
        }
    }

    /**
     * Cancels the user's import: a check at once, an import after the file it is working on (what
     * is imported stays; no playlists are made).
     *
     * @throws HttpError {@code 404 import_not_found}, {@code 409 import_not_running}
     */
    public Import cancel(String actorUserId, String userId) throws SQLException {
        synchronized (lock) {
            Import i = requireLatest(userId);
            if (!i.state().equals("ready") && !ACTIVE.contains(i.state())) {
                throw new HttpError(409, "import_not_running", "The import has already finished");
            }
            long now = EpochMs.now(clock);
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET state = 'cancelled',"
                        + " finished_at = ?, updated_at = ? WHERE id = ? AND state IN ('checking', 'ready',"
                        + " 'importing', 'paused')")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setString(3, i.id());
                    ps.executeUpdate();
                }
                Map<String, Object> details = auditSource(i.source());
                details.put("userId", userId);
                details.put("counts", i.counts());
                AuditLog.write(c, now, actorUserId, null, null, AUDIT_CANCELLED, i.id(), details);
                return null;
            });
            Thread t = worker;
            if (t != null) {
                LockSupport.unpark(t);
            }
            return byId(i.id());
        }
    }

    // ------------------------------------------------------------------ the worker

    /** Picks up an import a restart interrupted: checks again, or imports on from the next pending track. */
    public void resume() throws SQLException {
        synchronized (lock) {
            String id = activeId();
            if (id == null) {
                return;
            }
            Import i = byId(id);
            spawn(id, !i.state().equals("checking"));
        }
    }

    private void spawn(String id, boolean importing) {
        synchronized (lock) {
            Thread t = Thread.ofPlatform().daemon().name("library-import").unstarted(() -> run(id, importing));
            worker = t;
            t.start();
        }
    }

    /** Waits for the worker to finish (tests). */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        Thread t;
        synchronized (lock) {
            t = worker;
        }
        if (t == null) {
            return true;
        }
        t.join(timeout);
        return !t.isAlive();
    }

    /** Stops the worker without changing the import's state, as a restart would (tests). */
    void stopWorker() throws InterruptedException {
        Thread t;
        synchronized (lock) {
            t = worker;
        }
        if (t != null) {
            t.interrupt();
            t.join(10_000);
        }
    }

    private void run(String id, boolean importing) {
        try {
            if (importing) {
                importAll(id);
            } else {
                checkLibrary(id);
            }
        } catch (InterruptedException e) {
            // stopped (shutdown or a test's restart): the state stays for resume
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Library import " + id + " failed", e);
            String message = e instanceof HttpError h ? h.getMessage()
                    : e instanceof IOException ? "The source can't be read" : "The import failed";
            try {
                finish(id, "failed", message, null);
            } catch (SQLException ex) {
                LOG.log(System.Logger.Level.WARNING, "Library import " + id + ": couldn't record the failure", ex);
            }
        }
    }

    private String state(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state FROM library_imports WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Sets a new state from an active one (never over a cancel); audits {@code done}. */
    private boolean finish(String id, String state, String error, JsonObject counts) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET state = ?, error = ?,"
                    + " updated_at = ?, finished_at = ? WHERE id = ? AND state IN ('checking', 'importing', 'paused')")) {
                ps.setString(1, state);
                ps.setString(2, error);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setString(5, id);
                n = ps.executeUpdate();
            }
            if (n > 0 && state.equals("done")) {
                Import i = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM library_imports WHERE id = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            i = row(rs);
                        }
                    }
                }
                if (i != null) {
                    Map<String, Object> details = auditSource(i.source());
                    details.put("userId", i.userId());
                    details.put("counts", counts != null ? counts : i.counts());
                    AuditLog.write(c, now, i.startedByUserId(), null, null, AUDIT_FINISHED, id, details);
                }
            }
            return n > 0;
        });
    }

    /** The check's background part: the tracks already in the user's library, then {@code ready}. */
    private void checkLibrary(String id) throws Exception {
        Import imp = byId(id);
        Map<String, String> library = libraryContentIds(imp.userId());
        Set<Long> sizes = new HashSet<>();
        for (String cid : library.keySet()) {
            sizes.add(ContentId.sizeOf(cid));
        }
        int already = 0;
        try (ImportSource src = openSource(imp.source())) {
            for (TrackRow t : tracks(id)) {
                if (!t.state.equals("pending") && !t.state.equals("already")) {
                    continue;
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                if (!"checking".equals(state(id))) {
                    return; // cancelled
                }
                if (t.size == null || !sizes.contains(t.size)) {
                    continue;
                }
                String cid;
                try (InputStream in = src.open(t.path)) {
                    cid = ContentId.of(in);
                } catch (IOException e) {
                    continue; // the import tries it again and reports it
                }
                String itemId = cid == null ? null : library.get(cid);
                String cidFinal = cid;
                db.write(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE library_import_tracks SET content_id = ?,"
                            + " state = ?, item_id = ? WHERE import_id = ? AND position = ?")) {
                        ps.setString(1, cidFinal);
                        ps.setString(2, itemId != null ? "already" : "pending");
                        ps.setString(3, itemId);
                        ps.setString(4, id);
                        ps.setInt(5, t.position);
                        ps.executeUpdate();
                    }
                    return null;
                });
                if (itemId != null) {
                    already++;
                }
            }
        }
        JsonObject counts = byId(id).counts();
        counts.addProperty("alreadyInLibrary", already);
        long now = EpochMs.now(clock);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET state = 'ready', counts_json = ?,"
                    + " updated_at = ? WHERE id = ? AND state = 'checking'")) {
                ps.setString(1, counts.toString());
                ps.setLong(2, now);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The user's library items that have a content ID: content ID → item id. */
    private Map<String, String> libraryContentIds(String userId) throws SQLException {
        return db.read(c -> {
            Map<String, String> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT i.content_id, i.id FROM library_entries e"
                    + " JOIN items i ON i.id = e.item_id WHERE e.user_id = ? AND i.content_id IS NOT NULL"
                    + " AND i.status IN ('ready', 'pending', 'processing')")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    private static final class TrackRow {
        int position;
        String trackId;
        String path;
        Long size;
        String contentId;
        JsonObject details;
        String state;
        String itemId;
    }

    private List<TrackRow> tracks(String id) throws SQLException {
        return db.read(c -> {
            List<TrackRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT position, track_id, path, size, content_id,"
                    + " details_json, state, item_id FROM library_import_tracks WHERE import_id = ? ORDER BY position")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TrackRow t = new TrackRow();
                        t.position = rs.getInt(1);
                        t.trackId = rs.getString(2);
                        t.path = rs.getString(3);
                        long size = rs.getLong(4);
                        t.size = rs.wasNull() ? null : size;
                        t.contentId = rs.getString(5);
                        t.details = JsonParser.parseString(rs.getString(6)).getAsJsonObject();
                        t.state = rs.getString(7);
                        t.itemId = rs.getString(8);
                        out.add(t);
                    }
                }
            }
            return out;
        });
    }

    /** The running import's counts and problems, saved after every file. */
    private final class Progress {
        final String id;
        final JsonObject counts;
        final JsonArray problems;

        Progress(Import i) {
            this.id = i.id();
            this.counts = i.counts();
            this.problems = i.problems();
        }

        void add(String key, int n) {
            counts.addProperty(key, counts.get(key).getAsInt() + n);
        }

        void problem(JsonObject p) {
            add("problems", 1);
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(p);
            }
        }

        void skipped(String reason) {
            JsonObject s = counts.getAsJsonObject("skipped");
            s.addProperty(reason, s.has(reason) ? s.get(reason).getAsInt() + 1 : 1);
        }

        void save(Connection c) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET counts_json = ?,"
                    + " problems_json = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, counts.toString());
                ps.setString(2, problems.toString());
                ps.setLong(3, EpochMs.now(clock));
                ps.setString(4, id);
                ps.executeUpdate();
            }
        }
    }

    private boolean setState(String id, String from, String to) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE library_imports SET state = ?, updated_at = ? WHERE id = ? AND state = ?")) {
                ps.setString(1, to);
                ps.setLong(2, now);
                ps.setString(3, id);
                ps.setString(4, from);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Whether the import is still going (importing or paused); false once cancelled. */
    private boolean going(String id) throws SQLException {
        String s = state(id);
        return "importing".equals(s) || "paused".equals(s);
    }

    private void sleep(Duration d) throws InterruptedException {
        LockSupport.parkNanos(d.toNanos());
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    private void importAll(String id) throws Exception {
        Import imp = byId(id);
        Progress progress = new Progress(imp);
        String userId = imp.userId();
        Actor.User actor = new Actor.User(userId, SESSION, Actor.Via.BEARER);
        try (ImportSource src = openSource(imp.source())) {
            for (TrackRow t : tracks(id)) {
                if (!t.state.equals("pending") && !t.state.equals("already")) {
                    continue;
                }
                // People's uploads first: wait while a new-item ingest job is queued.
                while (ingestBusy.getAsBoolean()) {
                    if (!going(id)) {
                        return;
                    }
                    sleep(busyPoll);
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                if (!going(id)) {
                    return; // cancelled
                }
                importTrack(id, src, actor, t, progress);
            }
            if (!going(id)) {
                return;
            }
            makePlaylists(id, userId, imp.smartPlaylists(), progress);
            db.write(c -> {
                progress.save(c);
                return null;
            });
            finish(id, "done", null, progress.counts);
        }
    }

    /** Waits until there is room for {@code size} more bytes (paused meanwhile); false if cancelled. */
    private boolean awaitRoom(String id, long size) throws Exception {
        while (true) {
            try {
                diskSpace.get().requireRoomFor(size);
                if ("paused".equals(state(id))) {
                    setState(id, "paused", "importing");
                }
                return true;
            } catch (HttpError e) {
                if (e.status() != 507) {
                    throw e;
                }
            }
            if (!going(id)) {
                return false;
            }
            setState(id, "importing", "paused");
            sleep(spacePoll);
        }
    }

    private void importTrack(String id, ImportSource src, Actor.User actor, TrackRow t, Progress progress)
            throws Exception {
        String userId = actor.userId();
        String itemId = null;
        String state;
        String reason = null;
        boolean madeEntry = false;
        // Already in the library (by the check's content ID): no copy needed.
        itemId = libraryItem(userId, t.contentId);
        if (itemId != null) {
            state = "already";
        } else {
            long size = t.size == null ? 0 : t.size;
            String filename = t.path.substring(t.path.lastIndexOf('/') + 1);
            Uploaded done = null;
            while (done == null && reason == null) {
                if (!awaitRoom(id, size)) {
                    return;
                }
                try {
                    done = upload(src, actor, t, filename, size);
                } catch (HttpError e) {
                    switch (e.code()) {
                        case DiskSpace.ERROR_CODE -> {
                            // the space went while copying: wait and try again
                        }
                        case "payload_too_large" -> reason = "too_large";
                        case "too_long" -> reason = "too_long";
                        case "unreadable_media", "no_audio", "upload_incomplete", "invalid_size" -> reason = "unreadable";
                        default -> throw e;
                    }
                } catch (IOException e) {
                    reason = "unreadable";
                }
            }
            if (reason != null) {
                state = "skipped";
            } else {
                itemId = done.done().itemId();
                madeEntry = done.done().addedToLibrary() && !done.inLibraryBefore();
                state = madeEntry ? "imported" : "already";
            }
        }
        if (itemId != null && Boolean.TRUE.equals(bool(t.details, "loved"))) {
            try {
                if (playlists.like(actor, itemId, Classify.Classification.LEAVE, () -> {
                }).created()) {
                    progress.add("liked", 1);
                }
            } catch (HttpError e) {
                progress.problem(problem("liked", e.code(), t.details, t.path));
            }
        }
        String fItem = itemId;
        String fState = state;
        String fReason = reason;
        boolean fMade = madeEntry;
        Long dateAdded = t.details.has("dateAdded") ? t.details.get("dateAdded").getAsLong() : null;
        progress.add("done", 1);
        switch (state) {
            case "imported" -> progress.add("imported", 1);
            case "already" -> progress.add("already", 1);
            default -> {
                progress.skipped(reason);
                progress.problem(problem("skipped", reason, t.details, t.path));
            }
        }
        db.write(c -> {
            if (fMade && dateAdded != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE library_entries SET added_at = ? WHERE user_id = ? AND item_id = ?")) {
                    ps.setLong(1, dateAdded);
                    ps.setString(2, userId);
                    ps.setString(3, fItem);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE library_import_tracks SET state = ?, reason = ?,"
                    + " item_id = ? WHERE import_id = ? AND position = ?")) {
                ps.setString(1, fState);
                ps.setString(2, fReason);
                ps.setString(3, fItem);
                ps.setString(4, id);
                ps.setInt(5, t.position);
                ps.executeUpdate();
            }
            progress.save(c);
            return null;
        });
    }

    private static Boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    /** The item in the user's library with this content ID, or null. */
    private String libraryItem(String userId, String contentId) throws SQLException {
        if (contentId == null) {
            return null;
        }
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT i.id FROM library_entries e JOIN items i"
                    + " ON i.id = e.item_id WHERE e.user_id = ? AND i.content_id = ?"
                    + " AND i.status IN ('ready', 'pending', 'processing') ORDER BY e.added_at LIMIT 1")) {
                ps.setString(1, userId);
                ps.setString(2, contentId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** A completed upload, and whether its item was in the user's library before. */
    private record Uploaded(Uploads.Completed done, boolean inLibraryBefore) {
    }

    /** Copies one file into a new upload of the user and completes it. */
    private Uploaded upload(ImportSource src, Actor.User actor, TrackRow t, String filename, long size)
            throws Exception {
        Uploads.Upload upload = uploads.create(actor, filename, size);
        Path part = uploads.partFile(upload.id());
        ContentId contentId = new ContentId();
        try (InputStream in = src.open(t.path);
             OutputStream out = Files.newOutputStream(part, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[256 * 1024];
            long copied = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                copied += n;
                if (copied > size) {
                    break; // more than the listing said: refused as incomplete below
                }
                out.write(buffer, 0, n);
                contentId.update(buffer, 0, n);
            }
        } catch (NoSuchFileException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        boolean before = libraryItem(actor.userId(), contentId.value()) != null;
        JsonObject d = t.details;
        // A video stays uncategorized (LEAVE); an audio file ignores the classification.
        return new Uploaded(uploads.complete(actor, upload.id(), null, null, true, Classify.Classification.LEAVE,
                meta -> withDetails(meta, d)), before);
    }

    /**
     * The metadata of a new item with the export's details over the tags' (each cleaned and
     * checked like an edit; a value that doesn't fit is dropped).
     */
    static UploadMetadata withDetails(UploadMetadata meta, JsonObject d) {
        String title = UploadMetadata.clean(str(d, "title"));
        String artist = UploadMetadata.clean(str(d, "artist"));
        String album = UploadMetadata.clean(str(d, "album"));
        SongDetails details = meta.details();
        if (artist != null && details.artists() != null && !artist.equals(SongDetails.joinNames(details.artists()))) {
            details = details.with("artists", null);
        }
        String albumArtist = UploadMetadata.clean(str(d, "albumArtist"));
        if (albumArtist != null) {
            details = details.with("albumArtist", albumArtist);
        }
        String genre = UploadMetadata.clean(str(d, "genre"));
        if (genre != null) {
            details = details.with("genre", genre);
        }
        for (String f : List.of("track", "trackTotal", "disc", "discTotal")) {
            Integer v = integer(d, f);
            if (v != null && v >= 1 && v <= SongDetails.MAX_NUMBER) {
                details = details.with(f, v);
            }
        }
        String date = SongDetails.date(str(d, "date"));
        if (date != null) {
            details = details.with("date", date);
        }
        return new UploadMetadata(title != null ? title : meta.title(), artist != null ? artist : meta.artist(),
                album != null ? album : meta.album(), details);
    }

    /** Makes (or refills) the playlists after the last file. */
    private void makePlaylists(String id, String userId, boolean smartPlaylists, Progress progress)
            throws SQLException {
        JsonArray lists = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT playlists_json FROM library_imports WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return JsonParser.parseString(rs.getString(1)).getAsJsonArray();
                }
            }
        });
        Map<String, String> items = new HashMap<>();
        for (TrackRow t : tracks(id)) {
            if (t.itemId != null && (t.state.equals("imported") || t.state.equals("already"))) {
                items.put(t.trackId, t.itemId);
            }
        }
        for (JsonElement e : lists) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("smart").getAsBoolean() && !smartPlaylists) {
                continue;
            }
            String name = p.get("name").getAsString();
            List<String> ids = new ArrayList<>();
            for (JsonElement t : p.getAsJsonArray("trackIds")) {
                String itemId = items.get(t.getAsString());
                if (itemId != null) {
                    ids.add(itemId);
                }
            }
            JsonObject who = new JsonObject();
            try {
                Playlists.Imported done = playlists.importPlaylist(userId, p.get("persistentId").getAsString(),
                        name, ids);
                progress.add(done.created() ? "playlistsMade" : "playlistsUpdated", 1);
                if (done.truncated()) {
                    progress.problem(playlistProblem("too_many_entries", name));
                }
            } catch (HttpError err) {
                progress.problem(playlistProblem(err.code(), name));
            }
        }
    }

    private static JsonObject playlistProblem(String reason, String name) {
        JsonObject p = new JsonObject();
        p.addProperty("kind", "playlist");
        p.addProperty("reason", reason);
        String clean = UploadMetadata.clean(name);
        if (clean != null) {
            p.addProperty("playlist", clean);
        }
        return p;
    }

    private static final class LockSupport {
        static void parkNanos(long nanos) {
            java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        }

        static void unpark(Thread t) {
            java.util.concurrent.locks.LockSupport.unpark(t);
        }
    }
}
