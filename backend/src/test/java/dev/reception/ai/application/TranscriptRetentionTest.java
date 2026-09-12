package dev.reception.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The retention window: what the purge takes, what it spares, and what it leaves behind to say so.
 *
 * <p>Ninety days after a conversation's last activity, its transcript is deleted. Until this class
 * existed nothing in the system had ever deleted an {@code ai_message}, and a transcript holds the
 * Customer's name and phone number as they typed them.
 *
 * <p><strong>Every deletion assertion here is paired with a survival assertion, deliberately.</strong>
 * A purge that did nothing would pass a test that only checked what is gone, and a purge that
 * emptied the table would pass a test that only checked a count. Neither can pass a pair, and
 * {@code purgeBatch()} returns its own row count so no assertion in this file rests on an absence
 * alone.
 *
 * <p>Rows are planted with SQL rather than talked into existing against a model. What is under test
 * is a cutoff and a {@code DELETE}, and every row needs a {@code last_message_at} ninety days in the
 * past — which no conversation driven through the loop can have.
 */
class TranscriptRetentionTest extends IntegrationTest {

    /** Comfortably past the window, and not a boundary case — those are their own tests. */
    private static final Duration WELL_PAST_THE_WINDOW =
            Duration.ofDays(ConversationLimits.TRANSCRIPT_RETENTION_DAYS + 30);

    private static final Duration WELL_INSIDE_THE_WINDOW =
            Duration.ofDays(ConversationLimits.TRANSCRIPT_RETENTION_DAYS - 30);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private TranscriptPurge purge;

    @Autowired
    private TranscriptPurgeRepository repository;

    private AuthTestClient owner;
    private UUID businessId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", "a-long-enough-password", "Salon Aria");
        businessId = UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class));
    }

    // ------------------------------------------------------- the window itself

    @Test
    @DisplayName("a transcript past the window is deleted and one inside it is untouched")
    void the_window_is_enforced_in_both_directions() {
        UUID aged = conversation(businessId, ago(WELL_PAST_THE_WINDOW), 3);
        UUID recent = conversation(businessId, ago(WELL_INSIDE_THE_WINDOW), 3);

        int deleted = purge.purgeBatch();

        // Exactly three: not "at least one", which a purge of the whole table would also satisfy.
        assertThat(deleted).isEqualTo(3);
        assertThat(messageCountOf(aged)).isZero();
        assertThat(messageCountOf(recent)).isEqualTo(3);
    }

    @Test
    @DisplayName("the cutoff is the conversation's last activity, not each message's own age")
    void a_conversation_still_active_keeps_messages_older_than_the_window() {
        // The shape this guards against: a conversation opened a year ago and spoken in yesterday.
        // Anchored on each message's created_at, the purge would take its opening turns and leave
        // the rest — a transcript beginning mid-sentence, which is the half-scrubbed state phase 11
        // rejected when it chose purging over redaction.
        UUID straddling = conversation(businessId, ago(WELL_INSIDE_THE_WINDOW), 0);
        message(straddling, businessId, ago(Duration.ofDays(400)));
        message(straddling, businessId, ago(Duration.ofDays(200)));
        message(straddling, businessId, ago(WELL_INSIDE_THE_WINDOW));

        int deleted = purge.purgeBatch();

        assertThat(deleted).isZero();
        assertThat(messageCountOf(straddling)).isEqualTo(3);
    }

    // ------------------------------------------------------- what is left behind

    @Test
    @DisplayName("the conversation row survives the purge, marked, with its accounting intact")
    void the_parent_row_is_kept_and_says_what_happened_to_it() {
        UUID aged = conversation(businessId, ago(WELL_PAST_THE_WINDOW), 3);

        purge.purgeBatch();

        assertThat(purgedAt(aged)).isNotNull();

        // message_count is NOT decremented, and that is the point of messages_purged_at. Without
        // the column, "count of three, transcript of none" is indistinguishable from a conversation
        // that was opened and never spoken in — which is what the transcript screen says about it.
        assertThat(jdbc.queryForObject(
                        "select message_count from ai_conversations where id = ?", Integer.class, aged))
                .isEqualTo(3);

        // The cost accounting is the reason the row is kept at all.
        assertThat(jdbc.queryForObject(
                        "select estimated_cost_cents from ai_conversations where id = ?", Integer.class, aged))
                .isEqualTo(7);
    }

    @Test
    @DisplayName("a conversation that was never spoken in is marked, and stops being a candidate")
    void an_empty_conversation_leaves_the_candidate_set() {
        // A customer who opened the chat panel and closed the tab. It has nothing to delete, and an
        // unmarked one would be re-claimed by every run from now until the database is dropped.
        UUID emptyAndAged = conversation(businessId, ago(WELL_PAST_THE_WINDOW), 0);

        assertThat(purge.purgeBatch()).isZero();

        assertThat(purgedAt(emptyAndAged)).isNotNull();
        assertThat(repository.claimPurgeable(clock.instant(), 100)).isEmpty();
    }

    @Test
    @DisplayName("a second run over the same conversations claims nothing")
    void the_purge_is_idempotent() {
        conversation(businessId, ago(WELL_PAST_THE_WINDOW), 3);

        assertThat(purge.purgeBatch()).isEqualTo(3);

        // Not merely "deletes nothing" — claims nothing. The work of the second run is what the
        // messages_purged_at guard and the partial index exist to remove, and a purge that still
        // selected these rows would report zero deletions just as convincingly.
        assertThat(repository.claimPurgeable(clock.instant(), 100)).isEmpty();
        assertThat(purge.purgeBatch()).isZero();
    }

    // ------------------------------------------------------- the actor, and the bound

    @Test
    @DisplayName("one run purges every tenant, because the system is the actor")
    void the_purge_is_not_tenant_scoped() {
        AuthTestClient second = new AuthTestClient(rest, port);
        second.register("dato@auto.test", "a-long-enough-password", "Dato's Auto");
        UUID otherBusiness = UUID.fromString(jdbc.queryForObject(
                "select id::text from businesses where id <> ?::uuid", String.class, businessId));

        UUID mine = conversation(businessId, ago(WELL_PAST_THE_WINDOW), 2);
        UUID theirs = conversation(otherBusiness, ago(WELL_PAST_THE_WINDOW), 2);

        // Four, in one batch. A purge that had been written tenant-scoped — reading a TenantContext
        // that a scheduled thread does not have — would take two at most, and most likely none.
        assertThat(purge.purgeBatch()).isEqualTo(4);
        assertThat(messageCountOf(mine)).isZero();
        assertThat(messageCountOf(theirs)).isZero();
    }

    @Test
    @DisplayName("a claim takes no more than its batch size")
    void one_run_is_bounded() {
        conversation(businessId, ago(WELL_PAST_THE_WINDOW), 1);
        conversation(businessId, ago(WELL_PAST_THE_WINDOW), 1);
        conversation(businessId, ago(WELL_PAST_THE_WINDOW), 1);

        // Asserted at the repository rather than by building a TranscriptPurge with a batch size of
        // one: constructed by hand it would have no transaction, and the FOR UPDATE SKIP LOCKED
        // this query depends on would behave differently than it does in production.
        assertThat(repository.claimPurgeable(clock.instant(), 2)).hasSize(2);
    }

    // ------------------------------------------------------- what the owner then sees

    @Test
    @DisplayName("the API names the purge rather than leaving an empty transcript to be guessed at")
    void a_purged_conversation_is_readable_and_honest() {
        UUID aged = conversation(businessId, ago(WELL_PAST_THE_WINDOW), 3);

        purge.purgeBatch();

        ResponseEntity<String> response = owner.get("/conversations/" + aged);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // Still readable — the row was kept, so this is a 200 and not a 404.
        Object purgedAt = JsonPath.read(response.getBody(), "$.conversation.messagesPurgedAt");
        assertThat(purgedAt).isNotNull();
        assertThat((List<?>) JsonPath.read(response.getBody(), "$.messages")).isEmpty();
        assertThat((Integer) JsonPath.read(response.getBody(), "$.conversation.messageCount"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a conversation inside the window reports no purge")
    void an_unpurged_conversation_says_so() {
        // The counterfactual for the assertion above: messagesPurgedAt has to be capable of being
        // absent, or "is not null" proves nothing about the purge having run.
        UUID recent = conversation(businessId, ago(WELL_INSIDE_THE_WINDOW), 3);

        purge.purgeBatch();

        ResponseEntity<String> response = owner.get("/conversations/" + recent);
        Object purgedAt = JsonPath.read(response.getBody(), "$.conversation.messagesPurgedAt");
        assertThat(purgedAt).isNull();
        assertThat((List<?>) JsonPath.read(response.getBody(), "$.messages")).hasSize(3);
    }

    // ------------------------------------------------------- planting

    private Instant ago(Duration duration) {
        return clock.instant().minus(duration);
    }

    /** A conversation last active at {@code lastMessageAt}, with {@code messages} lines in it. */
    private UUID conversation(UUID business, Instant lastMessageAt, int messages) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into ai_conversations (
                    id, business_id, session_token_hash, status, message_count,
                    prompt_tokens, completion_tokens, estimated_cost_cents,
                    authorized_appointment_ids, started_at, last_message_at)
                values (?, ?, ?, 'CLOSED', ?, 900, 300, 7, '{}', ?, ?)
                """,
                id,
                business,
                // Unique per row: the column is UNIQUE, and two planted conversations would
                // otherwise collide on the second insert. Thirty-two hex characters, twice, is the
                // sixty-four the column holds.
                id.toString().replace("-", "").repeat(2),
                messages,
                Timestamp.from(lastMessageAt),
                Timestamp.from(lastMessageAt));

        for (int i = 0; i < messages; i++) {
            message(id, business, lastMessageAt);
        }
        return id;
    }

    private void message(UUID conversationId, UUID business, Instant createdAt) {
        jdbc.update(
                """
                insert into ai_messages (id, conversation_id, business_id, role, content, created_at)
                values (?, ?, ?, 'USER', ?, ?)
                """,
                UUID.randomUUID(),
                conversationId,
                business,
                // The shape of the thing this deletes: a name and a phone number, as typed.
                "Hi, it's Nino, my number is +995555123456",
                Timestamp.from(createdAt));
    }

    private int messageCountOf(UUID conversationId) {
        return jdbc.queryForObject(
                "select count(*) from ai_messages where conversation_id = ?", Integer.class, conversationId);
    }

    private Instant purgedAt(UUID conversationId) {
        Timestamp at = jdbc.queryForObject(
                "select messages_purged_at from ai_conversations where id = ?", Timestamp.class, conversationId);
        return at == null ? null : at.toInstant();
    }
}
