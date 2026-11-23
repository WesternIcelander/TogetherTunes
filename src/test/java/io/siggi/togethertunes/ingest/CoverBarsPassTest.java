package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The one-time pass that trims letterbox bars off older covers ({@link CoverBarsPass};
 * {@code docs/library-and-ingest.md#cards}, "Letterbox bars"): a barred cover gets a cropped one by
 * a card-only re-encode (the rooms are told), a clean cover is only marked, a busy item is retried
 * and left unchecked, and a second run resumes with what is left. Real ffmpeg (skipped without it).
 */
@Timeout(180)
@Execution(ExecutionMode.SAME_THREAD)
class CoverBarsPassTest extends IngestPipelineTestBase {
    /** An audio upload with square testsrc2 art embedded, ingested. */
    private String audioItem(String name) throws Exception {
        String item = fixtures.item("pending");
        Path png = dir.resolve(name + ".png");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=360x360", "-frames:v", "1", png.toString());
        Path source = stage(item, "source.flac");
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=44100", "-i", png.toString(), "-t", "2", "-map", "0:a", "-map", "1:v",
                "-c:a", "flac", "-c:v", "png", "-disposition:v", "attached_pic", source.toString());
        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        return item;
    }

    /** Replaces the item's current cover.jpg with itself between grey 16:9 bars (an older cover). */
    private void letterbox(String item) throws Exception {
        Path cover = cur(item).resolve(IngestPipeline.COVER);
        BufferedImage art = ImageIO.read(cover.toFile());
        BufferedImage boxed = new BufferedImage(art.getHeight() * 16 / 9, art.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = boxed.createGraphics();
        g.setColor(new Color(0x40, 0x40, 0x40));
        g.fillRect(0, 0, boxed.getWidth(), boxed.getHeight());
        g.drawImage(art, (boxed.getWidth() - art.getWidth()) / 2, 0, null);
        g.dispose();
        Files.delete(cover); // may be a link shared with another version
        CoverBars.writeJpeg(boxed, cover);
    }

    /** Stops the queues that ingested the items, so queued jobs stay queued. */
    private void stopQueues() {
        for (IngestQueue q : queues) {
            q.close();
        }
        queues.clear();
    }

    private void uncheckAll() throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET cover_bars_checked = 0")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    private int checked(String id) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT cover_bars_checked FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Test
    void cropsBarredCoversMarksEveryItemSkipsBusyAndResumes() throws Exception {
        String barred = audioItem("a");
        String clean = audioItem("b");
        String busy = audioItem("c");
        stopQueues();
        assertEquals(1, checked(barred), "new items start checked");
        letterbox(barred);
        letterbox(busy);
        uncheckAll();
        Path cleanBefore = cur(clean);
        // a job of the busy item is running
        IngestJob running = jobs.enqueue(busy, Kind.REENCODE);
        assertEquals(running.id(), jobs.claimNext().id());

        CoverBarsPass pass = new CoverBarsPass(db, data, jobs);
        AtomicInteger idleAsked = new AtomicInteger();
        pass.timing(() -> idleAsked.incrementAndGet() > 2, Duration.ZERO, Duration.ZERO);
        assertEquals(1, pass.run());
        assertTrue(idleAsked.get() > 2, "waited for the queue to be idle");

        assertEquals(1, checked(barred));
        BufferedImage pending = ImageIO.read(IngestPipeline.pendingCover(data, barred).toFile());
        assertTrue(Math.abs(pending.getWidth() - 360) <= 4, "square: " + pending.getWidth());
        assertEquals(360, pending.getHeight());
        IngestJob cover = jobs.latestForItem(barred).orElseThrow();
        assertEquals(Kind.REENCODE, cover.kind());
        assertEquals(State.QUEUED, cover.state());

        assertEquals(1, checked(clean), "no bars: only marked");
        assertEquals(Kind.UPLOAD, jobs.latestForItem(clean).orElseThrow().kind());
        assertEquals(cleanBefore, cur(clean));

        assertEquals(0, checked(busy), "busy: left for later");
        assertFalse(Files.exists(IngestPipeline.pendingCover(data, busy)));

        // the busy item's job ends (a queue would take over a running job as interrupted)
        assertTrue(jobs.fail(running.id(), "stopped", ""));

        // the card-only re-encode applies it, and the rooms are told
        List<String> told = new CopyOnWriteArrayList<>();
        jobs.statusListener(new ItemStatusListener() {
            @Override
            public void itemStatusChanged(String itemId) {
            }

            @Override
            public void itemDetailsChanged(String itemId) {
                told.add(itemId);
            }
        });
        Path before = cur(barred);
        IngestQueue q = queue(tools, reference, 1800);
        IngestJob done = awaitJob(cover.id());
        assertEquals(State.DONE, done.state(), done.log());
        assertTrue(done.log().contains("card only: a new cover"), done.log());
        assertFalse(cur(barred).equals(before));
        BufferedImage applied = ImageIO.read(cur(barred).resolve(IngestPipeline.COVER).toFile());
        assertEquals(pending.getWidth(), applied.getWidth());
        assertTrue(told.contains(barred), "rooms refresh: " + told);
        q.close();

        // the next start resumes with the busy item only
        pass.timing(() -> true, Duration.ZERO, Duration.ZERO);
        assertEquals(1, pass.run());
        assertEquals(1, checked(busy));
        assertTrue(Files.exists(IngestPipeline.pendingCover(data, busy)));
        assertEquals(State.DONE, jobs.latestForItem(barred).orElseThrow().state(), "not checked again");
        assertEquals(0, pass.run(), "nothing left");
    }

    @Test
    void aWaitingNewCoverIsBusy() throws Exception {
        String item = audioItem("w");
        stopQueues();
        letterbox(item);
        uncheckAll();
        Files.writeString(IngestPipeline.pendingCover(data, item), "someone's cover");
        CoverBarsPass pass = new CoverBarsPass(db, data, jobs);
        assertEquals(CoverBarsPass.Outcome.BUSY, pass.check(item));
        assertEquals(0, checked(item));
        assertEquals("someone's cover", Files.readString(IngestPipeline.pendingCover(data, item)));
    }
}
