package dev.reception.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The poller's half of the outbox, and the one query in this application that is deliberately not
 * tenant-scoped.
 *
 * <p>It is separate from {@link NotificationRepository} rather than a method on it because the
 * tenant rule is worth more than the file it costs: a {@code @TenantScoped} repository carrying one
 * cross-tenant method teaches every future reader that the rule has exceptions, and the next
 * exception will be an accident. Here the exception is the entire purpose of the type, stated in its
 * name, and {@code TenantRepositoryShapeTest} has nothing to say about it.
 *
 * <p><strong>The system is the actor.</strong> Nobody's session is behind a poll; the sweep is the
 * application delivering mail on behalf of every Business at once, which is exactly what
 * {@code Actor.system()} describes.
 */
public interface NotificationClaimRepository extends JpaRepository<Notification, UUID> {

    /**
     * Takes a batch of due rows and locks them for the caller's transaction.
     *
     * <p><strong>{@code FOR UPDATE SKIP LOCKED} is what makes the poller multi-instance-safe, and it
     * is free.</strong> Two instances polling the same second take disjoint batches: the second one
     * walks past the rows the first has locked instead of blocking behind them. Without
     * {@code SKIP LOCKED} the second instance would wait for the first to finish sending — turning
     * two pollers into one slow one — and without {@code FOR UPDATE} they would both claim the same
     * rows and the customer would get the message twice.
     *
     * <p>Native SQL because JPQL cannot express the locking clause: {@code @Lock(PESSIMISTIC_WRITE)}
     * produces {@code FOR UPDATE} but has no way to say {@code SKIP LOCKED}, and the difference
     * between those two is the whole property being bought.
     *
     * <p>The lock lives until the caller's transaction ends, so <strong>this must be called inside
     * one</strong>. Called outside, each statement commits on its own, the locks are released
     * immediately, and a second poller can claim rows the first is still sending.
     */
    @Query(
            value =
                    """
                    select * from notifications
                     where status = 'PENDING'
                       and scheduled_for <= :now
                     order by scheduled_for
                     limit :batchSize
                     for update skip locked
                    """,
            nativeQuery = true)
    List<Notification> claimDue(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
