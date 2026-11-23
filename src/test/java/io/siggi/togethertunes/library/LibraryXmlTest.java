package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.HttpError;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LibraryXml}: the property list parser, made safe against DTD tricks. */
class LibraryXmlTest {
    static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\""
            + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n";

    @TempDir
    Path dir;

    static LibraryXml parse(String xml) throws IOException {
        return LibraryXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTracksAndPlaylists() throws IOException {
        String nfd = Normalizer.normalize("Café", Normalizer.Form.NFD);
        String xml = HEADER + """
                <plist version="1.0">
                <dict>
                	<key>Major Version</key><integer>1</integer>
                	<key>Music Folder</key><string>file:///Users/me/Music/Media.localized/</string>
                	<key>Tracks</key>
                	<dict>
                		<key>101</key>
                		<dict>
                			<key>Track ID</key><integer>101</integer>
                			<key>Name</key><string>Rock &amp; Roll &#233;t&#xE9;</string>
                			<key>Artist</key><string>The &lt;Band&gt;</string>
                			<key>Album Artist</key><string>Various</string>
                			<key>Album</key><string>Hits</string>
                			<key>Genre</key><string>Rock</string>
                			<key>Track Number</key><integer>3</integer>
                			<key>Track Count</key><integer>12</integer>
                			<key>Disc Number</key><integer>1</integer>
                			<key>Disc Count</key><integer>2</integer>
                			<key>Year</key><integer>2010</integer>
                			<key>Release Date</key><date>2010-05-10T12:00:00Z</date>
                			<key>Total Time</key><integer>201000</integer>
                			<key>Date Added</key><date>2015-01-02T03:04:05Z</date>
                			<key>Kind</key><string>AAC audio file</string>
                			<key>Loved</key><true/>
                			<key>Persistent ID</key><string>ABCDEF0123456789</string>
                			<key>Artwork</key><data>AAAA</data>
                			<key>Location</key><string>file:///Users/me/Music/Media.localized/Music/The%20Band/Hits/03%20Rock%20%26%20Roll+1.m4a</string>
                		</dict>
                		<key>102</key>
                		<dict>
                			<key>Track ID</key><integer>102</integer>
                			<key>Name</key><string>Second</string>
                			<key>Year</key><integer>1999</integer>
                			<key>Release Date</key><date>2001-01-01T00:00:00Z</date>
                			<key>Favorited</key><true/>
                			<key>Track Number</key><integer>0</integer>
                			<key>Location</key><string>file://localhost/Users/me/Music/X/NFD.mp3</string>
                		</dict>
                	</dict>
                	<key>Playlists</key>
                	<array>
                		<dict>
                			<key>Name</key><string>Library</string>
                			<key>Master</key><true/>
                			<key>Playlist Persistent ID</key><string>P0</string>
                			<key>Playlist Items</key><array><dict><key>Track ID</key><integer>101</integer></dict></array>
                		</dict>
                		<dict>
                			<key>Name</key><string>Mix</string>
                			<key>Playlist Persistent ID</key><string>P1</string>
                			<key>Parent Persistent ID</key><string>F1</string>
                			<key>Playlist Items</key><array>
                				<dict><key>Track ID</key><integer>102</integer></dict>
                				<dict><key>Track ID</key><integer>101</integer></dict>
                			</array>
                		</dict>
                		<dict>
                			<key>Name</key><string>Smart</string>
                			<key>Playlist Persistent ID</key><string>P2</string>
                			<key>Smart Info</key><data>AQ==</data>
                			<key>Smart Criteria</key><data>AQ==</data>
                		</dict>
                		<dict>
                			<key>Name</key><string>Folder</string>
                			<key>Playlist Persistent ID</key><string>F1</string>
                			<key>Folder</key><true/>
                		</dict>
                		<dict>
                			<key>Name</key><string>Music</string>
                			<key>Distinguished Kind</key><integer>4</integer>
                		</dict>
                	</array>
                </dict>
                </plist>
                """.replace("NFD", nfd);
        LibraryXml lib = parse(xml);
        assertEquals("/Users/me/Music/Media.localized/", lib.musicFolder());
        assertEquals(List.of("101", "102"), List.copyOf(lib.tracks().keySet()));
        LibraryXml.Track a = lib.tracks().get("101");
        assertEquals("Rock & Roll été", a.name());
        assertEquals("The <Band>", a.artist());
        assertEquals("Various", a.albumArtist());
        assertEquals("Hits", a.album());
        assertEquals("Rock", a.genre());
        assertEquals(3, a.track());
        assertEquals(12, a.trackCount());
        assertEquals(1, a.disc());
        assertEquals(2, a.discCount());
        assertEquals("2010-05-10", a.date());
        assertEquals(201000L, a.totalTimeMs());
        assertEquals(Instant.parse("2015-01-02T03:04:05Z").toEpochMilli(), a.dateAdded());
        assertEquals("AAC audio file", a.kind());
        assertTrue(a.loved());
        assertEquals("ABCDEF0123456789", a.persistentId());
        // percent-decoded, a literal + stays a plus
        assertEquals("/Users/me/Music/Media.localized/Music/The Band/Hits/03 Rock & Roll+1.m4a", a.location());

        LibraryXml.Track b = lib.tracks().get("102");
        assertEquals("1999", b.date()); // the release date's year isn't the Year
        assertTrue(b.loved()); // Favorited
        assertNull(b.track()); // 0 isn't a track number
        assertEquals("/Users/me/Music/X/" + nfd + ".mp3", b.location()); // NFD kept as written

        assertEquals(5, lib.playlists().size());
        LibraryXml.Playlist master = lib.playlists().get(0);
        assertTrue(master.master());
        assertFalse(master.regular());
        LibraryXml.Playlist mix = lib.playlists().get(1);
        assertTrue(mix.regular());
        assertEquals("F1", mix.parentPersistentId());
        assertEquals(List.of("102", "101"), mix.trackIds());
        assertTrue(lib.playlists().get(2).smart());
        assertTrue(lib.playlists().get(3).folder());
        assertTrue(lib.playlists().get(4).distinguished());
    }

    @Test
    void percentEncodedUtf8() {
        assertEquals("/a/Café ü/x.m4a", LibraryXml.decodeLocation("file:///a/Caf%C3%A9%20%C3%BC/x.m4a"));
        assertEquals("/a/100%", LibraryXml.decodeLocation("file:///a/100%"));
    }

    @Test
    void anExternalEntityIsRefusedAndNeverRead() throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE plist [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<plist><dict><key>Music Folder</key><string>&xxe;</string></dict></plist>";
        HttpError e = assertThrows(HttpError.class, () -> parse(xml));
        assertEquals("invalid_library_xml", e.code());
        assertFalse(e.getMessage().contains("TOP-SECRET"));
    }

    @Test
    void anInternalEntityIsRefused() {
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE plist [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>\n"
                + "<plist><dict><key>Music Folder</key><string>&b;</string></dict></plist>";
        HttpError e = assertThrows(HttpError.class, () -> parse(xml));
        assertEquals("invalid_library_xml", e.code());
    }

    @Test
    void anExternalDtdIsNotFetched() throws IOException {
        // A DOCTYPE naming a DTD that doesn't exist (and a host that doesn't resolve): skipped, not loaded.
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE plist SYSTEM \"http://nowhere.invalid/x.dtd\">\n"
                + "<plist><dict><key>Tracks</key><dict/></dict></plist>";
        assertTrue(parse(xml).tracks().isEmpty());
    }

    @Test
    void notAPlist() {
        assertEquals("invalid_library_xml", assertThrows(HttpError.class, () -> parse("<html></html>")).code());
        assertEquals("invalid_library_xml", assertThrows(HttpError.class, () -> parse("not xml")).code());
    }
}
