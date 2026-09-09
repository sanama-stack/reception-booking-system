package dev.reception.ai.tools;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything a tool is allowed to know that did not come from the model.
 *
 * <p>Read the field list as the isolation property: the tenant and the authority are both here, and
 * neither is a tool parameter. A model cannot name a business because no schema has a place to put
 * one, and it cannot act on an appointment because saying an id is not the same as being in
 * {@link #authorized()} (docs/05-ai-architecture.md §3).
 *
 * @param businessId from the conversation row, never from the model. Tools do not read it directly
 *     — the tenant is already resolved for the request — but it is carried so a tool that needs to
 *     assert which tenant it is in can, and so that this record reads as the whole of the
 *     server-held context
 * @param clock the injected one. {@code Instant.now()} is banned outside the {@code Clock} bean
 *     (docs/09-phase-plan.md §5)
 */
public record ToolContext(UUID businessId, UUID conversationId, AuthorizedAppointments authorized, Clock clock) {

    public ToolContext {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(authorized, "authorized");
        Objects.requireNonNull(clock, "clock");
    }
}
