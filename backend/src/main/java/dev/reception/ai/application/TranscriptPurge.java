package dev.reception.ai.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes one batch of transcripts past the retention window. The <em>what</em>;
 * {@link TranscriptPurgeJob} is the <em>when</em>.
 *
 * <p>Split from the job for the reason {@code NotificationDispatcher} is split from
 * {@code NotificationPoller}, and the first half of it is not stylistic. A {@code @Scheduled}
 * method calling a {@code @Transactional} one on {@code this} would bypass the proxy and run with
 * no transaction at all — the {@code FOR UPDATE SKIP LOCKED} locks would be released statement by
 * statement, and the delete and the mark would commit separately. Across two beans the call goes
 * through the proxy and the transaction is real. The second half is that tests can drive a batch
 * directly instead of waiting on a scheduler.
 *
 * <p><strong>What this deletes, and what it does not.</strong> The {@code ai_messages} rows go; the
 * {@code ai_conversations} row stays, marked. The transcript is the part that holds a Customer's
 * name and phone number as they typed them; the parent holds counters and a cost estimate, which is
 * the record of what the Receptionist cost this business and outlives the conversation's usefulness
 * as evidence. That split is a principal decision, recorded in V10's header.
 *
 * <p><strong>The cutoff is the conversation's last activity, not each message's own age.</strong>
 * Anchored per message, a conversation straddling the boundary would lose its opening turns and
 * keep the rest — a transcript that begins mid-sentence, which is exactly the half-scrubbed state
 * phase 11 rejected when it chose purging over redaction.
 */
@Component
public class TranscriptPurge {

    private static final Logger log = LoggerFactory.getLogger(TranscriptPurge.class);

    private final TranscriptPurgeRepository repository;
    private final Clock clock;
    private final int batchSize;

    public TranscriptPurge(
            TranscriptPurgeRepository repository,
            Clock clock,
            @Value("${app.ai.retention.batch-size:200}") int batchSize) {
        this.repository = repository;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Claims up to {@code batchSize} conversations past the window and deletes their messages.
     *
     * <p>One batch per call rather than a loop to exhaustion. A database that has never been purged
     * — every one of them, the first time this runs — could hold years of transcripts, and a single
     * unbounded {@code DELETE} would hold locks across all of them while it ran. Bounded, the first
     * run takes a bite and the next timer tick takes the next; the window is a floor on how long
     * something is kept, never a ceiling, so arriving at it over several minutes costs nothing.
     *
     * @return how many transcript lines were deleted, which is what the job logs and the tests
     *     assert on
     */
    @Transactional
    public int purgeBatch() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(ConversationLimits.TRANSCRIPT_RETENTION_DAYS));

        List<UUID> conversations = repository.claimPurgeable(cutoff, batchSize);
        if (conversations.isEmpty()) {
            return 0;
        }

        int deleted = repository.deleteMessagesOf(conversations);

        // Marked even when the delete removed nothing. A conversation that was opened and never
        // spoken in has no messages to take, and leaving it unmarked would hold it in the candidate
        // set — and in the partial index — forever, re-claimed by every run from now on.
        repository.markPurged(conversations, clock.instant());

        // Conversation ids and counts. No content, no customer id, no business id: a log line about
        // deleting personal data is a poor place to write some down (docs/06-security.md §10).
        log.info(
                "Retention purge: {} transcript line(s) deleted from {} conversation(s) last active before {}",
                deleted,
                conversations.size(),
                cutoff);
        return deleted;
    }
}
