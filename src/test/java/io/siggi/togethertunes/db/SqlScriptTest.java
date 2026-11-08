package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlScriptTest {
    @Test
    void splitsOnSemicolonsAndDropsEmptyStatements() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlScript.split(" SELECT 1;\n;\n SELECT 2 ;  \n"));
        assertEquals(List.of("SELECT 1"), SqlScript.split("SELECT 1"));
        assertEquals(List.of(), SqlScript.split("  -- only a comment\n"));
    }

    @Test
    void ignoresSemicolonsInCommentsAndQuotes() {
        assertEquals(List.of("SELECT 'a;''b'", "SELECT \"x;y\"", "SELECT `p;q`", "SELECT 3"),
                SqlScript.split("SELECT 'a;''b'; -- c;d\nSELECT \"x;y\"; /* e;\nf */ SELECT `p;q`; SELECT 3"));
    }

    @Test
    void commentsBecomeSpaces() {
        assertEquals(List.of("SELECT 1 FROM t"), SqlScript.split("SELECT/* x */1 FROM t -- y"));
    }

    @Test
    void escapeStrings() {
        assertEquals(List.of("SELECT E'it\\'s;'", "SELECT 2"), SqlScript.split("SELECT E'it\\'s;'; SELECT 2"));
        // A plain string doesn't treat the backslash as an escape.
        assertEquals(List.of("SELECT 'a\\'", "SELECT 2"), SqlScript.split("SELECT 'a\\'; SELECT 2"));
    }

    @Test
    void dollarQuoting() {
        String fn = "CREATE FUNCTION f() RETURNS int AS $fn$ BEGIN RETURN 1; END; $fn$ LANGUAGE plpgsql";
        assertEquals(List.of(fn, "DO $$ BEGIN NULL; END $$"), SqlScript.split(fn + ";\nDO $$ BEGIN NULL; END $$;"));
        // $1 is a parameter, not a quote; a$b$ is an identifier.
        assertEquals(List.of("SELECT $1", "SELECT a$b$"), SqlScript.split("SELECT $1; SELECT a$b$;"));
    }

    @Test
    void sqliteTriggerBodies() {
        String trigger = "CREATE TEMP TRIGGER t AFTER INSERT ON a WHEN (CASE WHEN 1 THEN 1 END) BEGIN "
                + "UPDATE a SET n = CASE WHEN n > 0 THEN 1 ELSE 2 END; DELETE FROM b; END";
        assertEquals(List.of(trigger, "SELECT 1"), SqlScript.split(trigger + "; SELECT 1;"));
        // BEGIN outside a trigger is a normal statement.
        assertEquals(List.of("BEGIN", "COMMIT"), SqlScript.split("BEGIN; COMMIT;"));
    }

    @Test
    void unterminatedInputIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> SqlScript.split("SELECT 'abc"));
        assertThrows(IllegalArgumentException.class, () -> SqlScript.split("SELECT 1 /* x"));
        assertThrows(IllegalArgumentException.class, () -> SqlScript.split("DO $$ BEGIN"));
        assertThrows(IllegalArgumentException.class, () -> SqlScript.split("CREATE TRIGGER t AFTER INSERT ON a BEGIN SELECT 1;"));
    }
}
