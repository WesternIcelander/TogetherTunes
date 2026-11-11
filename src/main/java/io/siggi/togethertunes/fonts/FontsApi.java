package io.siggi.togethertunes.fonts;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The admin API for card fonts ({@code docs/api.md#fonts}, {@code docs/library-and-ingest.md#card-fonts}),
 * all with {@code admin.settings}, {@code Cache-Control: no-store}, changes audited:
 * <ul>
 *   <li>{@code GET /api/admin/fonts} → {@code {"cardFont", "autoDownload", "cjkVariant",
 *       "fonts": [{"id", "name", "scripts", "size", "status", "error"?, "retryAt"?}], "totalBytes",
 *       "missingBytes", "choices": {"bundled": {"value", "label"}, "system": [family...],
 *       "uploads": [{"id", "family", "size", "value"}]}, "uncovered": [{"char", "codePoint",
 *       "script", "count", "font"?}]}}. The settings themselves are changed with
 *       {@code PATCH /api/admin/settings} ({@code cards.font}, {@code fonts.autoDownload},
 *       {@code fonts.cjkVariant}).</li>
 *   <li>{@code POST /api/admin/fonts/scripts/{id}/download} → {@code 202} the font's entry (queued).</li>
 *   <li>{@code DELETE /api/admin/fonts/scripts/{id}} → {@code 204}; {@code 409 font_busy} while downloading.</li>
 *   <li>{@code POST /api/admin/fonts/download-all} → {@code 202 {"queuedBytes"}}.</li>
 *   <li>{@code POST /api/admin/fonts/uploads} (the font file as the body, at most
 *       {@link FontLibrary#MAX_UPLOAD_BYTES}) → {@code 201} the upload; {@code 400 invalid_font},
 *       {@code 413}.</li>
 *   <li>{@code DELETE /api/admin/fonts/uploads/{id}} → {@code 204}; {@code 409 font_in_use} while it
 *       is the card font.</li>
 * </ul>
 */
public final class FontsApi {
    public static final String AUDIT_DOWNLOAD = "admin.font_download";
    public static final String AUDIT_REMOVED = "admin.font_removed";
    public static final String AUDIT_UPLOADED = "admin.font_uploaded";

    private final FontLibrary library;
    private final Settings settings;
    private final Permissions permissions;
    private final Database db;
    private final FontLibrary.Texts texts;
    private final Path tmpDir;
    private final Clock clock;

    /**
     * @param texts  the item texts for the uncovered-characters list
     * @param tmpDir where an upload is received before it is checked
     */
    public FontsApi(FontLibrary library, Settings settings, Permissions permissions, Database db,
                    FontLibrary.Texts texts, Path tmpDir, Clock clock) {
        this.library = Objects.requireNonNull(library);
        this.settings = Objects.requireNonNull(settings);
        this.permissions = Objects.requireNonNull(permissions);
        this.db = Objects.requireNonNull(db);
        this.texts = Objects.requireNonNull(texts);
        this.tmpDir = Objects.requireNonNull(tmpDir);
        this.clock = Objects.requireNonNull(clock);
    }

    public void register(Router router) {
        var perm = permissions.hook(Permission.ADMIN_SETTINGS);
        router.get("/api/admin/fonts", this::get, perm);
        router.post("/api/admin/fonts/scripts/{id}/download", this::download, perm);
        router.delete("/api/admin/fonts/scripts/{id}", this::remove, perm);
        router.post("/api/admin/fonts/download-all", this::downloadAll, perm);
        router.post("/api/admin/fonts/uploads", this::upload, perm);
        router.delete("/api/admin/fonts/uploads/{id}", this::removeUpload, perm);
    }

    private void get(Request request, Response response) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        String cardFont = settings.cardsFont();
        out.put("cardFont", cardFont == null ? "bundled" : cardFont);
        out.put("autoDownload", settings.fontsAutoDownload());
        out.put("cjkVariant", settings.fontsCjkVariant().wireName());
        List<Map<String, Object>> fonts = new ArrayList<>();
        long missing = 0;
        for (FontLibrary.FontStatus s : library.status()) {
            fonts.add(json(s));
            if (s.state() != FontLibrary.State.INSTALLED) {
                missing += s.entry().size();
            }
        }
        out.put("fonts", fonts);
        out.put("totalBytes", library.manifest().totalBytes());
        out.put("missingBytes", missing);
        Map<String, Object> choices = new LinkedHashMap<>();
        choices.put("bundled", Map.of("value", "bundled", "label", BundledFonts.LABEL));
        choices.put("system", library.system().families());
        List<Map<String, Object>> uploads = new ArrayList<>();
        for (FontLibrary.Upload u : library.uploads()) {
            uploads.add(json(u));
        }
        choices.put("uploads", uploads);
        out.put("choices", choices);
        List<Map<String, Object>> uncovered = new ArrayList<>();
        for (FontLibrary.Uncovered u : library.uncovered(texts)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("char", new String(Character.toChars(u.codePoint())));
            m.put("codePoint", String.format("U+%04X", u.codePoint()));
            m.put("script", u.script());
            m.put("count", u.count());
            if (u.fontId() != null) {
                m.put("font", u.fontId());
            }
            uncovered.add(m);
        }
        out.put("uncovered", uncovered);
        send(response, 200, out);
    }

    private static Map<String, Object> json(FontLibrary.FontStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        FontManifest.Entry e = s.entry();
        m.put("id", e.id());
        m.put("name", e.name());
        List<String> scripts = new ArrayList<>();
        for (Character.UnicodeScript script : e.scripts()) {
            scripts.add(script.name());
        }
        if (scripts.isEmpty()) {
            scripts.add(e.id().equals("emoji") ? "EMOJI" : "SYMBOLS");
        }
        m.put("scripts", scripts);
        if (e.cjk() != null) {
            m.put("cjkVariant", e.cjk().wireName());
        }
        m.put("size", e.size());
        m.put("status", s.state().wireName());
        if (s.error() != null) {
            m.put("error", s.error());
        }
        if (s.retryAtMs() != null) {
            m.put("retryAt", s.retryAtMs());
        }
        return m;
    }

    private static Map<String, Object> json(FontLibrary.Upload u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("family", u.family());
        m.put("size", u.size());
        m.put("value", "upload:" + u.id());
        return m;
    }

    private void download(Request request, Response response) throws Exception {
        String id = request.pathParam("id");
        if (!library.download(id, true)) {
            throw HttpError.notFound();
        }
        audit(request, AUDIT_DOWNLOAD, id, null);
        send(response, 202, json(library.status(id)));
    }

    private void downloadAll(Request request, Response response) throws Exception {
        long bytes = library.downloadAll();
        audit(request, AUDIT_DOWNLOAD, "all", Map.of("queuedBytes", bytes));
        send(response, 202, Map.of("queuedBytes", bytes));
    }

    private void remove(Request request, Response response) throws Exception {
        String id = request.pathParam("id");
        try {
            if (!library.remove(id)) {
                throw HttpError.notFound();
            }
        } catch (IllegalStateException e) {
            throw new HttpError(409, "font_busy", e.getMessage());
        }
        audit(request, AUDIT_REMOVED, id, null);
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void upload(Request request, Response response) throws Exception {
        Files.createDirectories(tmpDir);
        Path tmp = Files.createTempFile(tmpDir, "font-upload-", ".part");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                request.copyBody(out, FontLibrary.MAX_UPLOAD_BYTES);
            }
            FontLibrary.Upload upload;
            try (InputStream in = Files.newInputStream(tmp)) {
                upload = library.addUpload(in);
            } catch (IllegalArgumentException e) {
                throw new HttpError(400, "invalid_font", e.getMessage());
            }
            audit(request, AUDIT_UPLOADED, upload.id(), Map.of("family", upload.family(), "size", upload.size()));
            send(response, 201, json(upload));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void removeUpload(Request request, Response response) throws Exception {
        String id = request.pathParam("id");
        try {
            if (!library.removeUpload(id)) {
                throw HttpError.notFound();
            }
        } catch (IllegalStateException e) {
            throw new HttpError(409, "font_in_use", e.getMessage());
        }
        audit(request, AUDIT_REMOVED, "upload:" + id, null);
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void audit(Request request, String action, String target, Map<String, ?> details) throws Exception {
        Actor.User user = (Actor.User) ActorResolver.actor(request); // the permission hook let only users through
        db.write(c -> {
            AuditLog.write(c, EpochMs.now(clock), user.userId(), null, request.clientIp(), action, target, details);
            return null;
        });
    }

    private static void send(Response response, int status, Object body) throws IOException {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }
}
