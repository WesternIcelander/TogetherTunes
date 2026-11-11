package io.siggi.togethertunes.fonts;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Fetches one manifest font ({@link FontManifest.Entry}) with the JDK HTTP client: only the
 * entry's own URL (redirects are not followed), at most the entry's size, and the file is used
 * only if its SHA-256 matches and Java can load it as a font. The font goes to
 * {@code <dir>/<file>} with its licence text next to it.
 */
public final class FontDownloader {
    private final HttpClient client;
    private final Duration timeout;

    public FontDownloader() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build(), Duration.ofMinutes(10));
    }

    /** With {@code client} (must not follow redirects) and a whole-request timeout. */
    public FontDownloader(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("the font download client must not follow redirects");
        }
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * Downloads {@code entry} into {@code dir} (created if missing) and returns the font file.
     *
     * @throws IOException with a short reason (HTTP status, too big, hash mismatch, not a font);
     *                     nothing is left behind then
     */
    public Path download(FontManifest.Entry entry, Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        HttpRequest request = HttpRequest.newBuilder(entry.url()).timeout(timeout).GET().build();
        Path tmp = Files.createTempFile(dir, "." + entry.id() + "-", ".part");
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + entry.url().getHost());
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > entry.size()) {
                    throw new IOException("the server sends " + declared + " bytes; expected " + entry.size());
                }
                MessageDigest sha = sha256();
                long total = 0;
                byte[] buf = new byte[65536];
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        total += n;
                        if (total > entry.size()) {
                            throw new IOException("more than the expected " + entry.size() + " bytes");
                        }
                        sha.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                if (total != entry.size()) {
                    throw new IOException("got " + total + " bytes; expected " + entry.size());
                }
                if (!MessageDigest.isEqual(sha.digest(), entry.sha256Bytes())) {
                    throw new IOException("the SHA-256 doesn't match the manifest");
                }
            }
            loadFont(tmp);
            Path target = dir.resolve(entry.file());
            move(tmp, target);
            writeLicence(entry, dir);
            return target;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Loads a font file (TrueType or CFF-based OpenType). */
    static Font loadFont(Path file) throws IOException {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file.toFile());
        } catch (FontFormatException e) {
            throw new IOException("not a usable font: " + e.getMessage(), e);
        }
    }

    /** Whether {@code file} has {@code entry}'s size and SHA-256 (checked again at startup before use). */
    static boolean matches(FontManifest.Entry entry, Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != entry.size()) {
                return false;
            }
            MessageDigest sha = sha256();
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    sha.update(buf, 0, n);
                }
            }
            return MessageDigest.isEqual(sha.digest(), entry.sha256Bytes());
        } catch (IOException e) {
            return false;
        }
    }

    /** Writes the entry's licence text (from the jar) to {@code dir}, if it isn't there yet. */
    static void writeLicence(FontManifest.Entry entry, Path dir) throws IOException {
        Path target = dir.resolve(entry.licence());
        if (Files.isRegularFile(target)) {
            return;
        }
        try (InputStream in = FontDownloader.class.getResourceAsStream("licenses/" + entry.licence())) {
            if (in == null) {
                throw new IOException("licence " + entry.licence() + " missing from the jar");
            }
            Path tmp = Files.createTempFile(dir, ".licence-", ".txt");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
