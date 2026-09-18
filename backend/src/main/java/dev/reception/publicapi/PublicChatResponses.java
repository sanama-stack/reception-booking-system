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
     * @param appointmentUpdated what {@code reschedule_appointment} returned, or null, on the same
     *     terms and for the same reason. A move used to arrive as prose alone — the server landed
     *     on a date, said so in the tool result, and nothing carried it to the panel — which left
     *     the model's sentence as the only account of the new time a Customer could read. Two
     *     fields rather than one because a turn may create and move, and a shared field would keep
     *     only the later
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
            PublicResponses.BookedAppointment appointmentCreated,
            PublicResponses.BookedAppointment appointmentUpdated) {

        public static Reply of(ConversationTurn turn) {
            return new Reply(
                    turn.reply(),
                    turn.status().name(),
                    turn.messagesRemaining(),
                    bookedAppointment(turn.appointmentCreated(), "confirmation_email_sent"),
                    bookedAppointment(turn.appointmentUpdated(), "reschedule_email_sent"));
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
     * <p><strong>One projection, both fields.</strong> {@code reschedule_appointment} was widened to
     * return the same keys {@code create_appointment} does precisely so this method serves both: a
     * moved Appointment is a confirmed Appointment fully described, and the alternative — a second
     * record carrying a subset — is the mistake this javadoc already records once (issue #10).
     *
     * <p><strong>{@code emailSentKey} is a parameter and not a constant</strong>, because the two
     * tools name that fact differently on purpose — {@code confirmation_email_sent} against
     * {@code reschedule_email_sent}, the same way {@code cancel_appointment} says
     * {@code cancellation_email_sent} — and those names are read by a model, which is better served
     * by the specific one. Passing it in is the part that matters: {@code path()} on an absent key
     * returns a missing node and {@code asBoolean()} on that is {@code false}, so a single hard-coded
     * key would have made every move report that no message was coming. That is the precise defect
     * ADR-0007 and ADR-0008 exist to prevent, arriving through the back door of a shared projection.
     *
     * <p>Null in, null out — most turns book nothing, and that is the ordinary case.
     *
     * @param emailSentKey the key in {@code toolResult} carrying whether a message was enqueued
     */
    static PublicResponses.BookedAppointment bookedAppointment(ObjectNode toolResult, String emailSentKey) {
        if (toolResult == null) {
            return null;
        }
        if (!toolResult.hasNonNull(emailSentKey)) {
            // Loud rather than false. The tool that fed this is in the same repository as this
            // method, so a missing key is a programming error, and the honest failure is a 500 the
            // suite catches — not a card quietly telling a Customer no email is on its way.
            throw new IllegalStateException(
                    "Tool result carries no " + emailSentKey + "; it cannot render a confirmation card.");
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
                toolResult.path(emailSentKey).asBoolean());
    }
}
