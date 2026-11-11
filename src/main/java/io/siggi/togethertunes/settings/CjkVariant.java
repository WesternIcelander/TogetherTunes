package io.siggi.togethertunes.settings;

import java.util.Locale;

/**
 * A regional variant of the CJK script fonts ({@code fonts.cjkVariant},
 * {@code docs/library-and-ingest.md#card-fonts}): the same Han character is drawn differently in
 * Japanese, Korean, Simplified and Traditional Chinese. Text with kana is drawn with
 * {@link #JP}, text with hangul with {@link #KR}, text with bopomofo with {@link #TC}, and Han
 * alone with the admin's preferred variant.
 */
public enum CjkVariant {
    JP, KR, SC, TC;

    /** The stored form: {@code jp}, {@code kr}, {@code sc} or {@code tc}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
