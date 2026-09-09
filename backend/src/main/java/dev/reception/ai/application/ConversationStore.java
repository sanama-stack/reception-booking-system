package dev.reception.ai.application;

import dev.reception.ai.tools.AuthorizedAppointments;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write a turn makes, behind a real transaction boundary.
 *
 * <p><strong>A separate bean rather than transactional methods on {@link ConversationService}, and
 * for a reason that does not show up in a test.</strong> Spring's {@code @Transactional} is applied
 * by a proxy, and a proxy is only involved when the call arrives from outside the object. The
 * orchestration loop calls these from inside itself, so had they stayed private methods on that
 * class the annotations would have been decoration — the writes would still have worked, each
 * inside the repository's own transaction, and nobody would have noticed until one needed to be
 * atomic with another.
 *
 * <p>The boundaries here are deliberately small: one message, or one conversation update. A turn
 * makes network calls that take tens of seconds, and a transaction spanning the whole of it would
 * hold a connection open for the length of a conversation — and would defeat "persisted before the
 * next iteration", since nothing uncommitted is readable by anyone debugging a stuck turn.
 */
@Component
public class ConversationStore {

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final CostTracker costs;
    private final Clock clock;

    public ConversationStore(
            AiConversationRepository conversations,
            AiMessageRepository messages,
            CostTracker costs,
            Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.costs = costs;
        this.clock = clock;
    }

    @Transactional
    public AiConversation create(AiConversation conversation) {
        return conversations.save(conversation);
    }

    /** One transcript line, committed before the loop goes round again. */
    @Transactional
    public void append(AiMessage message) {
        messages.save(message);
    }

    /**
     * What the turn consumed and what it proved, applied to the row rather than to the copy the
     * loop has been holding.
     *
     * <p>Re-read inside the transaction on purpose. The loop's copy was loaded before a sequence of
     * network calls, and a second turn on the same conversation — a customer with two tabs, a
     * client that retried — may have moved the counters since. Applying deltas to a freshly-read row
     * means the two turns add up instead of one overwriting the other.
     */
    @Transactional
    public AiConversation recordTurn(
            UUID businessId,
            UUID conversationId,
            int messagesAdded,
            int promptTokens,
            int completionTokens,
            AuthorizedAppointments authority) {
        AiConversation current = conversations
                .findByBusinessIdAndId(businessId, conversationId)
                .orElseThrow(() -> new IllegalStateException("Conversation vanished mid-turn: " + conversationId));

        current.recordTurn(
                messagesAdded,
                promptTokens,
                completionTokens,
                costs.costCentsFor(promptTokens, completionTokens),
                authority.snapshot(),
                clock.instant());
        return conversations.save(current);
    }

    @Transactional
    public void close(UUID businessId, UUID conversationId, ConversationStatus terminal) {
        conversations.findByBusinessIdAndId(businessId, conversationId).ifPresent(current -> {
            current.close(terminal);
            conversations.save(current);
        });
    }

    @Transactional
    public void identify(UUID businessId, UUID conversationId, UUID customerId) {
        conversations.findByBusinessIdAndId(businessId, conversationId).ifPresent(current -> {
            current.identify(customerId);
            conversations.save(current);
        });
    }
}
