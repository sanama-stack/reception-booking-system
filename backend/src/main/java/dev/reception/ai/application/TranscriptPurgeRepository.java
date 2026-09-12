package dev.reception.ai.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The retention purge's half of the AI tables, and the second query surface in this application
 * that is deliberately not tenant-scoped.
 *
 * <p>Separate from {@link AiConversationRepository} and {@link AiMessageRepository} for the reason
 * {@code NotificationClaimRepository} is separate from {@code NotificationRepository}, and it is
 * worth the file: a {@code @TenantScoped} repository carrying one cross-tenant method teaches every
 * future reader that the rule has exceptions, and the next exception will be an accident. Here the
 * exception is the entire purpose of the type, stated in its name, and {@code
 * TenantRepositoryShapeTest} has nothing to say about it.
 *
 * <p><strong>The system is the actor.</strong> Nobody's session is behind a purge. It is the
 * application keeping one promise on behalf of every Business at once, which is exactly the actor
 * the notification poller has.
 */
public interface TranscriptPurgeRepository extends JpaRepository<AiConversation, UUID> {

    /**
     * Takes a batch of conversations that last spoke before {@code cutoff} and still hold messages.
     *
     * <p><strong>{@code messages_purged_at IS NULL} is not an optimisation.</strong> Without it the
     * job would re-select every conversation it has ever purged, on every run, for the rest of the
     * database's life — and would find nothing to delete each time, so the wasted work would be
     * invisible in the row counts it logs. It is also what makes
     * {@code ai_conversations_purge_idx} a partial index that shrinks as the purge works.
     *
     * <p><strong>{@code FOR UPDATE SKIP LOCKED}, for the poller's reason.</strong> Two instances
     * purging in the same minute take disjoint batches instead of both deleting the same
     * transcripts and both counting them. The lock lives until the caller's transaction ends, so
     * this must be called inside one — which {@link TranscriptPurge} is.
     *
     * <p>Native SQL because JPQL cannot express the locking clause, and ids rather than entities
     * because nothing here needs a hydrated conversation: the two statements that follow are both
     * bulk.
     */
    @Query(
            value =
                    """
                    select id from ai_conversations
                     where last_message_at < :cutoff
                       and messages_purged_at is null
                     order by last_message_at
                     limit :batchSize
                     for update skip locked
                    """,
            nativeQuery = true)
    List<UUID> claimPurgeable(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Deletes every message of the claimed conversations.
     *
     * <p>A bulk delete rather than loading and removing entities. A conversation is capped at forty
     * messages so a batch is bounded either way, but hydrating rows whose only destiny is deletion
     * would spend the memory to build objects that are then thrown away — and would put the
     * Customer's name and phone number into this JVM's heap, on the one code path whose entire
     * purpose is to stop holding them.
     *
     * @return how many transcript lines were deleted, which is the number the job logs
     */
    @Modifying
    @Query("delete from AiMessage m where m.conversationId in :conversationIds")
    int deleteMessagesOf(@Param("conversationIds") Collection<UUID> conversationIds);

    /**
     * Marks the claimed conversations as purged.
     *
     * <p>The only write to {@code messages_purged_at} anywhere in the application. It is not a
     * setter on {@link AiConversation} on purpose: no request path has any business setting it, and
     * a field with no mutator cannot be set by one.
     *
     * <p>Must run in the same transaction as the delete above. Split across two, a crash between
     * them would leave conversations whose messages are gone and whose column still says they are
     * not — which the transcript screen would render as the lie this column exists to prevent.
     */
    @Modifying
    @Query("update AiConversation c set c.messagesPurgedAt = :now where c.id in :conversationIds")
    int markPurged(@Param("conversationIds") Collection<UUID> conversationIds, @Param("now") Instant now);
}
