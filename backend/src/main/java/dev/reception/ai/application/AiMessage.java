package dev.reception.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reception.ai.port.ChatRole;
import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line of a transcript: what the customer said, what the model answered, or what a tool
 * returned.
 *
 * <p>Written before the loop's next iteration runs, so a crash mid-turn leaves a readable trail
 * rather than a conversation that appears to have jumped.
 *
 * <p><strong>The system prompt is never one of these</strong> (docs/05-ai-architecture.md §4). It
 * is rebuilt from configuration every turn, which means it cannot drift between turns and cannot be
 * reconstructed by anybody who gets a look at this table. A schema check enforces the three roles
 * that remain.
 *
 * <p>Immutable once written. There is no setter and no {@code updatedAt}: a transcript that could be
 * edited is not evidence of anything.
 */
@Entity
@Table(name = "ai_messages")
public class AiMessage extends BaseEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ChatRole role;

    @Column(updatable = false)
    private String content;

    @Column(name = "tool_name", length = 60, updatable = false)
    private String toolName;

    @Column(name = "tool_call_id", length = 80, updatable = false)
    private String toolCallId;

    /**
     * The arguments the model actually sent, and the result it actually got.
     *
     * <p>{@code jsonb} rather than text because a human debugging a transcript reads these, and the
     * dashboard renders them. Storing what was sent — not a re-serialised approximation of it — is
     * what makes this row usable as evidence when the Receptionist has behaved oddly.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_arguments", updatable = false)
    private JsonNode toolArguments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_result", updatable = false)
    private JsonNode toolResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiMessage() {
        // JPA.
    }

    private AiMessage(UUID id, UUID conversationId, UUID businessId, ChatRole role, Instant now) {
        super(id);
        this.conversationId = conversationId;
        this.businessId = businessId;
        this.role = role;
        this.createdAt = now;
    }

    /** What the customer typed. */
    public static AiMessage user(UUID id, UUID conversationId, UUID businessId, String content, Instant now) {
        AiMessage message = new AiMessage(id, conversationId, businessId, ChatRole.USER, now);
        message.content = content;
        return message;
    }

    /**
     * A model turn.
     *
     * <p>{@code content} is null on a turn that only asked for tools, which is the ordinary shape of
     * a booking's first iteration — the schema permits it for exactly that reason.
     */
    public static AiMessage assistant(UUID id, UUID conversationId, UUID businessId, String content, Instant now) {
        AiMessage message = new AiMessage(id, conversationId, businessId, ChatRole.ASSISTANT, now);
        message.content = content;
        return message;
    }

    /** One tool call and what it returned, kept together because neither is readable alone. */
    public static AiMessage tool(
            UUID id,
            UUID conversationId,
            UUID businessId,
            String toolName,
            String toolCallId,
            JsonNode arguments,
            JsonNode result,
            Instant now) {
        AiMessage message = new AiMessage(id, conversationId, businessId, ChatRole.TOOL, now);
        message.toolName = toolName;
        message.toolCallId = toolCallId;
        message.toolArguments = arguments;
        message.toolResult = result;
        return message;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public ChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String toolName() {
        return toolName;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public JsonNode toolArguments() {
        return toolArguments;
    }

    public JsonNode toolResult() {
        return toolResult;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
