package dev.reception.ai.application;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Transcript lines, always within one Business. */
@TenantScoped
public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {

    /** The whole transcript, in order — what the owner's detail screen renders. */
    List<AiMessage> findByBusinessIdAndConversationIdOrderByCreatedAt(UUID businessId, UUID conversationId);

    /**
     * The sliding window, newest first so the limit takes the most recent.
     *
     * <p>Reversed by the caller before it reaches a model. Ordering ascending and limiting would
     * take the oldest twenty, which is the opposite of a window.
     */
    List<AiMessage> findByBusinessIdAndConversationIdOrderByCreatedAtDesc(
            UUID businessId, UUID conversationId, Pageable pageable);
}
