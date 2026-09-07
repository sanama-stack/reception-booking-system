package dev.reception.business.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Request bodies for {@code /business/*}.
 *
 * <p>Bean Validation covers shape only — presence, length, range. Whether {@code Europe/Atlantis} is
 * a place, or whether a slug is already taken, needs the JDK's registries or the database and lives
 * in the application service (docs/06-security.md §7).
 *
 * <p>None of these carries a {@code businessId}, and none ever may: the tenant is derived from the
 * Membership, and {@code TenantRepositoryShapeTest} fails the build over a field with that name.
 */
public final class BusinessRequests {

    private BusinessRequests() {}

    /**
     * A partial update. Every field is optional, and {@code null} means "leave this alone".
     *
     * <p>An optional text field is cleared by sending {@code ""} — which is exactly what a form
     * sends when the owner empties the input, so clearing needs no special gesture. The four
     * required fields use {@code @Size(min = 1)} instead of {@code @NotBlank}, which gives precisely
     * the rule wanted: absent is fine, blank is not.
     */
    public record PatchBusiness(
            @Size(min = 1, max = 120, message = "Business names can be at most 120 characters.") String name,
            @Size(min = 1, max = 140, message = "That web address is too long.") String slug,
            @Size(min = 1, max = 64, message = "That is not a timezone we recognise.") String timezone,
            @Size(min = 3, max = 3, message = "Use a three-letter currency code, such as USD.") String currency,
            @Size(max = 5000, message = "The description can be at most 5000 characters.") String description,
            @Size(max = 200, message = "The address can be at most 200 characters.") String addressLine,
            @Size(max = 120, message = "The city can be at most 120 characters.") String city,
            @Size(max = 2, message = "Use a two-letter country code, such as GE.") String country,
            @Size(max = 20, message = "That phone number is too long.") String phone,
            @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email,
            @Size(max = 300, message = "That web address is too long.") String website,

            // Ranges restate docs/01-prd.md FR-2 and the CHECK constraints. Stated here as well so
            // the owner gets a sentence rather than a constraint-violation 500.
            @Min(value = 5, message = "Choose 5, 10, 15, 20, 30 or 60 minutes.")
                    @Max(value = 60, message = "Choose 5, 10, 15, 20, 30 or 60 minutes.")
                    Integer slotIntervalMinutes,
            @Min(value = 0, message = "Notice cannot be negative.")
                    @Max(value = 10080, message = "Notice can be at most one week.")
                    Integer minLeadTimeMinutes,
            @Min(value = 1, message = "Customers must be able to book at least one day ahead.")
                    @Max(value = 365, message = "Bookings can be taken at most a year ahead.")
                    Integer maxAdvanceDays,
            @Min(value = 0, message = "The cancellation window cannot be negative.")
                    @Max(value = 168, message = "The cancellation window can be at most one week.")
                    Integer cancellationWindowHours,
            @Size(max = 5000, message = "The cancellation policy can be at most 5000 characters.")
                    String cancellationPolicy,
            Boolean aiEnabled,
            @Size(max = 2000, message = "This can be at most 2000 characters.") String aiAdditionalInfo,
            @Min(value = 0, message = "The daily limit cannot be negative.")
                    @Max(value = 1000000, message = "The daily limit is too high.")
                    Integer aiDailyCostCapCents) {

        /**
         * The slot interval is a choice from a list, not a range, so {@code @Min}/{@code @Max} is
         * not the rule — 7 minutes is between 5 and 60 and would produce a grid that drifts across
         * the day. Expressed as an assertion because Bean Validation has no "one of these" for a
         * number.
         */
        @AssertTrue(message = "Choose 5, 10, 15, 20, 30 or 60 minutes.")
        public boolean isSlotIntervalOneOfTheAllowedValues() {
            return slotIntervalMinutes == null || List.of(5, 10, 15, 20, 30, 60).contains(slotIntervalMinutes);
        }
    }

    /**
     * The whole week, replacing whatever is there.
     *
     * <p>An empty list is legitimate and means the business is closed every day. It is not the same
     * as omitting the field, which is a malformed request.
     */
    public record ReplaceHours(
            @NotNull(message = "Send the whole week, even if it is empty.")
                    @Size(max = 70, message = "That is more intervals than a week can hold.")
                    List<@Valid HoursInterval> hours) {}

    public record HoursInterval(
            @NotNull(message = "Choose a day.")
                    @Min(value = 1, message = "Days run from 1 (Monday) to 7 (Sunday).")
                    @Max(value = 7, message = "Days run from 1 (Monday) to 7 (Sunday).")
                    Integer dayOfWeek,
            @NotNull(message = "Enter an opening time.") LocalTime opensAt,
            @NotNull(message = "Enter a closing time.") LocalTime closesAt) {}

    /**
     * Dates, not instants — and they are calendar dates in the <em>business's</em> timezone. The
     * owner picks days on a calendar; converting them into a span of time is the server's job,
     * because only the server knows the zone at the moment of the write (ADR-0003).
     */
    public record CreateClosure(
            @NotNull(message = "Choose the first day you are closed.") LocalDate startDate,
            @NotNull(message = "Choose the last day you are closed.") LocalDate endDate,
            @Size(max = 200, message = "The reason can be at most 200 characters.") String reason) {}

    public record CreateFaq(
            @NotBlank(message = "Enter a question.")
                    @Size(max = 300, message = "Questions can be at most 300 characters.")
                    String question,
            @NotBlank(message = "Enter an answer.")
                    @Size(max = 1000, message = "Answers can be at most 1000 characters.")
                    String answer,
            @Min(value = 0, message = "The position cannot be negative.") Integer sortOrder) {}

    /** Partial, like {@link PatchBusiness}: {@code null} leaves a field alone. */
    public record PatchFaq(
            @Size(min = 1, max = 300, message = "Questions can be at most 300 characters.") String question,
            @Size(min = 1, max = 1000, message = "Answers can be at most 1000 characters.") String answer,
            @Min(value = 0, message = "The position cannot be negative.") Integer sortOrder) {}
}
