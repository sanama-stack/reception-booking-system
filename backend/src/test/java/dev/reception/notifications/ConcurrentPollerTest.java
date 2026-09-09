package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Two pollers, one queue, and nobody gets the same email twice.
 *
 * <p><strong>This is the test {@code FOR UPDATE SKIP LOCKED} exists for.</strong> Every other
 * dispatcher test would pass against a plain {@code SELECT … WHERE status = 'PENDING'}: one thread
 * claims, sends and marks, and the assertions hold. With two threads that query is a
 * check-then-write — both read the same rows before either marks them — and every customer in the
 * batch is emailed twice. The locking clause is what makes the second poller walk past rows the
 * first is still working on rather than duplicate them.
 *
 * <p>The sender is slow on purpose. Without the pause the first transaction would very often finish
 * before the second even began, and the test would be green for the wrong reason.
 *
 * <p>Repeated, for the same reason {@code ConcurrentBookingTest} is: a concurrency test that passed
 * once has demonstrated very little.
 */
@ContextConfiguration(classes = ConcurrentPollerTest.SlowRecordingSender.class)
class ConcurrentPollerTest extends IntegrationTest {

    private static final int ROWS = 12;
    private static final int POLLERS = 2;

    /**
     * Records every recipient it is handed, and takes its time about it.
     *
     * <p>A {@code @TestConfiguration} rather than a Mockito mock: the assertion is about what two
     * threads did concurrently, and a mock's invocation list is not the place to reason about that.
     */
    @TestConfiguration
    static class SlowRecordingSender {

        final ConcurrentLinkedQueue<String> sent = new ConcurrentLinkedQueue<>();

        @Bean
        @Primary
        EmailSender recordingEmailSender() {
            return (recipient, message) -> {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                sent.add(recipient);
            };
        }
    }

    @Autowired
    private SlowRecordingSender sender;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private UUID appointmentId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        sender.sent.clear();
        appointmentId = OutboxFixture.appointmentWithEmptyOutbox(rest, port, clock, jdbc);
    }

    @RepeatedTest(3)
    @DisplayName("two pollers drain one queue: every row sent exactly once")
    void no_row_is_sent_twice() throws Exception {
        Instant due = clock.instant().minus(1, ChronoUnit.HOURS);
        // CANCELLATION rows: the one type an appointment may legitimately hold many of, which is
        // what lets a dozen of them exist against a single fixture appointment.
        List<String> recipients = IntStream.range(0, ROWS)
                .mapToObj(i -> "poller" + i + "@example.test")
                .toList();
        for (int i = 0; i < ROWS; i++) {
            OutboxFixture.row(
                    jdbc,
                    clock,
                    appointmentId,
                    NotificationType.CANCELLATION,
                    due.plusSeconds(i),
                    0,
                    recipients.get(i));
        }

        CyclicBarrier startLine = new CyclicBarrier(POLLERS);
        try (ExecutorService pool = Executors.newFixedThreadPool(POLLERS)) {
            List<Callable<Integer>> polls = IntStream.range(0, POLLERS)
                    .mapToObj(i -> (Callable<Integer>) () -> {
                        startLine.await();
                        return dispatcher.dispatchDueBatch();
                    })
                    .toList();
            int totalReported = 0;
            for (Future<Integer> poll : pool.invokeAll(polls)) {
                totalReported += poll.get();
            }
            assertThat(totalReported).isEqualTo(ROWS);
        }

        // The assertion that matters: no address appears twice. A duplicate here is a customer
        // receiving the same confirmation email from two application instances.
        assertThat(sender.sent).hasSize(ROWS).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(recipients);
        assertThat(jdbc.queryForObject("select count(*) from notifications where status = 'SENT'", Long.class))
                .isEqualTo((long) ROWS);
    }
}
