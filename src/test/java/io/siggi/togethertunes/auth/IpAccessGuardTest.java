package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Invariant 5: only {@link IpAccess} reads IP columns ({@code sessions.ip}, {@code guests.ip_last},
 * {@code audit_log.ip}). Scans the main sources for SQL text that selects one: every run of
 * string literals joined with {@code +} that contains {@code SELECT} must not name {@code ip} or
 * {@code ip_last}, except in {@code IpAccess.java}. Writes ({@code INSERT}, {@code UPDATE ... SET})
 * are allowed anywhere.
 */
class IpAccessGuardTest {
    private static final Path MAIN = Path.of("src/main/java");
    /** One or more string literals joined by {@code +}. */
    private static final Pattern LITERAL_RUN =
            Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\\\n]|\\\\.)*\")*");
    private static final Pattern SELECT = Pattern.compile("(?i)\\bSELECT\\b");
    private static final Pattern IP_COLUMN = Pattern.compile("(?i)(?<![\\w.])(?:\\w+\\.)?(ip|ip_last)\\b");

    static List<String> violations(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = LITERAL_RUN.matcher(source);
        while (m.find()) {
            String sql = m.group();
            if (SELECT.matcher(sql).find() && IP_COLUMN.matcher(sql).find()) {
                out.add(sql);
            }
        }
        return out;
    }

    @Test
    void patternFindsSelectsOfIpColumns() {
        assertFalse(violations("x(\"SELECT id, ip FROM sessions\")").isEmpty());
        assertFalse(violations("x(\"SELECT id, \"\n + \"ip_last FROM guests\")").isEmpty());
        assertFalse(violations("x(\"SELECT a.ip FROM audit_log a\")").isEmpty());
        assertTrue(violations("x(\"INSERT INTO sessions (id, ip) VALUES (?, ?)\")").isEmpty());
        assertTrue(violations("x(\"UPDATE guests SET ip_last = ? WHERE id = ?\")").isEmpty());
        assertTrue(violations("x(\"SELECT id, ipsum, zip FROM t\")").isEmpty());
        assertTrue(violations("x(\"SELECT id FROM t\", \"ip\")").isEmpty(), "separate literals are separate");
    }

    @Test
    void onlyIpAccessSelectsIpColumns() throws IOException {
        assertTrue(Files.isDirectory(MAIN), "run from the project root");
        List<String> found = new ArrayList<>();
        int ipAccessHits;
        try (Stream<Path> files = Files.walk(MAIN)) {
            ipAccessHits = 0;
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> v = violations(Files.readString(file));
                if (file.getFileName().toString().equals("IpAccess.java")) {
                    ipAccessHits += v.size();
                } else {
                    v.forEach(sql -> found.add(file + ": " + sql));
                }
            }
        }
        assertEquals(List.of(), found, "only IpAccess may select IP columns");
        assertTrue(ipAccessHits >= 3, "the scan sees IpAccess's own queries");
    }
}
