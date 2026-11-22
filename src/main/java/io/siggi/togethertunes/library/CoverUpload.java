package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

/**
 * <b>Replace artwork</b> on the item page ({@code docs/library-and-ingest.md#cards}): a signed-in
 * user who may edit the item's details uploads an image (JPEG, PNG or WebP, at most
 * {@link #MAX_BYTES}) as the body of {@code PUT /api/items/{id}/cover}. It is decoded and scaled
 * server-side like every {@code cover.jpg} ({@link IngestPipeline#stagePendingCover}) and applied by
 * the card-only re-encode the lookup's cover uses ({@link IngestJobs#enqueueCover}); audited
 * {@code item.cover_changed} with {@code via: upload}. Not tied to the online lookup feature.
 */
public final class CoverUpload {
    private static final System.Logger LOG = System.getLogger(CoverUpload.class.getName());

    /** The largest image accepted. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final Items items;
    private final Tools tools;
    private final DataDir dataDir;
    private final IngestJobs jobs;

    public CoverUpload(Items items, Tools tools, DataDir dataDir, IngestJobs jobs) {
        this.items = Objects.requireNonNull(items);
        this.tools = Objects.requireNonNull(tools);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.jobs = Objects.requireNonNull(jobs);
    }

    /**
     * Takes the request body as the item's new cover and queues the card remake.
     *
     * @throws HttpError {@code 401 not_authenticated} (guests, anonymous), {@code 404 item_not_found},
     *                   {@code 403 forbidden}, {@code 409 item_not_ready}, {@code 400 invalid_image}
     *                   (empty, not a JPEG/PNG/WebP, or it doesn't decode), {@code 413} (too large),
     *                   {@code 409 item_busy}
     */
    public void replace(Actor actor, String itemId, Items.Rights rights, Request request, String clientIp)
            throws SQLException, IOException, InterruptedException {
        if (!(actor instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        String status = items.requireEdit(user, itemId, rights);
        if (!"ready".equals(status)) {
            throw new HttpError(409, "item_not_ready", "The item isn't ready yet");
        }
        Files.createDirectories(dataDir.tmp());
        Path tmp = Files.createTempFile(dataDir.tmp(), "cover-upload-", ".part");
        Path image = null;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                request.copyBody(out, MAX_BYTES);
            }
            String ext = format(tmp);
            if (ext == null) {
                throw new HttpError(400, "invalid_image", "Send a JPEG, PNG or WebP image as the request body");
            }
            // named by its format, so the decoder never has to guess from the name
            image = tmp.resolveSibling(tmp.getFileName().toString().replace(".part", "." + ext));
            Files.move(tmp, image);
            try {
                IngestPipeline.stagePendingCover(tools, dataDir, itemId, image);
            } catch (ToolException | IOException e) {
                LOG.log(System.Logger.Level.INFO, "uploaded cover for " + itemId + " not usable: " + e.getMessage());
                throw new HttpError(400, "invalid_image", "The image couldn't be read");
            }
            try {
                jobs.enqueueCover(itemId);
            } catch (IngestJobs.ItemBusy e) {
                try {
                    Files.deleteIfExists(IngestPipeline.pendingCover(dataDir, itemId));
                } catch (IOException ignored) {
                    // a leftover is used by the item's next re-encode
                }
                throw new HttpError(409, "item_busy", e.getMessage());
            }
            items.auditCover(user.userId(), itemId, clientIp, "upload");
        } finally {
            Files.deleteIfExists(tmp);
            if (image != null) {
                Files.deleteIfExists(image);
            }
        }
    }

    /** {@code jpg}, {@code png} or {@code webp} by the file's first bytes; null for anything else. */
    static String format(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(12);
        }
        if (startsWith(head, JPEG)) {
            return "jpg";
        }
        if (startsWith(head, PNG)) {
            return "png";
        }
        if (head.length == 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
