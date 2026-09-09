package dev.reception.publicapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.application.ConversationService;
import dev.reception.ai.application.ConversationTurn;
import dev.reception.appointments.AppointmentLookup;
import dev.reception.common.error.ApiException;
import dev.reception.notifications.ManageTokenService;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Receptionist, as a stranger reaches it.
 *
 * <p><strong>Under {@code /public/businesses/{slug}} deliberately.</strong> The slug puts
 * {@code SlugTenantContextFilter} in charge of resolving the tenant, exactly as it does for the
 * Classic Flow's availability and booking endpoints — so the conversation's Business is settled
 * before any code here runs, and there is nothing in a body that could name a different one. This
 * is the shape of ADR-0004's "no tool takes a tenant" at the HTTP layer: the model cannot name a
 * business because the request already did, once, in a place the model never sees.
 *
 * <p>Two endpoints and no more. A conversation is opened, and then it is spoken to; there is no way
 * to list conversations, look one up by id, or read anybody's transcript from here. The owner's
 * read-only view is {@code ConversationQueryController}, behind authentication.
 */
@RestController
@RequestMapping("/public/businesses/{slug}/chat")
public class PublicChatController {

    private final ConversationService conversations;
    private final ManageTokenService manageTokens;
    private final AppointmentLookup appointments;

    public PublicChatController(
            ConversationService conversations, ManageTokenService manageTokens, AppointmentLookup appointments) {
        this.conversations = conversations;
        this.manageTokens = manageTokens;
        this.appointments = appointments;
    }

    /**
     * Opens a conversation and issues the token that continues it.
     *
     * <p>The token comes back once and is never recoverable — the row stores only its hash. The
     * client keeps it in {@code sessionStorage}, which is why a reload resumes and a new tab does
     * not.
     */
    @PostMapping("/session")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicChatResponses.StartedSession start(@Valid @RequestBody(required = false) PublicChatRequests.StartSession request) {
        ConversationService.StartedConversation started =
                conversations.start(seedAuthority(request == null ? null : request.manageToken()));
        return new PublicChatResponses.StartedSession(started.conversationId(), started.sessionToken());
    }

    /** One turn: what the customer said in, what the Receptionist says back out. */
    @PostMapping
    public PublicChatResponses.Reply send(@Valid @RequestBody PublicChatRequests.SendMessage request) {
        ConversationTurn turn = conversations.respond(request.sessionToken(), request.message());
        return PublicChatResponses.Reply.of(turn);
    }

    /**
     * The Appointment a Manage Link proves, if one was presented and is still valid.
     *
     * <p><strong>Verified twice, and the second time is the one that matters.</strong>
     * {@code ManageTokenService.verify} says the token is authentic and unexpired; it says nothing
     * about which Business the appointment is in. {@code appointments.require} is tenant-scoped, so
     * a token for another business's appointment presented on this slug resolves to nothing — the
     * conversation opens with no authority rather than with authority over a row in the wrong
     * tenant.
     *
     * <p>Failures are swallowed on purpose. A stale link should open a chat panel with no
     * authority, not refuse to open one: the customer can still say what they want, and
     * {@code lookup_appointment} is right there.
     */
    private Optional<UUID> seedAuthority(String manageToken) {
        if (manageToken == null || manageToken.isBlank()) {
            return Optional.empty();
        }
        return manageTokens.verify(manageToken).flatMap(appointmentId -> {
            try {
                return Optional.of(appointments.require(appointmentId).getId());
            } catch (ApiException e) {
                return Optional.empty();
            }
        });
    }
}
