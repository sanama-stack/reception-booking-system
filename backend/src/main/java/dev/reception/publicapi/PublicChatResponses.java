package dev.reception.publicapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.application.ConversationTurn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What the chat panel receives. Hand-written like everything else in this package — no entity is
 * ever serialised, and {@code PublicFieldAllowListTest} fails the build for a field nobody typed in
 * here deliberately.
 */
public final class PublicChatResponses {

    private PublicChatResponses() {}

    /**
     * @param sessionToken returned exactly once. The server keeps only a SHA-256 of it, so this
     *     response is the only place it will ever exist outside the customer's browser
     */
    public record StartedSession(UUID conversationId, String sessionToken) {}

    /**
     * One reply.
     *
     * @param appointmentCreated what {@code create_appointment} returned, or null. The confirmation
     *     card is rendered from <strong>this</strong> and never from {@code reply} — the single most
     *     effective hallucination control in the system, and it works only because the field is
     *     populated from a tool result rather than parsed out of prose. A model that claims a
     *     booking that did not happen produces a paragraph with no card beneath it
     *     (docs/05-ai-architecture.md §6)
     * @param conversationStatus {@code ACTIVE}, {@code CLOSED} or {@code LIMIT_REACHED}. The client
     *     hides the composer and shows the Classic Flow for the latter two
     * @param messagesRemaining how many more messages fit before the ceiling closes this
     *     conversation, so the panel can warn rather than stop dead. Counted by the server because
     *     the total includes tool rows the client never sees
     */
    public record Reply(
            String reply,
            String conversationStatus,
            int messagesRemaining,
            PublicResponses.BookedAppointment appointmentCreated) {

        public static Reply of(ConversationTurn turn) {
            return new Reply(
                    turn.reply(),
                    turn.status().name(),
                    turn.messagesRemaining(),
                    bookedAppointment(turn.appointmentCreated()));
        }
    }

    /**
     * The confirmation card, projected onto the Classic Flow's own record.
     *
     * <p><strong>The same shape, not merely the same field names.</strong> A Receptionist booking
     * and a Classic Flow booking are the same event, and this returns
     * {@link PublicResponses.BookedAppointment} itself so that one component renders either. An
     * earlier version declared a second record here whose keys matched and whose <em>types</em> did
     * not — {@code service} a string beside a {@code BookedService}, {@code price} and
     * {@code currency} flat beside a {@code Money}, and no {@code timezone} at all — under a javadoc
     * claiming a client would not need two shapes. It did. Same-named fields of different types are
     * worse than differently-named ones, because a reader assumes they agree; {@code ALLOWED} is a
     * flat set of key names and could not see it either (issue #10).
     *
     * <p><strong>Projected, not passed through.</strong> What makes this field a hallucination
     * control is its <em>provenance</em> — it exists only because {@code create_appointment}
     * returned a success — and that is untouched by re-shaping it on the way out. The tools speak
     * {@code snake_case} because that is what reads well in a JSON Schema a model consumes; a
     * booking page should not have to know that.
     *
     * <p>Null in, null out — most turns book nothing, and that is the ordinary case.
     */
    static PublicResponses.BookedAppointment bookedAppointment(ObjectNode toolResult) {
        if (toolResult == null) {
            return null;
        }
        return new PublicResponses.BookedAppointment(
                UUID.fromString(toolResult.path("appointment_id").asText()),
                toolResult.path("confirmation_code").asText(),
                OffsetDateTime.parse(toolResult.path("starts_at").asText()),
                OffsetDateTime.parse(toolResult.path("ends_at").asText()),
                toolResult.path("timezone").asText(),
                new PublicResponses.BookedService(
                        toolResult.path("service_name").asText(),
                        toolResult.path("service_duration_minutes").asInt()),
                new PublicResponses.BookedEmployee(toolResult.path("employee_name").asText()),
                new PublicResponses.Money(
                        new BigDecimal(toolResult.path("price").asText()), toolResult.path("currency").asText()),
                toolResult.path("confirmation_email_sent").asBoolean());
    }
}
