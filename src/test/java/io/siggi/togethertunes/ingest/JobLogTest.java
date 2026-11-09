package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobLogTest {

    @Test
    void keepsLinesInOrder() {
        JobLog log = new JobLog();
        assertEquals("", log.tail());
        log.append("one");
        log.append("two\r\nthree\rfour");
        assertEquals("one\ntwo\nthree\nfour\n", log.tail());
        assertFalse(log.truncated());
    }

    @Test
    void dropsOldestLinesBeyondTheLimit() {
        List<String> seen = new ArrayList<>();
        JobLog log = new JobLog(JobLog.MAX_LINE_CHARS + 100, seen::add);
        for (int i = 0; i < 1000; i++) {
            log.append("line " + i);
        }
        String tail = log.tail();
        assertTrue(log.truncated());
        assertTrue(tail.startsWith(JobLog.TRUNCATED_MARKER + "\n"));
        assertTrue(tail.endsWith("line 999\n"));
        assertFalse(tail.contains("line 0\n"));
        assertTrue(tail.length() - JobLog.TRUNCATED_MARKER.length() - 1 <= JobLog.MAX_LINE_CHARS + 100);
        assertEquals(1000, seen.size(), "the listener sees every line");
    }

    @Test
    void cutsVeryLongLines() {
        JobLog log = new JobLog();
        log.append("y".repeat(JobLog.MAX_LINE_CHARS * 3));
        assertEquals("y".repeat(JobLog.MAX_LINE_CHARS) + "…\n", log.tail());
    }

    @Test
    void rejectsTinyLimit() {
        assertThrows(IllegalArgumentException.class, () -> new JobLog(10, null));
    }

    @Test
    void resumesAStoredTail() {
        assertEquals("", JobLog.resume(null).tail());
        assertEquals("", JobLog.resume("").tail());
        JobLog plain = JobLog.resume("a\nb\n");
        plain.append("c");
        assertEquals("a\nb\nc\n", plain.tail());
        assertFalse(plain.truncated());

        JobLog cut = new JobLog();
        for (int i = 0; i < 20_000; i++) {
            cut.append("line " + i);
        }
        JobLog resumed = JobLog.resume(cut.tail());
        assertTrue(resumed.truncated());
        assertEquals(cut.tail(), resumed.tail(), "the marker isn't doubled");
        resumed.append("more");
        assertTrue(resumed.tail().startsWith(JobLog.TRUNCATED_MARKER + "\n"));
        assertTrue(resumed.tail().endsWith("line 19999\nmore\n"));
    }
}
