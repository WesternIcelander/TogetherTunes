package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PathPatternTest {
    @Test
    void literals() {
        PathPattern p = PathPattern.parse("/api/me");
        assertEquals(Map.of(), p.match("/api/me"));
        assertNull(p.match("/api/me/"));
        assertNull(p.match("/api/Me"));
        assertNull(p.match("/api"));
        assertNull(p.match("/api/me/x"));
        assertNull(p.match("api/me"));
        assertEquals(Map.of(), PathPattern.parse("/").match("/"));
        assertNull(PathPattern.parse("/").match("/x"));
    }

    @Test
    void params() {
        PathPattern p = PathPattern.parse("/api/rooms/{id}/members/{userId}");
        assertEquals(Map.of("id", "r1", "userId", "u 2"), p.match("/api/rooms/r1/members/u 2"));
        assertNull(p.match("/api/rooms//members/u"));
        assertNull(p.match("/api/rooms/r1/members"));
        assertNull(p.match("/api/rooms/r1/members/u/x"));
    }

    @Test
    void rest() {
        PathPattern p = PathPattern.parse("/web/{path...}");
        assertEquals(Map.of("path", "js/main.js"), p.match("/web/js/main.js"));
        assertEquals(Map.of("path", ""), p.match("/web/"));
        assertEquals(Map.of("path", "a//b/"), p.match("/web/a//b/"));
        assertNull(p.match("/web"));
        assertNull(p.match("/webx/a"));
    }

    @Test
    void malformedPatterns() {
        for (String bad : List.of("", "api", "/a/{x", "/a/x{y}", "/a/{1x}", "/a/{x}/{x}", "/{rest...}/a", "/a//b")) {
            assertThrows(IllegalArgumentException.class, () -> PathPattern.parse(bad), bad);
        }
    }

    @Test
    void specificity() {
        List<PathPattern> patterns = new ArrayList<>(List.of(
                PathPattern.parse("/{a...}"),
                PathPattern.parse("/api/{x}"),
                PathPattern.parse("/api/{a...}"),
                PathPattern.parse("/api/me"),
                PathPattern.parse("/{x}/me")));
        Collections.shuffle(patterns);
        Collections.sort(patterns);
        assertEquals(List.of("/api/me", "/api/{x}", "/api/{a...}", "/{x}/me", "/{a...}"),
                patterns.stream().map(PathPattern::toString).toList());
    }

    @Test
    void sameShapeIgnoresParamNames() {
        assertTrue(PathPattern.parse("/a/{x}").sameShape(PathPattern.parse("/a/{y}")));
        assertFalse(PathPattern.parse("/a/{x}").sameShape(PathPattern.parse("/a/b")));
        assertFalse(PathPattern.parse("/a/{x}").sameShape(PathPattern.parse("/a/{x...}")));
    }
}
