package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.CardRenderer.Filler;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FillerCardsTest {
    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    @Timeout(180)
    void encodesEveryFillerOnceAndReusesThem(OutputProfile p) throws Exception {
        OutputEncoding profile = OutputEncoding.defaultFor(p);
        Tools tools = CardEncoderTest.tools();
        Path cards = dir.resolve("cards");
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);

        FillerCards fillers = FillerCards.ensure(tools, cards, ref, null);
        assertEquals(profile, fillers.encoding());
        assertEquals(Filler.values().length, fillers.encoded());
        Path profileDir = cards.resolve(profile.id());
        assertEquals(profileDir.resolve("paused.mp4"), fillers.card(Filler.PAUSED));
        assertEquals(profileDir.resolve("idle.mp4"), fillers.card(Filler.QUEUE_EMPTY));
        assertEquals(profileDir.resolve("loading.mp4"), fillers.card(Filler.LOADING));
        Set<String> contents = new HashSet<>();
        for (Filler filler : Filler.values()) {
            Path card = fillers.card(filler);
            assertEquals(card, FillerCards.cardFile(cards, profile, filler));
            assertArrayEquals(ref.avcC(), ReferenceCard.readAvcC(card), filler + " avcC");
            CardEncoderTest.checkCard(tools, card, profile);
            CardEncoderTest.checkSkipFrameCard(tools, card, profile);
            assertTrue(Files.isRegularFile(profileDir.resolve(filler.id() + FillerCards.KEY_SUFFIX)));
            contents.add(new String(Files.readAllBytes(card), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        assertEquals(Filler.values().length, contents.size(), "the cards differ");
        assertEquals(List.of(), CardEncoderTest.tempFiles(profileDir));

        // Second start: nothing to do, files untouched; leftovers of a killed encode are removed.
        Files.writeString(profileDir.resolve(".paused.mp4-123.mp4"), "partial");
        Files.writeString(profileDir.resolve(".reference-456.png"), "partial");
        ReferenceCard.ensure(tools, cards, profile, null);
        assertEquals(List.of(), CardEncoderTest.tempFiles(profileDir));
        FileTime before = Files.getLastModifiedTime(fillers.card(Filler.PAUSED));
        FillerCards again = FillerCards.ensure(tools, cards, ref, null);
        assertEquals(0, again.encoded());
        assertEquals(before, Files.getLastModifiedTime(again.card(Filler.PAUSED)));
    }

    @Test
    @Timeout(180)
    void cardsOfAnOlderCardFormatAreRebuilt() throws Exception {
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Tools tools = CardEncoderTest.tools();
        Path cards = dir.resolve("cards");
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);
        FillerCards.ensure(tools, cards, ref, null);
        // as a server before skip-frame cards left them: a full x264 card under the old fingerprint
        BufferedImage image = new CardRenderer(profile.profile()).renderFiller(Filler.PAUSED);
        Path paused = FillerCards.cardFile(cards, profile, Filler.PAUSED);
        Path png = dir.resolve("paused.png");
        CardRenderer.writePng(image, png);
        CardEncoder.encodePng(tools, png, paused, profile, null);
        String v2 = FillerCards.fingerprint(image, ref);
        Files.writeString(paused.resolveSibling("paused" + FillerCards.KEY_SUFFIX),
                FillerCards.fingerprint("card v1", image, ref) + "\n");
        assertTrue(!v2.equals(FillerCards.fingerprint("card v1", image, ref)));

        FillerCards again = FillerCards.ensure(tools, cards, ref, null);
        assertEquals(1, again.encoded(), "only the old-format card is made again");
        CardEncoderTest.checkSkipFrameCard(tools, paused, profile);
        assertEquals(v2, Files.readString(paused.resolveSibling("paused" + FillerCards.KEY_SUFFIX)).strip());
    }

    @Test
    @Timeout(180)
    void missingStaleOrDamagedCardsAreReencoded() throws Exception {
        Tools tools = CardEncoderTest.tools();
        Path cards = dir.resolve("cards");
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);
        FillerCards fillers = FillerCards.ensure(tools, cards, ref, null);
        Path profileDir = cards.resolve(profile.id());

        Files.delete(fillers.card(Filler.PAUSED));
        Files.writeString(profileDir.resolve("idle" + FillerCards.KEY_SUFFIX), "stale\n");
        Files.write(fillers.card(Filler.LOADING), new byte[] {0, 0, 0, 8, 'f', 'r', 'e', 'e'});

        FillerCards again = FillerCards.ensure(tools, cards, ref, null);
        assertEquals(3, again.encoded());
        for (Filler filler : Filler.values()) {
            assertArrayEquals(ref.avcC(), ReferenceCard.readAvcC(again.card(filler)));
        }

        // A missing key file alone also triggers a re-encode of that card only.
        Files.delete(profileDir.resolve("paused" + FillerCards.KEY_SUFFIX));
        assertEquals(1, FillerCards.ensure(tools, cards, ref, null).encoded());
    }

    @Test
    @Timeout(180)
    void encoderChangeKeepsCurrentCardsButCannotMakeNewOnes() throws Exception {
        Tools tools = CardEncoderTest.tools();
        Path cards = dir.resolve("cards");
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);
        FillerCards.ensure(tools, cards, ref, null);

        // A changed reference changes every fingerprint, and a fresh encode can't match it.
        Path avcFile = cards.resolve(profile.id()).resolve(ReferenceCard.AVCC_FILE);
        byte[] older = ref.avcC();
        older[older.length - 1] ^= 0x01;
        Files.write(avcFile, older);
        ReferenceCard changed = ReferenceCard.ensure(tools, cards, profile, null);
        assertTrue(changed.encoderChanged());
        Path paused = FillerCards.cardFile(cards, profile, Filler.PAUSED);
        byte[] before = Files.readAllBytes(paused);
        assertThrows(ReferenceCard.AvcCMismatchException.class, () -> FillerCards.ensure(tools, cards, changed, null));
        assertArrayEquals(before, Files.readAllBytes(paused), "published card untouched");
    }

    @Test
    void fingerprintDependsOnPixelsProfileAndReference() throws Exception {
        Tools tools = CardEncoderTest.tools();
        Path cards = dir.resolve("cards");
        ReferenceCard p720 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        CardRenderer renderer = new CardRenderer(OutputProfile.P720_30);
        BufferedImage paused = renderer.renderFiller(Filler.PAUSED);
        String key = FillerCards.fingerprint(paused, p720);
        assertEquals(64, key.length());
        assertEquals(key, FillerCards.fingerprint(renderer.renderFiller(Filler.PAUSED), p720), "deterministic");
        assertNotEquals(key, FillerCards.fingerprint(renderer.renderFiller(Filler.LOADING), p720));
        paused.setRGB(5, 5, 0xffffff);
        assertNotEquals(key, FillerCards.fingerprint(paused, p720));
        ReferenceCard p1080 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P1080_30), null);
        assertFalse(key.equals(FillerCards.fingerprint(renderer.renderFiller(Filler.PAUSED), p1080)));
    }
}
