package dev.reception.publicapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What the chat panel sends. Hand-written, like every other request body on this surface. */
public final class PublicChatRequests {

    private PublicChatRequests() {}

    /**
     * Opening a conversation.
     *
     * @param manageToken a Manage Link the customer arrived on, or null. Present, it seeds the
     *     conversation's authority with the one Appointment that token already authorises — which is
     *     what lets someone who followed a link from an email say "move this" without reciting a
     *     Confirmation Code they would have to go and find. An invalid token is ignored rather than
     *     refused: the customer can still have an ordinary conversation, and a chat panel that
     *     refused to open because of a stale link would be worse than one that opens with no
     *     authority
     */
    public record StartSession(@Size(max = 500) String manageToken) {}

    /**
     * One turn.
     *
     * <p>The session token travels in the body rather than the query string, for the reason a phone
     * number does: it is a capability, and a query string reaches logs, referrer headers and
     * browser history (docs/04-api-overview.md §6).
     *
     * <p><strong>No {@code conversationId}, which is a deliberate departure from that document's
     * example body.</strong> The token already names exactly one conversation — it is indexed
     * uniquely — so a second identifier beside it could only ever agree redundantly or disagree, and
     * there is no useful behaviour for the disagreeing case. It is the same argument
     * {@code PublicAppointmentController} makes about the id in its path: where a request carries a
     * proof and a claim about what the proof names, the proof decides and the claim is at best
     * noise. There the claim is checked because a URL cannot avoid carrying one; here the body can
     * simply not carry it.
     *
     * @param message capped at 2,000 characters. Longer than anything a person types at a
     *     receptionist and short enough that no single turn can dominate the prompt budget
     */
    public record SendMessage(
            @NotBlank @Size(max = 500) String sessionToken, @NotBlank @Size(max = 2000) String message) {}
}
