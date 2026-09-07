package dev.reception.business;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * What a Business looks like the moment registration creates it.
 *
 * <p>Gathered in one file so "what does a new account start with" is a question with one answer.
 * The booking settings are duplicated as column defaults in {@code V3__businesses.sql}; that copy
 * is what makes the migration able to backfill rows registration had already created, and this one
 * is what a reader of the code finds.
 */
public final class BusinessDefaults {

    private BusinessDefaults() {}

    /**
     * Until the owner sets their own. UTC is the honest placeholder: it is visibly not a guess
     * about where they are, unlike the server's zone, which would be one.
     */
    public static final ZoneId TIMEZONE = ZoneId.of("UTC");

    public static final String CURRENCY = "USD";

    /**
     * Monday to Friday, 09:00-17:00.
     *
     * <p>Defaults exist so a new account is never a blank slate the owner has to decode. The
     * weekend is deliberately absent rather than present-and-closed: a day with no row <em>is</em>
     * closed, and seeding contradictory rows would teach the opposite (docs/03-data-model.md).
     */
    public static final LocalTime OPENS_AT = LocalTime.of(9, 0);

    public static final LocalTime CLOSES_AT = LocalTime.of(17, 0);

    public static final List<DayOfWeek> OPEN_DAYS = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    /** Booking policy, per docs/01-prd.md FR-2. */
    public static final int SLOT_INTERVAL_MINUTES = 15;

    public static final int MIN_LEAD_TIME_MINUTES = 60;
    public static final int MAX_ADVANCE_DAYS = 60;
    public static final int CANCELLATION_WINDOW_HOURS = 24;

    public static final boolean AI_ENABLED = true;
    public static final int AI_DAILY_COST_CAP_CENTS = 500;
}
