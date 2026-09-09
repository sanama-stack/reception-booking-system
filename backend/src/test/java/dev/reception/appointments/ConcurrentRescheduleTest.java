package dev.reception.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Two owners move the same Appointment to two different times, at the same moment.
 *
 * <p>The exclusion constraint has nothing to say here: the two destinations do not overlap, both
 * moves are individually legal, and the row can hold either. What is being contested is the
 * <em>record</em> rather than a stretch of time, and the only thing that notices is
 * {@code @Version}. Without it the second write wins silently and the first person walks away
 * believing they moved the appointment — the failure nobody reports, because nobody sees it.
 *
 * <p>Deliberately a different code from {@code SLOT_UNAVAILABLE}: retrying after a re-read is the
 * right answer here, and the re-read may well show the other person made the change that was wanted.
 */
class ConcurrentRescheduleTest extends IntegrationTest {

    private static final int MOVERS = 2;

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
    @DisplayName("two simultaneous reschedules: one succeeds, the other is 409 VERSION_CONFLICT")
    void one_move_wins() throws Exception {
        String appointment = aria.bookedAt(aria.at(aria.monday, 10, 0));

        List<AuthTestClient> movers = IntStream.range(0, MOVERS)
                .mapToObj(i -> {
                    AuthTestClient client = new AuthTestClient(rest, port);
                    client.login("nino@aria.test", BookingScenario.PASSWORD);
                    return client;
                })
                .toList();

        // Two destinations, neither of which overlaps the other or the original — so nothing here
        // can be settled by the exclusion constraint.
        List<String> destinations = List.of(
                aria.at(aria.monday, 13, 0).toString(), aria.at(aria.monday, 15, 0).toString());

        CyclicBarrier startLine = new CyclicBarrier(MOVERS);
        List<ResponseEntity<String>> responses = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(MOVERS)) {
            List<Callable<ResponseEntity<String>>> attempts = IntStream.range(0, MOVERS)
                    .mapToObj(i -> (Callable<ResponseEntity<String>>) () -> {
                        startLine.await();
                        return movers.get(i)
                                .post(
                                        "/appointments/" + appointment + "/reschedule",
                                        Map.of("startsAt", destinations.get(i)));
                    })
                    .toList();
            for (Future<ResponseEntity<String>> future : pool.invokeAll(attempts)) {
                responses.add(future.get());
            }
        }

        List<HttpStatus> statuses =
                responses.stream().map(response -> (HttpStatus) response.getStatusCode()).toList();

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(response -> assertThat(BookingScenario.codeOf(response)).isEqualTo("VERSION_CONFLICT"));

        assertThat(jdbc.queryForObject("select count(*) from appointments", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select status from appointments where id = ?::uuid", String.class, appointment))
                .isEqualTo("CONFIRMED");
    }
}
