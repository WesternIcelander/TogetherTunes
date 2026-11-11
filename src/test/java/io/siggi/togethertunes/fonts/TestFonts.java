package io.siggi.togethertunes.fonts;

import io.siggi.togethertunes.settings.CjkVariant;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/** Small real fonts from the test resources (Noto Sans Thai and Lao, OFL) and manifest entries for them. */
final class TestFonts {
    private TestFonts() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = TestFonts.class.getResourceAsStream("/fonts/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] thai() {
        return bytes("NotoSansThai-Regular.ttf");
    }

    static byte[] lao() {
        return bytes("NotoSansLao-Regular.ttf");
    }

    static Font font(byte[] bytes) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A manifest entry for {@code bytes} at {@code url}, for {@code scripts}. */
    static FontManifest.Entry entry(String id, String file, byte[] bytes, String url, Character.UnicodeScript... scripts) {
        Set<Character.UnicodeScript> s = scripts.length == 0 ? EnumSet.noneOf(Character.UnicodeScript.class)
                : EnumSet.of(scripts[0], scripts);
        return new FontManifest.Entry(id, "Test " + id, file, bytes.length, sha256(bytes), URI.create(url),
                "OFL-notofonts.txt", s, null, new int[0]);
    }

    /** A CJK entry of {@code variant} (never downloaded in tests). */
    static FontManifest.Entry cjk(CjkVariant variant, String url) {
        return new FontManifest.Entry("cjk-" + variant.wireName(), "Test CJK " + variant, "cjk-" + variant.wireName()
                + ".otf", 1000, "0".repeat(64), URI.create(url), "OFL-noto-cjk.txt",
                FontManifest.CJK_SCRIPTS, variant, new int[0]);
    }
}
