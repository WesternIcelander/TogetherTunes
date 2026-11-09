/**
 * Retention ({@code docs/libraries.md#retention}): {@link io.siggi.togethertunes.retention.References}
 * maintains {@code items.last_referenced_at}; the hourly
 * {@link io.siggi.togethertunes.retention.RetentionSweeper} purges unreferenced items after
 * {@code retention.unreferencedDays}.
 */
package io.siggi.togethertunes.retention;
