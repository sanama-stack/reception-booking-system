package dev.reception.ai.application.web;

import dev.reception.ai.application.AiConversation;
import dev.reception.ai.application.AiConversationRepository;
import dev.reception.ai.application.AiMessage;
import dev.reception.ai.application.AiMessageRepository;
import dev.reception.common.error.ApiException;
import dev.reception.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading transcripts, for the owner.
 *
 * <p>Read-only in the strongest sense available: there is no write method here at all, and none of
 * the three states a conversation can be in is reachable from this class. An owner can see what the
 * Receptionist did and cannot change it — which is what makes a transcript worth having when a
 * customer says the booking is wrong.
 *
 * <p>Both queries are tenant-scoped by the repository's own shape, so "another business's
 * conversation" and "no such conversation" are the same {@code 404} here as everywhere else.
 */
@Service
public class ConversationQueryService {

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final TenantContext tenant;

    public ConversationQueryService(
            AiConversationRepository conversations, AiMessageRepository messages, TenantContext tenant) {
        this.conversations = conversations;
        this.messages = messages;
        this.tenant = tenant;
    }

    @Transactional(readOnly = true)
    public Page<AiConversation> list(int page, int size) {
        return conversations.findByBusinessIdOrderByStartedAtDesc(tenant.businessId(), PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public AiConversation read(UUID id) {
        return conversations
                .findByBusinessIdAndId(tenant.businessId(), id)
                .orElseThrow(() -> ApiException.notFound("No such conversation."));
    }

    /** The whole transcript in order — every turn and every tool call, not a window of them. */
    @Transactional(readOnly = true)
    public List<AiMessage> transcript(UUID conversationId) {
        // Through read() so a conversation in another tenant 404s here rather than returning an
        // empty transcript, which would read as "this conversation had no messages".
        read(conversationId);
        return messages.findByBusinessIdAndConversationIdOrderByCreatedAt(tenant.businessId(), conversationId);
    }
}
