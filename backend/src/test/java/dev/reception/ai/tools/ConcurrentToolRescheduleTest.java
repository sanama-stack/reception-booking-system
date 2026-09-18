package dev.reception.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two Customers move two different appointments onto the same time, through the Receptionist.
 *
 * <p>The route by which a tool reaches the exclusion constraint at all. {@code BookingService} takes
 * an advisory lock per Employee before its re-check, so a losing booking is refused by name and the
 * constraint is a backstop; {@code RescheduleService} takes no such lock, so two moves race to the
 * constraint itself with both re-checks having passed.
 */
class ConcurrentToolRescheduleTest extends IntegrationTest {

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

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private TenantAdoption tenants;

    @Autowired
    private ObjectMapper json;

    private BookingScenario aria;
    private UUID businessId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
        businessId = UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class));
    }

    @RepeatedTest(3)
    @DisplayName("two simultaneous moves onto one time: one moves, the other is told the time went")
    void the_loser_hears_that_the_time_went() throws Exception {
        List<String> appointments = List.of(
                aria.bookedAt(aria.at(aria.monday, 10, 0)), aria.bookedAt(aria.at(aria.monday, 12, 0)));
        OffsetDateTime contested = aria.at(aria.monday, 15, 0);

        CyclicBarrier startLine = new CyclicBarrier(MOVERS);
        List<ObjectNode> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(MOVERS)) {
            List<Callable<ObjectNode>> attempts = IntStream.range(0, MOVERS)
                    .mapToObj(i -> (Callable<ObjectNode>) () -> {
                        // Per thread: the tenant a filter would have resolved, and the authority a
                        // conversation would have earned. Both are thread-confined.
                        tenants.adopt(businessId);
                        UUID appointment = UUID.fromString(appointments.get(i));
                        ToolContext context = new ToolContext(
                                businessId,
                                UUID.randomUUID(),
                                new AuthorizedAppointments(Set.of(appointment)),
                                clock);
                        startLine.await();
                        return registry.execute(
                                "reschedule_appointment",
                                json.readTree("{\"appointment_id\":\"" + appointment + "\",\"new_starts_at\":\""
                                        + contested + "\",\"employee_id\":null}"),
                                context);
                    })
                    .toList();
            for (Future<ObjectNode> future : pool.invokeAll(attempts)) {
                results.add(future.get());
            }
        }

        List<String> outcomes = results.stream()
                .map(result -> result.has("error") ? result.path("error").asText() : "MOVED")
                .sorted()
                .toList();

        assertThat(outcomes)
                .as("what the two movers were told: %s", results)
                .containsExactly("MOVED", "SLOT_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
                        "select count(*) from appointments where starts_at = ?::timestamptz",
                        Long.class,
                        contested.toString()))
                .isEqualTo(1);
    }
}
