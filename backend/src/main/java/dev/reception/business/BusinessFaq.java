package dev.reception.business;

import dev.reception.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One question and answer the Receptionist may repeat to a Customer.
 *
 * <p>Rendered verbatim into the system prompt, which is why the lengths are bounded and the count
 * is capped: this is text a business writes and a model is charged for on every conversation
 * (docs/05-ai-architecture.md).
 */
@Entity
@Table(name = "business_faqs")
public class BusinessFaq extends BaseEntity {

    /** A business may hold at most this many. Enforced in {@link FaqService}, not in the schema. */
    public static final int MAX_PER_BUSINESS = 50;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(nullable = false, length = 300)
    private String question;

    @Column(nullable = false, length = 1000)
    private String answer;

    /** Ascending. Ties broken by {@code created_at}, so the order is total rather than arbitrary. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessFaq() {
        // JPA.
    }

    public BusinessFaq(UUID id, UUID businessId, String question, String answer, int sortOrder, Instant now) {
        super(id);
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.question = Objects.requireNonNull(question, "question");
        this.answer = Objects.requireNonNull(answer, "answer");
        this.sortOrder = sortOrder;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** Absent leaves a field alone, matching the {@code PATCH} semantics used across this package. */
    void apply(String newQuestion, String newAnswer, Integer newSortOrder, Instant now) {
        if (newQuestion != null) {
            this.question = newQuestion;
        }
        if (newAnswer != null) {
            this.answer = newAnswer;
        }
        if (newSortOrder != null) {
            this.sortOrder = newSortOrder;
        }
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID businessId() {
        return businessId;
    }

    public String question() {
        return question;
    }

    public String answer() {
        return answer;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
