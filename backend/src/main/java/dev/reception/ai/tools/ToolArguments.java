package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Reads the model's arguments, and says something useful when they cannot be read.
 *
 * <p>Strict schemas make almost everything here unreachable: the provider validates types and
 * required keys before the call is dispatched, so a missing property or a number where a string
 * belongs does not arrive. What strict mode <em>cannot</em> check is the inside of a string — a
 * uuid that is not a uuid, a date of "next Tuesday", a time of "half five" — and those are exactly
 * the mistakes a language model makes.
 *
 * <p>Every failure is an {@link ApiException} carrying {@code VALIDATION_FAILED}, which
 * {@link ToolRegistry} turns into a result the model reads and can retry from. That is the whole
 * design: a malformed argument is a conversation the model can repair, not a request that failed.
 */
final class ToolArguments {

    private ToolArguments() {}

    static UUID uuid(JsonNode arguments, String field) {
        String raw = requireText(arguments, field);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "must be an id you were given by a previous tool call, not one you composed");
        }
    }

    /** Null-tolerant, because every optional argument arrives as an explicit null under strict mode. */
    static UUID optionalUuid(JsonNode arguments, String field) {
        return isAbsent(arguments, field) ? null : uuid(arguments, field);
    }

    static String text(JsonNode arguments, String field) {
        return requireText(arguments, field);
    }

    static String optionalText(JsonNode arguments, String field) {
        if (isAbsent(arguments, field)) {
            return null;
        }
        String value = arguments.get(field).asText();
        return value.isBlank() ? null : value;
    }

    static LocalDate date(JsonNode arguments, String field) {
        String raw = requireText(arguments, field);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // The model has today's date from the system prompt and from get_business_info. This
            // message points at the resolution step rather than at the format, because the format
            // is rarely what it got wrong.
            throw invalid(field, "must be a calendar date as YYYY-MM-DD — resolve 'tomorrow' or 'next "
                    + "Tuesday' against today's date before calling");
        }
    }

    static LocalDate optionalDate(JsonNode arguments, String field) {
        return isAbsent(arguments, field) ? null : date(arguments, field);
    }

    static LocalTime optionalTime(JsonNode arguments, String field) {
        if (isAbsent(arguments, field)) {
            return null;
        }
        String raw = arguments.get(field).asText();
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalid(field, "must be a 24-hour time as HH:mm — 'after five' is '17:00'");
        }
    }

    /**
     * An instant, which must have arrived with an offset.
     *
     * <p>Deliberately refuses a local date-time. A start time without an offset is ambiguous twice a
     * year and wrong by hours the rest of it, and the only correct source for this argument is a
     * {@code starts_at} that {@code find_available_slots} already returned — which always carries
     * one (ADR-0003).
     */
    static OffsetDateTime instant(JsonNode arguments, String field) {
        String raw = requireText(arguments, field);
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalid(field, "must be a start time exactly as find_available_slots returned it, "
                    + "including its timezone offset");
        }
    }

    private static String requireText(JsonNode arguments, String field) {
        if (isAbsent(arguments, field)) {
            throw invalid(field, "is required");
        }
        String value = arguments.get(field).asText();
        if (value.isBlank()) {
            throw invalid(field, "is required");
        }
        return value;
    }

    private static boolean isAbsent(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return node == null || node.isNull();
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, field + " " + problem + ".");
    }
}
