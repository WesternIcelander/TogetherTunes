package io.siggi.togethertunes.settings;

import java.util.List;

/**
 * A list-of-strings setting value (e.g. {@code guests.nameBlocklist}). Stored as a JSON array of
 * strings; {@link SettingKey#stringList} trims and collapses each entry's whitespace, drops blank
 * entries and exact duplicates, and limits the count and length.
 */
public record StringList(List<String> items) {
    /** The empty list. */
    public static final StringList EMPTY = new StringList(List.of());

    public StringList {
        items = List.copyOf(items);
    }
}
