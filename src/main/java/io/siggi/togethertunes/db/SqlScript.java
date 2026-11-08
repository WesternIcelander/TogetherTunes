package io.siggi.togethertunes.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a SQL script (a migration file) into single statements, because neither JDBC driver
 * reliably runs several statements in one {@code execute}.
 *
 * <p>Statements end at a {@code ;} outside of quotes and comments. Understood: {@code --} and
 * {@code /* *}{@code /} comments (dropped), {@code '...'} strings (with {@code ''}), {@code
 * E'...'} strings with backslash escapes, {@code "..."} and {@code `...`} identifiers, and
 * PostgreSQL dollar quoting ({@code $$...$$}, {@code $tag$...$tag$}). A SQLite {@code CREATE
 * TRIGGER} keeps the semicolons inside its {@code BEGIN ... END} body (and {@code CASE ...
 * END} inside it). Empty statements are skipped.
 */
final class SqlScript {
    private SqlScript() {
    }

    /** The statements of {@code script}, without the trailing {@code ;} and comments. */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // Trigger tracking: the statement's leading words, and BEGIN/CASE nesting inside a trigger.
        List<String> leadingWords = new ArrayList<>();
        boolean trigger = false;
        int blockDepth = 0;

        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : '\0';

            if (c == '-' && next == '-') {
                int end = script.indexOf('\n', i);
                i = end < 0 ? n : end;
                appendSpace(current);
                continue;
            }
            if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated /* comment");
                }
                i = end + 2;
                appendSpace(current);
                continue;
            }
            if (c == '\'') {
                boolean backslashEscapes = i > 0 && (script.charAt(i - 1) == 'E' || script.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentifierPart(script.charAt(i - 2)));
                int end = endOfQuoted(script, i, '\'', backslashEscapes);
                current.append(script, i, end);
                i = end;
                continue;
            }
            if (c == '"' || c == '`') {
                int end = endOfQuoted(script, i, c, false);
                current.append(script, i, end);
                i = end;
                continue;
            }
            if (c == '$' && (i == 0 || !isIdentifierPart(script.charAt(i - 1)))) {
                String tag = dollarTag(script, i);
                if (tag != null) {
                    int close = script.indexOf(tag, i + tag.length());
                    if (close < 0) {
                        throw new IllegalArgumentException("Unterminated dollar-quoted string " + tag);
                    }
                    int end = close + tag.length();
                    current.append(script, i, end);
                    i = end;
                    continue;
                }
            }
            if (isIdentifierStart(c) && (i == 0 || !isIdentifierPart(script.charAt(i - 1)))) {
                int end = i + 1;
                while (end < n && isIdentifierPart(script.charAt(end))) {
                    end++;
                }
                String word = script.substring(i, end).toUpperCase(Locale.ROOT);
                if (leadingWords.size() < 4) {
                    leadingWords.add(word);
                    trigger = isCreateTrigger(leadingWords);
                }
                if (trigger) {
                    switch (word) {
                        case "BEGIN", "CASE" -> blockDepth++;
                        case "END" -> blockDepth = Math.max(0, blockDepth - 1);
                        default -> {
                        }
                    }
                }
                current.append(script, i, end);
                i = end;
                continue;
            }
            if (c == ';' && blockDepth == 0) {
                addStatement(out, current);
                current.setLength(0);
                leadingWords.clear();
                trigger = false;
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        if (blockDepth > 0) {
            throw new IllegalArgumentException("CREATE TRIGGER without a matching END");
        }
        addStatement(out, current);
        return out;
    }

    private static boolean isCreateTrigger(List<String> words) {
        // CREATE [TEMP|TEMPORARY] TRIGGER
        if (words.isEmpty() || !words.get(0).equals("CREATE")) {
            return false;
        }
        for (int k = 1; k < words.size(); k++) {
            String w = words.get(k);
            if (w.equals("TRIGGER")) {
                return true;
            }
            if (!w.equals("TEMP") && !w.equals("TEMPORARY")) {
                return false;
            }
        }
        return false;
    }

    private static void addStatement(List<String> out, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            out.add(statement);
        }
    }

    private static void appendSpace(StringBuilder current) {
        if (!current.isEmpty() && !Character.isWhitespace(current.charAt(current.length() - 1))) {
            current.append(' ');
        }
    }

    /** Index just after the closing quote of the quoted text starting at {@code start}. */
    private static int endOfQuoted(String s, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2; // doubled quote
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("Unterminated " + quote + " quote starting at offset " + start);
    }

    /** The dollar-quote tag ({@code $$} or {@code $name$}) starting at {@code start}, or null. */
    private static String dollarTag(String s, int start) {
        int i = start + 1;
        if (i < s.length() && s.charAt(i) == '$') {
            return "$$";
        }
        if (i >= s.length() || !isIdentifierStart(s.charAt(i))) {
            return null;
        }
        while (i < s.length() && isIdentifierPart(s.charAt(i)) && s.charAt(i) != '$') {
            i++;
        }
        if (i < s.length() && s.charAt(i) == '$') {
            return s.substring(start, i + 1);
        }
        return null;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }
}
