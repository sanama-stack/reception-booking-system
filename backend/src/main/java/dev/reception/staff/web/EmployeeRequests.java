package dev.reception.staff.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies for {@code /employees/*}.
 *
 * <p>The phone bound is generous because it applies to what a person typed, not to what is stored:
 * {@code +995 555 12 34 56} is longer than the E.164 form it normalises to, and rejecting it for
 * length would be rejecting the spacing.
 */
public final class EmployeeRequests {

    private EmployeeRequests() {}

    public record CreateEmployee(
            @NotBlank(message = "Enter a name.")
                    @Size(max = 120, message = "Names can be at most 120 characters.")
                    String fullName,
            @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email,
            @Size(max = 40, message = "That phone number is too long.") String phone,
            @Size(max = 120, message = "The job title can be at most 120 characters.") String jobTitle) {}

    /** Partial: {@code null} leaves a field alone, blank clears an optional one. */
    public record PatchEmployee(
            @Size(min = 1, max = 120, message = "Names can be at most 120 characters.") String fullName,
            @Size(max = 254, message = "That email address is too long.") String email,
            @Size(max = 40, message = "That phone number is too long.") String phone,
            @Size(max = 120, message = "The job title can be at most 120 characters.") String jobTitle) {}

    /**
     * The whole service set, replacing whatever is there. Empty is legitimate — a new employee who
     * has not been assigned anything yet.
     */
    public record ReplaceServices(
            @NotNull(message = "Send the whole list, even if it is empty.")
                    @Size(max = 500, message = "That is more services than a business can hold.")
                    List<UUID> serviceIds) {}

    /**
     * The whole week, replacing whatever is there.
     *
     * <p>An empty list means this Employee works no fixed days — legitimate, and not the same as
     * omitting the field, which is a malformed request. A day absent from the payload is a day off;
     * there is no flag for it, here or in storage.
     */
    public record ReplaceSchedule(
            @NotNull(message = "Send the whole week, even if it is empty.")
                    @Size(max = 70, message = "That is more intervals than a week can hold.")
                    List<@Valid ScheduleInterval> schedule) {}

    public record ScheduleInterval(
            @NotNull(message = "Choose a day.")
                    @Min(value = 1, message = "Days run from 1 (Monday) to 7 (Sunday).")
                    @Max(value = 7, message = "Days run from 1 (Monday) to 7 (Sunday).")
                    Integer dayOfWeek,
            @NotNull(message = "Enter a start time.") LocalTime startsAt,
            @NotNull(message = "Enter an end time.") LocalTime endsAt) {}

    /**
     * Dates, not instants, and they are calendar dates in the <em>business's</em> timezone. The
     * owner picks days; turning them into a span of time is the server's job, because only the
     * server knows the zone at the moment of the write (ADR-0003).
     *
     * <p>{@code endDate} is inclusive. A single day off is the same date twice.
     */
    public record CreateTimeOff(
            @NotNull(message = "Choose the first day off.") LocalDate startDate,
            @NotNull(message = "Choose the last day off.") LocalDate endDate,
            @Size(max = 200, message = "The reason can be at most 200 characters.") String reason) {}
}
