package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.secrets.SecretDecryptionException;
import java.io.PrintStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Converts RTMP stream keys stored encrypted by older versions ({@code stream_outputs.rtmp_key_enc},
 * AES-GCM with the context {@code stream_outputs.rtmp_key_enc:<id>}) into the plain {@code
 * rtmp_key} column added by migration 0007. Runs at every start right after the migrations; it
 * only touches rows that still have an {@code rtmp_key_enc}, so after the first run it does
 * nothing. A key that can't be decrypted (another server secret) is dropped with a warning that
 * names the output, never the key (invariant 6); the owner enters it again.
 */
public final class LegacyRtmpKeys {
    private LegacyRtmpKeys() {
    }

    /** The result: keys converted and keys dropped. */
    public record Result(int converted, int dropped) {
    }

    static String context(String outputId) {
        return "stream_outputs.rtmp_key_enc:" + outputId;
    }

    /**
     * Converts every {@code rtmp_key_enc} into {@code rtmp_key} and clears {@code rtmp_key_enc},
     * in one transaction.
     *
     * @param warnings where to warn about keys that can't be decrypted
     */
    public static Result convert(Database db, SecretCipher cipher, PrintStream warnings) throws SQLException {
        Objects.requireNonNull(cipher, "cipher");
        return db.write(c -> {
            int converted = 0;
            int dropped = 0;
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT id, room_id, rtmp_key_enc FROM stream_outputs WHERE rtmp_key_enc IS NOT NULL");
                 PreparedStatement update = c.prepareStatement(
                         "UPDATE stream_outputs SET rtmp_key = ?, rtmp_key_enc = NULL WHERE id = ?");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String key;
                    try {
                        key = cipher.decrypt(rs.getString(3), context(id));
                    } catch (SecretDecryptionException e) {
                        key = null;
                    }
                    if (key == null) {
                        dropped++;
                        warnings.println("WARNING: the RTMP stream key of output " + id + " (room " + rs.getString(2)
                                + ") can't be decrypted (was the server's secret key changed?) and was removed."
                                + " The room owner has to enter it again.");
                    } else {
                        converted++;
                    }
                    update.setString(1, key);
                    update.setString(2, id);
                    update.addBatch();
                }
                if (converted + dropped > 0) {
                    update.executeBatch();
                }
            }
            return new Result(converted, dropped);
        });
    }
}
