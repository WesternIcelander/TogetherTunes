package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.HttpError;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuestNameRulesTest {
    private static void assertInvalid(String name) {
        HttpError e = assertThrows(HttpError.class, () -> GuestNameRules.normalise(name), () -> "accepted " + name);
        assertEquals(400, e.status());
        assertEquals("invalid_name", e.code());
    }

    @Test
    void trimsCollapsesAndNormalises() {
        assertEquals("Night Owl", GuestNameRules.normalise("  Night \t\n Owl  "));
        assertEquals("Night Owl", GuestNameRules.normalise("Night\u00a0\u3000Owl"));
        // NFKC: full-width letters, ligatures, styled maths letters become plain ones.
        assertEquals("Siggi", GuestNameRules.normalise("\uff33\uff49\uff47\uff47\uff49"));
        assertEquals("fine", GuestNameRules.normalise("\ufb01ne"));
        assertEquals("Abc", GuestNameRules.normalise("\ud835\udc00\ud835\udc1b\ud835\udc1c"));
        // Decomposed accents are composed.
        assertEquals("Jos\u00e9", GuestNameRules.normalise("Jose\u0301"));
    }

    @Test
    void length() {
        assertEquals("Ab", GuestNameRules.normalise("Ab"));
        assertEquals(32, GuestNameRules.normalise("z".repeat(32)).length());
        assertInvalid("a");
        assertInvalid(" a ");
        assertInvalid("");
        assertInvalid(null);
        assertInvalid("z".repeat(33));
        // Counted in code points, after NFKC: 16 astral letters are 16 characters.
        assertEquals(16, GuestNameRules.normalise("\ud801\udc00".repeat(16)).codePointCount(0, 32));
        // A ligature that expands past 32 characters after NFKC.
        assertInvalid("a".repeat(30) + "\ufb03");
    }

    @Test
    void allowedCharacters() {
        for (String ok : List.of("Quiet Walrus 2", "O'Brien", "O\u2019Brien", "Jean-Luc", "dj_mix", "Hey!",
                "Who?", "Rock & Roll", "Mr. T", "A, B", "(Band)", "Re: Mix", "We're #1", "C++", "~Wave~",
                "\u00dcn\u00efc\u00f6d\u00e9", "\u0416\u0435\u043d\u044f", "\u5f20\u4f1f", "\u0645\u062d\u0645\u062f",
                "\u0928\u092e\u0938\u094d\u0924\u0947", "\u0e2a\u0e27\u0e31\u0e2a\u0e14\u0e35", "42")) {
            assertEquals(ok, GuestNameRules.normalise(ok));
        }
        for (String bad : List.of("a\u200bb", "a\u0007b", "a\u202eb", "<script>", "a@b", "a/b", "a\"b",
                "a`b", "a\\b", "a|b", "a=b", "a$b", "a%b", "a*b", "[ab]", "{ab}", "a\ud83c\udfb5b", "...",
                "!!", "- -", "a\ue000b", "\u0301ab", "a \u0301b", "a.\u0301b",
                "x\u0323\u0302\u0303\u0304")) {
            assertInvalid(bad);
        }
        // Up to three marks on one character.
        assertEquals(5, GuestNameRules.normalise("ax\u0323\u0302\u0303").length());
    }

    @Test
    void keyIgnoresCaseWidthAndApostrophes() {
        assertEquals(GuestNameRules.key("Siggi"), GuestNameRules.key("SIGGI"));
        assertEquals(GuestNameRules.key("Siggi"), GuestNameRules.key("\uff33\uff49\uff47\uff47\uff49"));
        assertEquals(GuestNameRules.key("Stra\u00dfe"), GuestNameRules.key("STRASSE"));
        assertEquals(GuestNameRules.key("O'Brien"), GuestNameRules.key("o\u2019brien"));
        assertEquals(GuestNameRules.key("DJ  Sig"), GuestNameRules.key(" dj sig "));
        assertEquals(GuestNameRules.key("Jos\u00e9"), GuestNameRules.key("JOSE\u0301"));
        assertFalse(GuestNameRules.key("Siggi").equals(GuestNameRules.key("Siggi2")));
    }

    @Test
    void blocklist() {
        List<String> list = List.of("badword", "Two Words", "!!!");
        assertTrue(GuestNameRules.blocked("BadWord", list));
        assertTrue(GuestNameRules.blocked("xxbadwordxx", list));
        assertTrue(GuestNameRules.blocked("b.a.d w-o-r-d", list));
        assertTrue(GuestNameRules.blocked("\uff42\uff41\uff44word", list));
        assertTrue(GuestNameRules.blocked("twowords", list));
        assertFalse(GuestNameRules.blocked("Bad Wolf", list));
        // An entry without letters or digits matches nothing.
        assertFalse(GuestNameRules.blocked("Hey!!!", list));
        assertFalse(GuestNameRules.blocked("anything", List.of()));
    }
}
