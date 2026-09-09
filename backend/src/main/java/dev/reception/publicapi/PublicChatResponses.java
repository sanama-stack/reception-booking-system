package dev.reception.publicapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.application.ConversationTurn;
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
            BookedAppointment appointmentCreated) {

        public static Reply of(ConversationTurn turn) {
            return new Reply(
                    turn.reply(),
                    turn.status().name(),
                    turn.messagesRemaining(),
                    BookedAppointment.from(turn.appointmentCreated()));
        }
    }

    /**
     * The confirmation card, in this package's vocabulary rather than the tool surface's.
     *
     * <p><strong>Projected, not passed through</strong>, and the distinction is narrower than it
     * looks. What makes this field a hallucination control is its <em>provenance</em> — it exists
     * only because {@code create_appointment} returned a success — and that is untouched by
     * renaming the keys on the way out. What passing the tool result through verbatim would have
     * cost is the rule this whole package is built on: one hand-written vocabulary, in
     * {@code camelCase}, governed by {@code PublicFieldAllowListTest}. The tools speak
     * {@code snake_case} because that is what reads well in a JSON Schema a model consumes; a
     * booking page should not have to know that.
     *
     * <p>The fields are deliberately the same ones {@code PublicResponses.BookedAppointment} carries,
     * including {@code confirmationSent} — a Receptionist booking and a Classic Flow booking are the
     * same event, and a client should not need two shapes to render one card.
     */
    public record BookedAppointment(
            UUID id,
            String confirmationCode,
            String startsAt,
            String endsAt,
            String service,
            String employee,
            String price,
            String currency,
            boolean confirmationSent) {

        /** Null in, null out — most turns book nothing, and that is the ordinary case. */
        static BookedAppointment from(ObjectNode toolResult) {
            if (toolResult == null) {
                return null;
            }
            return new BookedAppointment(
                    UUID.fromString(toolResult.path("appointment_id").asText()),
                    toolResult.path("confirmation_code").asText(),
                    toolResult.path("starts_at").asText(),
                    toolResult.path("ends_at").asText(),
                    toolResult.path("service_name").asText(),
                    toolResult.path("employee_name").asText(),
                    toolResult.path("price").asText(),
                    toolResult.path("currency").asText(),
                    toolResult.path("confirmation_email_sent").asBoolean());
        }
    }
}
