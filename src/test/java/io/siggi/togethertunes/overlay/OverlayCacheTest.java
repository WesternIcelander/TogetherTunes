package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OverlayCache}: the restart scan (partials, temporary files, orphans), LRU eviction within
 * {@code overlay.cacheMaxBytes} with the grace period and in-use entries, stale overlay hashes,
 * purge and room delete.
 */
class OverlayCacheTest {
    @TempDir
    Path tmp;

    static final String R1 = "room1";
    static final String R2 = "room2";
    static final String H1 = "0123456789abcdef";
    static final String H2 = "fedcba9876543210";

    final AtomicLong now = new AtomicLong(10_000_000);
    final AtomicLong max = new AtomicLong(Long.MAX_VALUE);
    final Set<Path> inUse = new HashSet<>();
    final List<Path> evicted = new ArrayList<>();

    Path rooms() {
        return tmp.resolve("rooms");
    }

    OverlayCache cache() {
        OverlayCache c = new OverlayCache(rooms(), max::get, Runnable::run, now::get);
        OverlayCache.Evictor evictor = p -> {
            if (inUse.contains(p)) {
                return false;
            }
            evicted.add(p);
            return p.getParent().getFileName().toString().equals("cards") ? OverlayCache.deleteCardFiles(p)
                    : OverlayCache.deleteTree(p);
        };
        c.evictors(evictor, evictor);
        return c;
    }

    /** A finished re-encode of {@code bytes} bytes, last used at {@code usedAt}. */
    Path encode(String room, String item, String hash, int g0, int bytes, long usedAt) throws IOException {
        Path dir = rooms().resolve(room).resolve("ov").resolve(item).resolve("v1-" + hash + "-720p30-3000k-v3-g" + g0)
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.write(dir.resolve(OverlayGops.H264_FILE), new byte[bytes]);
        Files.write(dir.resolve(OverlayCache.DONE_FILE), new byte[0]);
        Files.setLastModifiedTime(dir.resolve(OverlayCache.DONE_FILE), FileTime.fromMillis(usedAt));
        return dir;
    }

    /** A card of {@code bytes} bytes (plus a 4-byte fingerprint), last used at {@code usedAt}. */
    Path card(String room, String name, int bytes, long usedAt) throws IOException {
        Path dir = rooms().resolve(room).resolve("cards");
        Files.createDirectories(dir);
        Path f = dir.resolve(name).toAbsolutePath().normalize();
        Files.write(f, new byte[bytes]);
        Files.write(OverlayCache.keyFile(f), new byte[4]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(usedAt));
        return f;
    }

    @Test
    void theRestartScanDiscardsPartialsTemporaryFilesAndDeletedRooms() throws Exception {
        long old = now.get() - 3_600_000;
        Path done = encode(R1, "item1", H1, 0, 1000, old);
        Path partial = encode(R1, "item1", H1, 2, 500, old);
        Files.delete(partial.resolve(OverlayCache.DONE_FILE));
        Path partialOnly = encode(R1, "item2", H1, 0, 500, old);
        Files.delete(partialOnly.resolve(OverlayCache.DONE_FILE));
        Path card = card(R1, "filler-paused-" + H1 + "-720p30-3000k-v3-c2.mp4", 300, old);
        Path noKey = card(R1, "item-item1-v1-" + H1 + "-720p30-3000k-v3-c2.mp4", 300, old);
        Files.delete(OverlayCache.keyFile(noKey));
        Path keyOnly = OverlayCache.keyFile(card(R1, "item-item2-v1-" + H1 + "-720p30-3000k-v3-c2.mp4", 300, old));
        Files.delete(keyOnly.resolveSibling(keyOnly.getFileName().toString().replace(".key", "")));
        Path tmpCard = rooms().resolve(R1).resolve("cards").resolve(".filler-x.mp4-123.mp4");
        Files.write(tmpCard, new byte[10]);
        Path tmpOverlay = rooms().resolve(R1).resolve(".overlay123.png.tmp");
        Files.write(tmpOverlay, new byte[10]);
        Path png = rooms().resolve(R1).resolve("overlay.png");
        Files.write(png, new byte[10]);
        encode(R2, "item1", H1, 0, 1000, old);

        OverlayCache c = cache();
        assertEquals(7, c.start(Set.of(R1)));
        assertTrue(Files.isRegularFile(done.resolve(OverlayGops.H264_FILE)));
        assertFalse(Files.exists(partial), "no marker: the server stopped mid-encode");
        assertFalse(Files.exists(partialOnly.getParent()), "the item directory went with its only encode");
        assertFalse(Files.exists(noKey), "the fingerprint is written last");
        assertFalse(Files.exists(keyOnly));
        assertFalse(Files.exists(tmpCard));
        assertFalse(Files.exists(tmpOverlay));
        assertTrue(Files.exists(png), "the room's overlay image stays");
        assertFalse(Files.exists(rooms().resolve(R2)), "a deleted room's files");
        assertTrue(Files.exists(card));
        assertTrue(c.contains(done));
        assertTrue(c.contains(card));
        assertEquals(1000 + 304, c.totalBytes());
        assertTrue(evicted.isEmpty(), "within the limit");
    }

    @Test
    void theLeastRecentlyUsedGoFirstAndRecentOrInUseEntriesStay() throws Exception {
        long t = now.get();
        Path a = encode(R1, "item1", H1, 0, 1000, t - 500_000);
        Path b = card(R1, "item-item1-v1-" + H1 + "-enc-c2.mp4", 996, t - 400_000); // 1000 with the key
        Path c = encode(R1, "item2", H1, 0, 1000, t - 300_000);
        Path d = encode(R2, "item3", H1, 0, 1000, t - 200_000);
        OverlayCache cache = cache();
        cache.start(null);
        assertEquals(4000, cache.totalBytes());

        cache.used(a); // a is now the most recent
        inUse.add(c);
        max.set(2500);
        assertEquals(2, cache.trim());
        assertEquals(List.of(b, d), evicted, "b is the oldest, c is in use, d next");
        assertTrue(Files.exists(a) && Files.exists(c));
        assertFalse(Files.exists(b) || Files.exists(OverlayCache.keyFile(b)) || Files.exists(d));
        assertFalse(Files.exists(d.getParent()), "empty item directory removed");
        assertEquals(2000, cache.totalBytes());

        // a was used within the grace period, c is in use: nothing more goes, even at 0
        max.set(0);
        assertEquals(0, cache.trim());
        inUse.clear();
        assertEquals(1, cache.trim(), "c is no longer in use");
        now.addAndGet(OverlayCache.GRACE_MS);
        assertEquals(1, cache.trim(), "0 keeps nothing");
        assertEquals(0, cache.totalBytes());
    }

    @Test
    void addedEntriesAreCountedAndTheLastUseIsPersisted() throws Exception {
        OverlayCache cache = cache();
        cache.start(Set.of(R1));
        Path a = encode(R1, "item1", H1, 0, 1000, 1);
        cache.added(a);
        assertTrue(cache.contains(a));
        assertEquals(1000, cache.totalBytes());
        Path card = card(R1, "item-item1-v1-" + H1 + "-enc-c2.mp4", 100, 1);
        cache.added(card);
        assertEquals(1104, cache.totalBytes());
        cache.added(card); // made again: counted once
        assertEquals(1104, cache.totalBytes());
        cache.used(rooms().resolve(R1).resolve("elsewhere")); // unknown: ignored

        // the last use reaches the disk at most every PERSIST_MS, so the order survives a restart
        now.addAndGet(OverlayCache.PERSIST_MS - 1);
        cache.used(card);
        assertEquals(1, Files.getLastModifiedTime(card).toMillis());
        now.addAndGet(1);
        cache.used(card);
        cache.used(a);
        assertEquals(now.get(), Files.getLastModifiedTime(card).toMillis());
        assertEquals(now.get(), Files.getLastModifiedTime(a.resolve(OverlayCache.DONE_FILE)).toMillis());

        // a smaller limit evicts at the next trim; something added within the grace period is kept
        max.set(1000);
        assertEquals(0, cache.trim());
        now.addAndGet(OverlayCache.GRACE_MS);
        cache.used(card);
        assertEquals(1, cache.trim());
        assertEquals(List.of(a), evicted);
    }

    @Test
    void entriesOfAnotherOverlayHashGoWhenNothingUsesThem() throws Exception {
        long t = now.get();
        Path old1 = encode(R1, "item1", H1, 0, 1000, t);
        Path oldCard = card(R1, "filler-paused-" + H1 + "-enc-c2.mp4", 100, t);
        Path current = encode(R1, "item1", H2, 0, 1000, t);
        Path otherRoom = encode(R2, "item1", H1, 0, 1000, t);
        OverlayCache cache = cache();
        cache.start(null);
        inUse.add(old1);
        cache.roomOverlay(R1, H2); // trims at once (direct executor), whatever the size and grace
        assertEquals(List.of(oldCard), evicted);
        assertTrue(Files.exists(old1), "in use: kept for now");
        assertTrue(Files.exists(current));
        assertTrue(Files.exists(otherRoom), "another room's hash is its own");
        inUse.clear();
        cache.trim();
        assertFalse(Files.exists(old1));
        assertEquals(2000, cache.totalBytes());

        // an old-hash encode finishing after the change is stale at once
        Path late = encode(R1, "item2", H1, 0, 1000, t);
        cache.added(late);
        assertFalse(Files.exists(late));
        assertFalse(cache.contains(late));
    }

    @Test
    void aPurgeDeletesTheItemsFilesInEveryRoom() throws Exception {
        long t = now.get();
        Path a = encode(R1, "item1", H1, 0, 1000, t);
        Path b = encode(R1, "item1", H1, 2, 1000, t);
        Path c = encode(R2, "item1", H1, 0, 1000, t);
        Path card = card(R2, "item-item1-v1-" + H1 + "-enc-c2.mp4", 100, t);
        Path keep = encode(R1, "item10", H1, 0, 1000, t);
        Path keepCard = card(R2, "item-item10-v1-" + H1 + "-enc-c2.mp4", 100, t);
        Path filler = card(R2, "filler-paused-" + H1 + "-enc-c2.mp4", 100, t);
        OverlayCache cache = cache();
        cache.start(null);
        inUse.add(a); // purged: deleted anyway
        cache.itemPurged("item1");
        for (Path p : List.of(a, b, c, card, a.getParent(), c.getParent())) {
            assertFalse(Files.exists(p), p.toString());
        }
        for (Path p : List.of(keep, keepCard, filler)) {
            assertTrue(Files.exists(p), p.toString());
        }
        assertEquals(1000 + 104 + 104, cache.totalBytes());
        cache.itemPurged("../room1"); // not an item id: nothing
        assertTrue(Files.exists(keep));
    }

    @Test
    void aDeletedRoomsFilesGo() throws Exception {
        long t = now.get();
        encode(R1, "item1", H1, 0, 1000, t);
        card(R1, "filler-paused-" + H1 + "-enc-c2.mp4", 100, t);
        Path other = encode(R2, "item1", H1, 0, 1000, t);
        OverlayCache cache = cache();
        cache.start(null);
        cache.roomDeleted(R1);
        assertFalse(Files.exists(rooms().resolve(R1)));
        assertTrue(Files.exists(other));
        assertEquals(1000, cache.totalBytes());
        cache.roomDeleted(".."); // not a room id: nothing
        assertTrue(Files.exists(other));
    }
}
