package io.siggi.togethertunes.library;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * A fake {@code tracksplitter} for tests ({@link MetadataLookup}): a shell script that records its
 * arguments, optionally sleeps, prints the JSON it was given and exits with the given code.
 */
public final class FakeTrackSplitter {
    public final Path dir;
    public final Path script;
    public final Path cacheDir;

    public FakeTrackSplitter(Path dir) throws IOException {
        this.dir = Files.createDirectories(dir).toAbsolutePath();
        this.cacheDir = Files.createDirectories(this.dir.resolve("cache"));
        this.script = this.dir.resolve("tracksplitter");
        String d = this.dir.toString().replace("'", "'\\''");
        Files.writeString(script, "#!/bin/sh\n"
                + "d='" + d + "'\n"
                + ": > \"$d/args.tmp\"\n"
                + "for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$d/args.tmp\"; done\n"
                + "mv \"$d/args.tmp\" \"$d/args.txt\"\n"
                + "echo x >> \"$d/calls\"\n"
                + "if [ -f \"$d/sleep\" ]; then sleep \"$(cat \"$d/sleep\")\"; fi\n"
                + "echo 'looking it up' >&2\n"
                + "cat \"$d/out.json\"\n"
                + "exit \"$(cat \"$d/exit\")\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        respond("{\"tracks\":[],\"notices\":[]}", 1);
    }

    /** What the next runs print and their exit code. */
    public FakeTrackSplitter respond(String json, int exit) throws IOException {
        Files.writeString(dir.resolve("out.json"), json);
        Files.writeString(dir.resolve("exit"), Integer.toString(exit));
        return this;
    }

    /** Each run sleeps this many seconds first (0: not at all). */
    public FakeTrackSplitter sleep(double seconds) throws IOException {
        if (seconds <= 0) {
            Files.deleteIfExists(dir.resolve("sleep"));
        } else {
            Files.writeString(dir.resolve("sleep"), Double.toString(seconds));
        }
        return this;
    }

    /** The last run's arguments. */
    public List<String> lastArgs() throws IOException {
        return Files.readAllLines(dir.resolve("args.txt"));
    }

    /** How many times it ran. */
    public int calls() throws IOException {
        Path f = dir.resolve("calls");
        return Files.isRegularFile(f) ? Files.readAllLines(f).size() : 0;
    }

    /** A JPEG of {@code width}×{@code height} in its cache's {@code artwork/} directory. */
    public Path artwork(String name, int width, int height) throws IOException {
        Path out = Files.createDirectories(cacheDir.resolve("artwork")).resolve(name);
        writeJpeg(out, width, height);
        return out;
    }

    /** Writes a plain JPEG. */
    public static void writeJpeg(Path out, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | 0x40);
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(img, "jpg", out.toFile());
    }

    /** TrackSplitter's JSON for one found track (Wonderful★opportunity!'s example), cover {@code artwork}. */
    public static String singleTrack(Path artwork, String matchedBy) {
        return "{\"tracks\":[{\"tags\":{\"title\":\"Good is YEAH! (feat. Kagamine Rin & Kagamine Len)\","
                + "\"artist\":\"Wonderful★opportunity!, Kagamine Rin & Kagamine Len\","
                + "\"artists\":[\"Wonderful★opportunity!\",\"Kagamine Rin & Kagamine Len\"],"
                + "\"album\":\"Good is YEAH! (feat. Kagamine Rin & Kagamine Len)\","
                + "\"albumArtist\":\"Wonderful★opportunity!\",\"track\":1,\"trackTotal\":2,"
                + "\"isrc\":\"JPVI02201530\",\"date\":\"2022-06-29\","
                + "\"copyright\":\"Crypton Future Media, INC. www.piapro.net\",\"label\":\"FlyingDog\","
                + "\"barcode\":\"4573556731873\",\"genre\":\"J-Pop\"},"
                + (artwork == null ? "" : "\"artwork\":" + com.google.gson.JsonParser.parseString(
                        "\"" + artwork.toString().replace("\\", "\\\\") + "\"") + ",")
                + "\"album\":{\"source\":\"Catalogue\",\"id\":\"0D8\",\"url\":\"https://catalogue.example/album/0D8\","
                + "\"artist\":\"Wonderful★opportunity!\",\"title\":\"Good is YEAH!\",\"matchedBy\":\"" + matchedBy
                + "\",\"namesOnly\":false}}],\"notices\":[]}";
    }

    /** TrackSplitter's JSON for a lookup without an album: two albums. */
    public static String albumList() {
        return "{\"tracks\":["
                + "{\"tags\":{\"title\":\"Wavin' Flag\",\"artist\":\"K'naan\",\"album\":\"Troubadour\","
                + "\"albumArtist\":\"K'naan\",\"track\":7,\"trackTotal\":18,\"date\":\"2009-02-24\"},"
                + "\"artwork\":\"https://images.example/1.jpg\",\"album\":{\"source\":\"Catalogue\",\"id\":\"1\","
                + "\"url\":\"https://catalogue.example/1\",\"artist\":\"K'naan\",\"title\":\"Troubadour\","
                + "\"matchedBy\":\"name\",\"namesOnly\":true}},"
                + "{\"tags\":{\"title\":\"Wavin' Flag\",\"artist\":\"K'naan\","
                + "\"album\":\"Wavin' Flag (Celebration Mix) - EP\",\"albumArtist\":\"K'naan\",\"track\":4,"
                + "\"trackTotal\":4},\"artwork\":\"https://images.example/2.jpg\",\"album\":{\"source\":\"Other\","
                + "\"id\":\"2\",\"url\":\"https://other.example/2\",\"artist\":\"K'naan\","
                + "\"title\":\"Wavin' Flag (Celebration Mix) - EP\",\"matchedBy\":\"name\",\"namesOnly\":true}}"
                + "],\"notices\":[]}";
    }
}
