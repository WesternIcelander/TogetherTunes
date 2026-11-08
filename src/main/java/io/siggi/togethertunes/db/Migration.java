package io.siggi.togethertunes.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One migration file, {@code NNNN_name.sql}: a version (the number), a name and the SQL.
 *
 * <p>The checksum is the SHA-256 of the SQL with line endings normalised to {@code \n} (so a
 * checkout with CRLF line endings doesn't look like an edited file). It's recorded in {@code
 * schema_migrations} when the migration is applied; an applied migration whose file changed
 * afterwards stops startup.
 *
 * @param version  the number from the file name, at least 1
 * @param name     the part after the underscore, without {@code .sql}
 * @param sql      the file content
 * @param checksum lower-case hex SHA-256 of the normalised content
 */
public record Migration(int version, String name, String sql, String checksum) {
    /** {@code NNNN_name.sql}: four or more digits, an underscore, lower-case words. */
    static final Pattern FILE_NAME = Pattern.compile("([0-9]{4,9})_([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");

    public Migration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(checksum, "checksum");
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be at least 1: " + version);
        }
    }

    /** Builds a migration from its file name ({@code NNNN_name.sql}) and content. */
    public static Migration of(String fileName, String sql) {
        Matcher m = FILE_NAME.matcher(fileName);
        if (!m.matches()) {
            throw new IllegalArgumentException("Migration file name " + fileName
                    + " doesn't match NNNN_name.sql (digits, underscore, lower-case letters/digits/underscores)");
        }
        return new Migration(Integer.parseInt(m.group(1)), m.group(2), sql, checksum(sql));
    }

    /** The file name this migration came from, e.g. {@code 0001_initial.sql}. */
    public String fileName() {
        return String.format("%04d_%s.sql", version, name);
    }

    static String checksum(String sql) {
        String normalised = sql.replace("\r\n", "\n").replace('\r', '\n');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return fileName();
    }
}
