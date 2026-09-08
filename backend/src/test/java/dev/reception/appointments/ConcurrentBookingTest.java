package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Twenty people click the same slot at the same moment. Exactly one of them gets it.
 *
 * <p><strong>This is the test the whole phase exists for.</strong> Every other test in this package
 * describes a rule the application enforces, and any of them could be satisfied by a careful
 * check-then-write that a second thread walks straight through. This one cannot: the pre-check in
 * {@code BookingService} is <em>expected</em> to pass in more than one thread, and what makes the
 * answer correct is {@code appointments_no_overlap} refusing the second row at the storage layer
 * (ADR-0002).
 *
 * <p>Repeated, because a concurrency test that has passed once has demonstrated very little. A
 * check-then-write implementation passes this suite occasionally.
 *
 * <p>Every thread has its own {@link AuthTestClient} — the class keeps a cookie list, and sharing
 * one across twenty threads would be testing the fixture rather than the server.
 */
class ConcurrentBookingTest extends IntegrationTest {

    private static final int BOOKERS = 20;

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

    private BookingScenario aria;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
    }

    @RepeatedTest(3)
    @DisplayName("twenty threads book one slot: one 201, nineteen 409, exactly one row")
    void exactly_one_booking_survives() throws Exception {
        OffsetDateTime contested = aria.at(aria.monday, 10, 0);

        // Sessions are established before the barrier. A login inside the timed window would be
        // measuring the auth path and would spread the arrivals out, which is the opposite of what
        // this test wants.
        List<AuthTestClient> bookers = IntStream.range(0, BOOKERS)
                .mapToObj(i -> {
                    AuthTestClient client = new AuthTestClient(rest, port);
                    client.login("nino@aria.test", BookingScenario.PASSWORD);
                    return client;
                })
                .toList();

        CyclicBarrier startLine = new CyclicBarrier(BOOKERS);
        List<ResponseEntity<String>> responses = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(BOOKERS)) {
            List<Callable<ResponseEntity<String>>> attempts = IntStream.range(0, BOOKERS)
                    .mapToObj(i -> (Callable<ResponseEntity<String>>) () -> {
                        startLine.await();
                        return book(bookers.get(i), contested, i);
                    })
                    .toList();

            for (Future<ResponseEntity<String>> future : pool.invokeAll(attempts)) {
                responses.add(future.get());
            }
        }

        List<HttpStatus> statuses =
                responses.stream().map(response -> (HttpStatus) response.getStatusCode()).toList();

        assertThat(statuses.stream().filter(HttpStatus.CREATED::equals)).hasSize(1);
        // Every loser gets the same answer, whether it lost to the pre-check or to the constraint.
        // A 500 here would mean the violation reached a handler that did not recognise it.
        assertThat(statuses.stream().filter(HttpStatus.CONFLICT::equals)).hasSize(BOOKERS - 1);
        assertThat(responses.stream()
                        .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                        .map(BookingScenario::codeOf))
                .containsOnly("SLOT_UNAVAILABLE");

        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class))
                .isEqualTo(1);
        // And exactly one audit event: a rolled-back booking must not leave a CREATED behind.
        assertThat(jdbc.queryForObject("select count(*) from appointment_events", Long.class))
                .isEqualTo(1);
    }

    /** A different customer per thread, so nothing is shared but the slot they are all after. */
    private ResponseEntity<String> book(AuthTestClient client, OffsetDateTime startsAt, int index) {
        return client.post(
                "/appointments",
                java.util.Map.of(
                        "serviceId", aria.serviceId,
                        "employeeId", aria.employeeId,
                        "startsAt", startsAt.toString(),
                        "customerName", "Customer " + index,
                        "customerPhone", "+99555512%04d".formatted(index)));
    }
}
