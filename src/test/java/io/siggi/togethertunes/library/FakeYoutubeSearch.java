package io.siggi.togethertunes.library;

import io.siggi.togethertunes.youtube.FakeYtSucks;
import io.siggi.togethertunes.youtube.YoutubeService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A fake YouTube service for the searches ("Find a music video", the YouTube search):
 * {@code /api/search} answers {@link #searchJson} (or fails after {@link #searchFails}) and is
 * recorded ({@link #searches}); {@code /api/info} answers metadata for any video, like the service
 * does for an import.
 */
public final class FakeYoutubeSearch implements AutoCloseable {
    public final FakeYtSucks fake;

    public FakeYoutubeSearch() throws IOException {
        fake = new FakeYtSucks();
        fake.anyVideo = true;
    }

    /** A client of the fake. */
    public YoutubeService service() {
        return fake.service();
    }

    /** What the next searches answer. */
    public void searchJson(String json) {
        fake.searchStatus = 200;
        fake.searchBody = json;
    }

    /** The next searches fail: YouTube's {@code network} error with the service's 502. */
    public void searchFails() {
        fake.searchStatus = 502;
        fake.searchBody = "{\"error_type\":\"network\",\"error\":\"[youtube] Unable to download API page\"}";
    }

    /** The searches made so far: {@code q=<text> n=<count> exclude_live=<flag>}. */
    public List<String> searches() {
        List<String> out = new ArrayList<>();
        for (FakeYtSucks.Req r : fake.requests("/api/search")) {
            out.add("q=" + r.query().get("q") + " n=" + r.query().get("n") + " exclude_live=" + r.query().get("exclude_live"));
        }
        return out;
    }

    /** {@link FakeYtSucks#results}. */
    public static String entries(String[]... entries) {
        return FakeYtSucks.results(entries);
    }

    @Override
    public void close() {
        fake.close();
    }
}
