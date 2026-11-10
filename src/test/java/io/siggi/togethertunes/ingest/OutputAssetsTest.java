package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@link OutputAssets}: switching the output encoding at runtime (fake cards; no ffmpeg). */
class OutputAssetsTest {
    private static final OutputEncoding A = OutputEncoding.DEFAULT;
    private static final OutputEncoding B = new OutputEncoding(OutputProfile.P720_30, 2000);
    private static final OutputEncoding C = OutputEncoding.defaultFor(OutputProfile.P1080_30);

    private static ReferenceCard card(OutputEncoding e) {
        return ReferenceCard.forTest(e, Path.of("/nonexistent/" + e.id() + "/ref.mp4"), new byte[] {1, 2, 3});
    }

    private static OutputAssets.Assets assets(OutputEncoding e) {
        return new OutputAssets.Assets(e, card(e), null);
    }

    @Test
    void switchesMakesTheCardsFirstAndTellsTheListeners() throws Exception {
        List<OutputEncoding> made = new ArrayList<>();
        OutputAssets assets = new OutputAssets(e -> {
            made.add(e);
            return assets(e);
        }, assets(A));
        List<String> heard = new ArrayList<>();
        assets.addListener((previous, now) -> heard.add(previous.encoding().id() + "->" + now.encoding().id()));
        assets.addListener((previous, now) -> {
            throw new IllegalStateException("a broken listener doesn't stop the switch");
        });

        assertFalse(assets.follow(() -> A), "already current: nothing to make");
        assertTrue(made.isEmpty());

        AtomicReference<OutputEncoding> setting = new AtomicReference<>(B);
        assertTrue(assets.follow(setting::get));
        assertEquals(B, assets.encoding());
        assertEquals(B, assets.reference().encoding());
        assertEquals(List.of(B), made);
        assertEquals(List.of(A.id() + "->" + B.id()), heard);

        setting.set(C);
        assertTrue(assets.follow(setting::get));
        assertEquals(C, assets.encoding());
        assertEquals(List.of(A.id() + "->" + B.id(), B.id() + "->" + C.id()), heard);
    }

    @Test
    void aReferenceCardThatCantBeMadeKeepsTheCurrentEncoding() {
        OutputAssets.Assets start = assets(A);
        OutputAssets assets = new OutputAssets(e -> new OutputAssets.Assets(e, null, null), start);
        List<String> heard = new ArrayList<>();
        assets.addListener((previous, now) -> heard.add(now.encoding().id()));
        IOException e = assertThrows(IOException.class, () -> assets.follow(() -> C));
        assertTrue(e.getMessage().contains(C.id()), e.getMessage());
        assertSame(start, assets.current());
        assertTrue(heard.isEmpty());
    }

    @Test
    void fixedAssetsNeverChange() {
        OutputAssets assets = OutputAssets.fixed(assets(A));
        assertThrows(IllegalStateException.class, () -> assets.follow(() -> B));
        assertEquals(A, assets.encoding());
    }
}
