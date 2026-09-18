package dev.reception.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ChatRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a write landed on a time this Conversation had actually quoted.
 *
 * <p><strong>The question nothing else in the system can answer.</strong> A Customer asks for "the
 * Monday after next" and the Receptionist writes a different day about 10.6% of the time (#17).
 * Every layer below the model behaves correctly while it happens: ownership is proven, the Slot is
 * real, the engine returned it, and the exclusion constraint has nothing to object to. The day is
 * the part that is wrong, and it is the part nothing downstream can check — because until this
 * class existed nothing held the relation between the Slots that were offered and the time that was
 * finally written. The probe harnesses rebuilt that relation in SQL, after a funded fifty-
 * conversation arm had already been paid for.
 *
 * <p><strong>It never refuses anything.</strong> ADR-0012, and the reason is #17's third candidate:
 * a guard that made {@code reschedule_appointment} declare a {@code requested_date} and refused
 * writes that disagreed with it took wrong writes from 28.0% to 44.0%. Its lesson — <em>T25, a
 * guard is only as good as the intent it is given</em> — does not reach this check, because an
 * Offered Slot is authored by the availability engine rather than by the model, so the comparison
 * is between what the server offered and what it was asked to write. But a refusal is a behaviour
 * change that needs its own pre-registered arm, and shipping one behind an architecture change
 * would smuggle an unmeasured mechanism in. This observes; the counters say how often.
 *
 * <p><strong>Offered Slots are not stored.</strong> They are read back from the Transcript, where
 * every {@code find_available_slots} result already sits with its arguments and its answer — so
 * they are gone when it is, at ninety days (V10). What outlives it is the pair of counters on the
 * Conversation, which is why those are counters and not the dates themselves.
 */
@Component
public class OfferedSlots {

    /**
     * The Tool whose results are the offers.
     *
     * <p>Only this one. {@code get_service_details} names a duration and {@code resolve_date}
     * answers with a calendar date, and neither is a bookable time anybody was shown — a Slot is
     * "a candidate start time that availability calculation has proven bookable" and this is the
     * only Tool that returns one.
     */
    private static final String OFFERING_TOOL = "find_available_slots";

    /**
     * The Tools whose writes are counted: the two whose result carries a time.
     *
     * <p>{@code cancel_appointment} is absent because it has no time to compare and would only
     * dilute the denominator. {@code create_appointment} is present because a booking to a time the
     * Customer was never shown is the same defect as a move to one — {@code reasonNotBookable}
     * refuses an <em>unbookable</em> time, and a perfectly bookable one that was never quoted
     * passes every check there is. It is also why the rate this produces is not comparable to
     * #17's recorded figures, which measure reschedules alone (ADR-0012).
     */
    private static final Set<String> WRITING_TOOLS = Set.of("create_appointment", "reschedule_appointment");

    private final AiMessageRepository messages;

    public OfferedSlots(AiMessageRepository messages) {
        this.messages = messages;
    }

    /**
     * Where one Tool call put an Appointment, and whether this Conversation had offered it.
     *
     * @param offered the written time matches a Slot {@code find_available_slots} returned earlier
     *     in this Conversation
     * @param offersWereTruncated at least one of those answers was capped by {@code MAX_SLOTS} and
     *     said so. Carried rather than folded into {@code offered} because a real Slot truncated
     *     out of an answer and then written reads here as unoffered: the counters are known to run
     *     slightly high, and this is what lets the overcount be measured instead of assumed away
     */
    public record Landing(Instant writtenAt, boolean offered, boolean offersWereTruncated) {}

    /**
     * Empty unless this call wrote an Appointment.
     *
     * <p>Empty rather than a third enum value so a caller counts exactly what it is handed: a call
     * that is not a write, or one that failed, is not a denominator.
     *
     * <p><strong>The time comes from the result, not the arguments.</strong> The argument is what
     * the model asked for; the result is what the server did, and those are the same value only as
     * long as nothing between them adjusts it. Reading the result also means one key —
     * {@code starts_at} — where the arguments would have meant knowing that one Tool calls it
     * {@code starts_at} and the other {@code new_starts_at}.
     *
     * <p>Read-only and inside the turn. The Transcript rows for this turn are already committed —
     * the loop persists each Tool result before the next iteration — so a write in turn three is
     * compared against offers made in turn one, which is the whole point: the failure is cross-turn
     * by construction.
     */
    @Transactional(readOnly = true)
    public Optional<Landing> landingOf(UUID businessId, UUID conversationId, String toolName, ObjectNode result) {
        if (!WRITING_TOOLS.contains(toolName) || result == null || result.has("error")) {
            return Optional.empty();
        }
        Optional<Instant> written = instantAt(result, "starts_at");
        if (written.isEmpty()) {
            // A write whose result carries no time is a programming error in the Tool, not a
            // Customer's problem. It is left out of the counters rather than counted as a miss,
            // because inventing a denominator entry for it would understate the rate.
            return Optional.empty();
        }

        Set<Instant> offers = new HashSet<>();
        boolean truncated = false;
        for (AiMessage message : messages.findByBusinessIdAndConversationIdOrderByCreatedAt(businessId, conversationId)) {
            if (message.role() != ChatRole.TOOL || !OFFERING_TOOL.equals(message.toolName())) {
                continue;
            }
            JsonNode answer = message.toolResult();
            if (answer == null || answer.has("error")) {
                continue;
            }
            truncated |= answer.path("truncated").asBoolean(false);
            for (JsonNode slot : answer.path("slots")) {
                instantAt(slot, "starts_at").ifPresent(offers::add);
            }
        }

        return Optional.of(new Landing(written.get(), offers.contains(written.get()), truncated));
    }

    /**
     * Compared as instants, never as text.
     *
     * <p>The two sides are written by different code paths and formatted independently, so
     * {@code "2026-09-21T12:00+04:00"} and {@code "2026-09-21T12:00:00+04:00"} are the same moment
     * and different strings — and a Business that changed timezone between the offer and the write
     * would produce a third spelling of it again (ADR-0003). An unparseable value is absent rather
     * than a miss: it cannot be shown to disagree with anything.
     */
    private static Optional<Instant> instantAt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value.asText()).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
