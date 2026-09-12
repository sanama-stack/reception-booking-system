package dev.reception.ai.application.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reception.ai.application.AiConversation;
import dev.reception.ai.application.AiMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * What the owner's transcript screens render.
 *
 * <p>Hand-written, and one field is conspicuously absent: {@code sessionTokenHash} is never
 * projected. It is not a secret in the way the token is, but it is the index a conversation is
 * resumed by, and nothing on a dashboard needs it.
 */
public final class ConversationResponses {

    private ConversationResponses() {}

    /**
     * @param messagesPurgedAt when the retention purge took this conversation's transcript, or null
     *     if it still has one. Projected because a screen cannot tell the truth about a state the
     *     API cannot name: {@code messageCount} is not decremented by the purge, so without this
     *     field an empty transcript beneath a non-zero count is indistinguishable from a
     *     conversation that was opened and never spoken in — and the transcript screen says exactly
     *     that about it
     */
    public record ConversationSummary(
            UUID id,
            String status,
            int messageCount,
            int promptTokens,
            int completionTokens,
            int estimatedCostCents,
            UUID customerId,
            Instant startedAt,
            Instant lastMessageAt,
            Instant messagesPurgedAt) {

        public static ConversationSummary of(AiConversation conversation) {
            return new ConversationSummary(
                    conversation.getId(),
                    conversation.status().name(),
                    conversation.messageCount(),
                    conversation.promptTokens(),
                    conversation.completionTokens(),
                    conversation.estimatedCostCents(),
                    conversation.customerId(),
                    conversation.startedAt(),
                    conversation.lastMessageAt(),
                    conversation.messagesPurgedAt());
        }
    }

    public record ConversationPage(List<ConversationSummary> items, int page, int size, long total) {

        public static ConversationPage of(Page<AiConversation> page) {
            return new ConversationPage(
                    page.getContent().stream().map(ConversationSummary::of).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements());
        }
    }

    /**
     * One transcript line.
     *
     * <p>Tool arguments and results are passed through as the JSON they were stored as, because the
     * point of the screen is to show what actually crossed the boundary. A summarised tool call is
     * exactly as useful as no tool call when the Receptionist has done something surprising.
     */
    public record Message(
            UUID id,
            String role,
            String content,
            String toolName,
            JsonNode toolArguments,
            JsonNode toolResult,
            Instant createdAt) {

        public static Message of(AiMessage message) {
            return new Message(
                    message.getId(),
                    message.role().name(),
                    message.content(),
                    message.toolName(),
                    message.toolArguments(),
                    message.toolResult(),
                    message.createdAt());
        }
    }

    public record ConversationDetail(ConversationSummary conversation, List<Message> messages) {

        public static ConversationDetail of(AiConversation conversation, List<AiMessage> messages) {
            return new ConversationDetail(
                    ConversationSummary.of(conversation), messages.stream().map(Message::of).toList());
        }
    }
}
