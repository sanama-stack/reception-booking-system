package dev.reception.ai.application;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One conversation with the Receptionist: its budget, its ceiling, and what it is allowed to touch.
 *
 * <p>The row exists before the first model call and is updated after every turn, so a conversation
 * that crashes mid-turn is still countable and still readable. Everything the orchestration loop
 * has to check before spending money is on this one row, which is why the loop needs no second read
 * to decide whether it may proceed.
 *
 * <p><strong>{@code sessionTokenHash} is a hash and the token is never stored.</strong> The token is
 * a capability — whoever holds it can continue this conversation — and a database read must not
 * hand one out. The same reasoning as a password hash, for the same threat.
 */
@Entity
@Table(name = "ai_conversations")
public class AiConversation extends BaseEntity {

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "session_token_hash", nullable = false, length = 64, updatable = false)
    private String sessionTokenHash;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "estimated_cost_cents", nullable = false)
    private int estimatedCostCents;

    /**
     * The authority set, as the database holds it.
     *
     * <p>A Postgres {@code uuid[]}, mapped with {@code SqlTypes.ARRAY}. An array rather than a child
     * table because it is read whole on every write-tool call and never queried across
     * conversations — and because a child table would be a second place an id could be inserted.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "authorized_appointment_ids", nullable = false)
    private UUID[] authorizedAppointmentIds;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    /**
     * When the retention purge deleted this conversation's messages, or null if it has not.
     *
     * <p>A fact rather than an inference. {@code messageCount} is not decremented by the purge, so
     * "count above zero, transcript empty" does identify a purged conversation — and reads as a
     * broken foreign key to anyone who meets it without knowing the purge exists. The screen that
     * renders a transcript has to tell those two apart to say anything true, and it can only do
     * that if the API can name the state.
     */
    @Column(name = "messages_purged_at")
    private Instant messagesPurgedAt;

    protected AiConversation() {
        // JPA.
    }

    public AiConversation(UUID id, UUID businessId, String sessionTokenHash, List<UUID> seedAuthority, Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.sessionTokenHash = Objects.requireNonNull(sessionTokenHash, "sessionTokenHash");
        this.status = ConversationStatus.ACTIVE;
        this.authorizedAppointmentIds = seedAuthority.toArray(UUID[]::new);
        this.startedAt = Objects.requireNonNull(now, "now");
        this.lastMessageAt = now;
    }

    /**
     * Records what a turn consumed and what it proved.
     *
     * <p>One method rather than four setters, because these five values only ever change together
     * and a caller that updated the token counts without the message count would silently defeat
     * the ceiling.
     */
    public void recordTurn(int messagesAdded, int promptTokens, int completionTokens, int costCents,
            Set<UUID> authority, Instant now) {
        this.messageCount += messagesAdded;
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.estimatedCostCents += costCents;
        this.authorizedAppointmentIds = authority.toArray(UUID[]::new);
        this.lastMessageAt = now;
    }

    public void close(ConversationStatus terminal) {
        if (terminal == ConversationStatus.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE is not a terminal status");
        }
        this.status = terminal;
    }

    /** Set once identity is known, which for most conversations is the moment a booking succeeds. */
    public void identify(UUID customerId) {
        if (this.customerId == null) {
            this.customerId = customerId;
        }
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID customerId() {
        return customerId;
    }

    public ConversationStatus status() {
        return status;
    }

    public int messageCount() {
        return messageCount;
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public int estimatedCostCents() {
        return estimatedCostCents;
    }

    /** A copy, and an ordered one, so a caller cannot append to the authority set by accident. */
    public Set<UUID> authorizedAppointmentIds() {
        return authorizedAppointmentIds == null
                ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(authorizedAppointmentIds));
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public Instant messagesPurgedAt() {
        return messagesPurgedAt;
    }

    /** True once the retention purge has taken this conversation's transcript. */
    public boolean messagesPurged() {
        return messagesPurgedAt != null;
    }
}
