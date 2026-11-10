package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The software itself never names third-party products (CLAUDE.md; Siggi, 2026-11-10): how to use
 * the stream URLs in a particular game or with a particular RTMP service belongs in the external
 * docs ({@code docs/user-guide.md}).
 *
 * <p>Covers the web UI sources ({@code web/src/**.ts}), the static assets ({@code web/static/**}:
 * HTML and CSS) and the Java sources ({@code src/main/java/**.java}). Comments are removed first
 * (they may keep the names); everything else (string literals, HTML text and attributes, and also
 * identifiers) must not contain {@link #NAMES}. The comment stripper is a simple scanner that knows
 * {@code //} and {@code /* *}{@code /} comments, HTML {@code <!-- -->} comments and quoted strings
 * ({@code "}, {@code '}, {@code `}, Java text blocks); it doesn't know TypeScript regex literals, so a
 * regex containing a quote or {@code //} could hide the rest of its line (a missed match, never a
 * false one).
 */
class ProductNamesTest {
    /** The names the UI must not contain. Case-insensitive, except ETS2/ATS-style abbreviations. */
    static final Pattern NAMES = Pattern.compile(
            "(?i:vrchat|vrcdn|euro\\s*truck|american\\s*truck)|\\bETS2\\b|\\bATS2?\\b");

    /** Removes {@code //} and block comments from JS/TS/Java/CSS source, keeping strings and newlines. */
    static String stripCodeComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("\"\"\"", i)) { // Java text block
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\') j++;
                    else if (src.charAt(j) == '\n' && c != '`') break; // unterminated: stop at the line end
                    j++;
                }
                j = Math.min(n, j + 1);
                out.append(src, i, j);
                i = j;
            } else if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(src.substring(i, end).replaceAll("[^\\n]", " "));
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Removes {@code <!-- -->} comments from HTML, keeping newlines. */
    static String stripHtmlComments(String html) {
        Matcher m = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[^\\n]", " ")));
        m.appendTail(sb);
        return sb.toString();
    }

    /** One line per product name found in {@code text} (comments already removed). */
    static List<String> productNames(String file, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = NAMES.matcher(lines[i]);
            while (m.find()) found.add(file + ":" + (i + 1) + ": " + m.group());
        }
        return found;
    }

    static List<String> scanFile(Path root, Path p) throws IOException {
        String name = root.relativize(p).toString().replace('\\', '/');
        String text = Files.readString(p, StandardCharsets.UTF_8);
        // HTML text keeps "//" (URLs) and apostrophes, so only HTML comments are removed there.
        String stripped = name.endsWith(".html") ? stripHtmlComments(text) : stripCodeComments(text);
        return productNames(root.getFileName() + "/" + name, stripped);
    }

    private static List<String> scan(Path root, String... extensions) throws IOException {
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                String s = p.toString();
                boolean match = false;
                for (String ext : extensions) match |= s.endsWith(ext);
                if (!match) continue;
                files++;
                found.addAll(scanFile(root, p));
            }
        }
        assertTrue(files > 0, "no " + String.join("/", extensions) + " files under " + root);
        return found;
    }

    @Test
    void scannerIgnoresCommentsButFindsStringsAndText() {
        assertEquals(List.of(), productNames("a.ts", stripCodeComments(
                "// in VRChat\nconst a = 1; /* VRCDN\n ETS2 */ const u = \"http://x\"; // VRChat")));
        assertEquals(List.of("a.ts:1: VRChat"), productNames("a.ts", stripCodeComments(
                "const t = \"Watch in VRChat\"; // fine")));
        assertEquals(List.of("a.ts:2: VRCDN"), productNames("a.ts", stripCodeComments(
                "const u = \"http://x\";\nconst t = `e.g. VRCDN`;")));
        assertEquals(List.of("A.java:2: Euro Truck"), productNames("A.java", stripCodeComments(
                "String s = \"\"\"\n  Euro Truck // it's \"quoted\"\n  \"\"\"; // ETS2")));
        assertEquals(List.of("a.ts:1: VRCHAT"), productNames("a.ts", stripCodeComments(
                "export const VRCHAT_NOTE = 1;")));
        assertEquals(List.of("a.html:2: ETS2"), productNames("a.html", stripHtmlComments(
                "<!-- VRChat -->\n<p>Play it in ETS2</p>")));
        // ordinary words that contain the letters are fine
        assertEquals(List.of(), productNames("a.ts", "const stats = 1; // Beats, data, HLS"));
    }

    @Test
    void webSourcesDoNotNameThirdPartyProducts() throws IOException {
        List<String> found = scan(Path.of("web", "src"), ".ts");
        assertFalse(found.size() > 0, "third-party product names in web/src:\n  " + String.join("\n  ", found));
    }

    @Test
    void staticAssetsDoNotNameThirdPartyProducts() throws IOException {
        List<String> found = scan(Path.of("web", "static"), ".html", ".css", ".js", ".json", ".svg", ".txt");
        assertFalse(found.size() > 0, "third-party product names in web/static:\n  " + String.join("\n  ", found));
    }

    @Test
    void javaSourcesDoNotNameThirdPartyProducts() throws IOException {
        List<String> found = scan(Path.of("src", "main", "java"), ".java");
        assertFalse(found.size() > 0,
                "third-party product names in Java strings or code:\n  " + String.join("\n  ", found));
    }
}
