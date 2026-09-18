package dev.reception.ai.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/**
 * What one turn produced: something to say, and — if a booking happened — the booking itself.
 *
 * <p><strong>{@code appointmentCreated} is the single most effective hallucination control in the
 * system</strong>, and it is a UI decision as much as a backend one. It is populated from the tool
 * result, never from the model's prose, and the chat panel renders its confirmation card from this
 * object alone. A model that announces a booking that did not happen therefore produces a paragraph
 * with no card under it — a visible absence rather than a convincing lie
 * (docs/05-ai-architecture.md §6).
 *
 * <p>The same argument covers a move, and for a while it did not. {@code reschedule_appointment}
 * has always returned the {@code starts_at} the server landed on, and this loop dropped it — so
 * after a reschedule the only account of the new date a Customer could read was the model's prose,
 * which is exactly the surface issue #17 measures. A move now produces a card on the same terms a
 * booking does.
 *
 * @param appointmentCreated the {@code create_appointment} result verbatim, or null. Null is the
 *     ordinary case: most turns book nothing
 * @param appointmentUpdated the {@code reschedule_appointment} result verbatim, or null. Separate
 *     from {@code appointmentCreated} rather than sharing it: one turn may do both, and a single
 *     field would let whichever wrote last erase the other
 * @param status where the conversation now is. {@code ACTIVE} accepts another turn; the other two do
 *     not, and the client shows the Classic Flow
 * @param messagesRemaining how many more messages this conversation may hold before the ceiling
 *     closes it. Returned rather than left to the client to count, because the count includes tool
 *     rows — a client counting what it can see would be wrong by however many tools ran, and would
 *     be wrong in the optimistic direction
 */
public record ConversationTurn(
        UUID conversationId,
        String reply,
        ObjectNode appointmentCreated,
        ObjectNode appointmentUpdated,
        ConversationStatus status,
        int messagesRemaining) {

    /** Whether the composer should stay open. */
    public boolean isClosed() {
        return status != ConversationStatus.ACTIVE;
    }
}
