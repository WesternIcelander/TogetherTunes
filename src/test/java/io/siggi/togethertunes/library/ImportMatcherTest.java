package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ImportMatcher}: finding a library export's files in the source. */
class ImportMatcherTest {
    private static final String ROOT = "/Users/me/Music/Music/Media.localized/";

    private static List<String> files(String... f) {
        return Arrays.asList(f);
    }

    private static ImportMatcher.Match only(String track, List<String> files) {
        return ImportMatcher.match(List.of(ROOT + "Music/A/B/1.m4a", track), files).matches().get(1);
    }

    @Test
    void theFolderAboveTheMediaFolder() {
        List<String> files = files("Library.xml", "Media.localized/Music/A/B/1.m4a", "Media.localized/Music/A/B/2.mp3",
                "Media.localized/Music/C/D/3.m4a", "Media.localized/.DS_Store");
        ImportMatcher.Result r = ImportMatcher.match(List.of(ROOT + "Music/A/B/1.m4a", ROOT + "Music/A/B/2.mp3",
                ROOT + "Music/C/D/3.m4a"), files);
        assertEquals("users/me/music/music", r.prefix());
        assertEquals(List.of("Media.localized/Music/A/B/1.m4a", "Media.localized/Music/A/B/2.mp3",
                "Media.localized/Music/C/D/3.m4a"), r.matches().stream().map(ImportMatcher.Match::file).toList());
        assertEquals(3, r.matched());
    }

    @Test
    void theMediaFolderItselfAsTheRoot() {
        ImportMatcher.Result r = ImportMatcher.match(List.of(ROOT + "Music/A/B/1.m4a", ROOT + "Music/C/D/3.m4a"),
                files("Music/A/B/1.m4a", "Music/C/D/3.m4a", "Library.xml"));
        assertEquals("users/me/music/music/media.localized", r.prefix());
        assertEquals("Music/C/D/3.m4a", r.matches().get(1).file());
    }

    @Test
    void aDifferentRootUnderAnotherFolder() {
        // Exported on another machine, stored under export/
        ImportMatcher.Result r = ImportMatcher.match(List.of("/Volumes/Disk/iTunes/iTunes Media/Music/A/1.mp3"),
                files("export/iTunes Media/Music/A/1.mp3", "export/Library.xml"));
        assertEquals("export/iTunes Media/Music/A/1.mp3", r.matches().get(0).file());
    }

    @Test
    void nfdAgainstNfcAndCase() {
        String nfd = Normalizer.normalize("Beyoncé/Déjà Vu.m4a", Normalizer.Form.NFD);
        String nfc = Normalizer.normalize("Beyoncé/Déjà Vu.m4a", Normalizer.Form.NFC);
        ImportMatcher.Result r = ImportMatcher.match(List.of(ROOT + "Music/A/B/1.m4a", ROOT + "Music/" + nfd,
                        ROOT + "music/a/b/1.M4A"),
                files("Media.localized/Music/A/B/1.m4a", "Media.localized/Music/" + nfc));
        assertEquals("Media.localized/Music/" + nfc, r.matches().get(1).file());
        assertEquals("Media.localized/Music/A/B/1.m4a", r.matches().get(2).file());
    }

    @Test
    void sameNamedFilesAreNeverGuessed() {
        List<String> files = files("Media.localized/Music/A/B/1.m4a", "Media.localized/Music/X/One/01 Intro.m4a",
                "Media.localized/Music/Y/Two/01 Intro.m4a");
        // A track whose own folder is gone: only the file name matches, and two files have it.
        ImportMatcher.Match m = only(ROOT + "Music/Z/Three/01 Intro.m4a", files);
        assertNull(m.file());
        assertEquals(ImportMatcher.AMBIGUOUS, m.reason());
        // With the album folder it is unique.
        assertEquals("Media.localized/Music/Y/Two/01 Intro.m4a",
                only("/Other/Root/Music/Y/Two/01 Intro.m4a", files).file());
    }

    @Test
    void aUniqueFileNameIsFoundWhenTheFolderMoved() {
        List<String> files = files("Media.localized/Music/A/B/1.m4a", "Media.localized/Music/New/Place/song.mp3");
        assertEquals("Media.localized/Music/New/Place/song.mp3", only(ROOT + "Music/Old/Place/song.mp3", files).file());
    }

    @Test
    void missingFiles() {
        List<String> files = files("Media.localized/Music/A/B/1.m4a");
        ImportMatcher.Result r = ImportMatcher.match(Arrays.asList(ROOT + "Music/A/B/1.m4a", ROOT + "Music/Gone.m4a",
                null), files);
        assertEquals(ImportMatcher.NOT_FOUND, r.matches().get(1).reason());
        assertEquals(ImportMatcher.NO_FILE, r.matches().get(2).reason());
        assertEquals(1, r.matched());
    }
}
