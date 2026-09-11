package dev.reception.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The lower bound that makes {@code GET /calendar} indexable, and the constraint that keeps it
 * honest.
 *
 * <p>The calendar asks for Appointments that <strong>overlap</strong> a range. Written as
 * {@code starts_at < :to AND ends_at > :from} and nothing else, {@code starts_at} has no lower end:
 * the index range begins at the tenant's first ever Appointment, and {@code ends_at} — which no
 * index covers — can only be applied as a filter afterwards. Against 10 000 Appointments that read
 * 8 820 rows to return 20; at 30 000 PostgreSQL abandoned the index and sequentially scanned the
 * whole table.
 *
 * <p>{@code AppointmentRepository} therefore bounds it by {@link
 * dev.reception.catalog.Service#MAX_DURATION_MINUTES}, and <strong>these tests are the reason that
 * is safe rather than merely fast.</strong> The first proves the bound does not drop the very thing
 * it could plausibly drop; the second proves the ceiling the bound depends on cannot be exceeded.
 *
 * <p>Both rows are written with {@link JdbcTemplate}, around the application. That is deliberate:
 * {@code AvailabilityEngine} cannot currently <em>produce</em> an Appointment that crosses midnight,
 * because it requires the whole booking to fit inside one of a date's opening intervals and an
 * interval does not span days. A test that could only build its fixture through the booking
 * endpoint could therefore not reach this case at all, and would pass by never trying.
 */
class CalendarOverlapBoundTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private BookingScenario aria;

    /** Copied off a real booking, so every foreign key is one the application itself wrote. */
    private UUID businessId;

    private UUID employeeId;
    private UUID serviceId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        Map<String, Object> row = jdbc.queryForMap("select business_id, employee_id, service_id, customer_id "
                + "from appointments limit 1");
        businessId = (UUID) row.get("business_id");
        employeeId = (UUID) row.get("employee_id");
        serviceId = (UUID) row.get("service_id");
        customerId = (UUID) row.get("customer_id");
    }

    /**
     * The bound at its limit, which is the only place it can be wrong.
     *
     * <p>An Appointment can be at most {@code MAX_DURATION_MINUTES} — exactly one day — so the
     * earliest one that can still reach into Monday is one starting <em>just after</em> Sunday
     * 00:00 and running the full maximum. This fixture starts at Sunday 00:30 and ends Monday 00:30:
     * thirty minutes of overlap, and the furthest back the query has any business looking.
     *
     * <p>It is a real counterfactual. Narrow the bound by so much as an hour and this row falls
     * outside it, the calendar stops drawing an Appointment that is genuinely in view, and this test
     * goes red — which is exactly the failure the bound could otherwise introduce silently.
     */
    @Test
    @DisplayName("the longest possible appointment reaching into the day is still drawn")
    void an_appointment_that_began_the_previous_day_is_not_lost() {
        LocalDate sunday = aria.monday.minusDays(1);
        Instant startsAt = aria.at(sunday, 0, 30).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(1440));

        insertAppointment(startsAt, endsAt, "OVERNIGHT");

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.appointments[*].id"))
                .as("an appointment running from Sunday 00:30 to Monday 00:30 overlaps Monday")
                .hasSize(2);
    }

    /**
     * The same fixture one minute longer, to show the first test is not passing by accident of
     * range arithmetic: an Appointment that ends before the range opens is genuinely absent.
     */
    @Test
    @DisplayName("an appointment that ends before the day begins is not drawn")
    void an_appointment_that_ends_before_the_range_is_absent() {
        LocalDate sunday = aria.monday.minusDays(1);
        Instant startsAt = aria.at(sunday, 10, 0).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofHours(2));

        insertAppointment(startsAt, endsAt, "SUNDAYONLY");

        String body = calendar(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<List<Object>>read(body, "$.appointments[*]")).hasSize(1);
    }

    /**
     * <strong>The ceiling, enforced where it cannot be forgotten.</strong>
     *
     * <p>The bound above is correct only while no Appointment can last longer than a day. Nothing
     * used to hold anyone to that — {@code appointments_time_order} checks that the times are
     * ordered, not that they are close together — so raising {@code MAX_DURATION_MINUTES} in Java
     * without widening the query would have made the calendar quietly stop drawing long
     * Appointments. A wrong answer, arriving silently, on a screen whose whole job is to show
     * everything.
     *
     * <p>{@code V8} turns that into a failed write instead. Written through {@link JdbcTemplate},
     * so what is proven is that the <em>database</em> refuses it and not that some validator
     * happened to run.
     */
    @Test
    @DisplayName("the database refuses an appointment longer than a day")
    void an_appointment_longer_than_a_day_cannot_be_written() {
        Instant startsAt = aria.at(aria.monday.plusDays(1), 0, 0).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(1441));

        assertThatThrownBy(() -> insertAppointment(startsAt, endsAt, "TOOLONG"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("appointments_max_length");
    }

    /** Exactly the maximum is allowed — the constraint is a ceiling, not a fencepost error. */
    @Test
    @DisplayName("an appointment of exactly a day is allowed")
    void an_appointment_of_exactly_the_maximum_is_written() {
        Instant startsAt = aria.at(aria.monday.plusDays(1), 0, 0).toInstant();

        insertAppointment(startsAt, startsAt.plus(Duration.ofMinutes(1440)), "EXACTLYADAY");

        assertThat(jdbc.queryForObject(
                        "select count(*) from appointments where confirmation_code = ?", Integer.class, "EXACTLYADAY"))
                .isEqualTo(1);
    }

    private void insertAppointment(Instant startsAt, Instant endsAt, String code) {
        jdbc.update(
                """
                insert into appointments (
                    id, business_id, employee_id, service_id, customer_id,
                    starts_at, ends_at, blocked_from, blocked_to,
                    status, price_amount, currency, confirmation_code, source,
                    version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', 50.00, 'GEL', ?, 'DASHBOARD', 0, now(), now())
                """,
                UUID.randomUUID(),
                businessId,
                employeeId,
                serviceId,
                customerId,
                java.sql.Timestamp.from(startsAt),
                java.sql.Timestamp.from(endsAt),
                java.sql.Timestamp.from(startsAt),
                java.sql.Timestamp.from(endsAt),
                code);
    }

    private org.springframework.http.ResponseEntity<String> calendar(LocalDate from, LocalDate to) {
        var response = aria.owner.get("/calendar?from=" + from + "&to=" + to);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }
}
