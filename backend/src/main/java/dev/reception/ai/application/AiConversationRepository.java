package dev.reception.ai.application;

import dev.reception.tenancy.TenantScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Conversations, always within one Business. */
@TenantScoped
public interface AiConversationRepository extends JpaRepository<AiConversation, UUID> {

    Optional<AiConversation> findByBusinessIdAndId(UUID businessId, UUID id);

    /**
     * Resuming from the token the browser holds.
     *
     * <p>Named by tenant first like every other method here, and the hash is unique across the whole
     * table anyway — so the tenant is a guard rather than a selector. A token issued for one
     * business cannot resume a conversation in another even if the hash somehow collided.
     */
    Optional<AiConversation> findByBusinessIdAndSessionTokenHash(UUID businessId, String sessionTokenHash);

    /** The owner's transcript list. */
    Page<AiConversation> findByBusinessIdOrderByStartedAtDesc(UUID businessId, Pageable pageable);

    /**
     * What this Business has spent since {@code since} — the daily cap's input.
     *
     * <p>{@code coalesce} because a business that has had no conversations today sums to null, and a
     * null here would be read as "no cap data" by a caller that has to answer a yes-or-no question
     * before spending money. Zero is the truth.
     */
    @Query("""
            select coalesce(sum(c.estimatedCostCents), 0)
              from AiConversation c
             where c.businessId = :businessId
               and c.startedAt >= :since
            """)
    long sumByBusinessIdSince(@Param("businessId") UUID businessId, @Param("since") Instant since);

    List<AiConversation> findByBusinessIdAndStatus(UUID businessId, ConversationStatus status);
}
