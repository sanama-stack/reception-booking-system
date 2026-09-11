package dev.reception.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reception.appointments.AppointmentRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two bounds that make the availability read indexable, and the constraints that keep them
 * honest. The sibling of {@code CalendarOverlapBoundTest}, for the query the whole booking flow
 * runs on.
 *
 * <p>{@code findByBusinessIdAndEmployeesOverlapping} asks which committed time overlaps a range:
 * {@code blockedFrom < :to AND blockedTo > :from}. Neither blocked column is in any index, so
 * written that way and no other both comparisons can only be applied after the rows have been
 * fetched — PostgreSQL {@code BitmapAnd}s the gist exclusion constraint with
 * {@code appointments_business_starts_idx}, and that one has no range restriction at all. Measured
 * against 10 000 Appointments, one day's question read all 10 000 of the business's index entries
 * to return six.
 *
 * <p>{@code AppointmentRepository} therefore bounds {@code startsAt} on both sides, and
 * <strong>these tests are the reason that is safe rather than merely fast.</strong> The first two
 * prove each bound does not drop the very thing it could plausibly drop; the rest prove the
 * ceilings the bounds depend on cannot be exceeded.
 *
 * <p><strong>Why this asserts on the repository rather than on offered Slots.</strong> Both bounds
 * can only be wrong at their limits, and an Appointment sitting at either limit blocks time in the
 * first four hours of the range or the last — outside every fixture business's opening hours, and
 * so invisible in a grid of bookable Slots. A test driven through {@code GET /availability} could
 * not reach the case the bound is about and would pass by never trying. Same reason
 * {@code CalendarOverlapBoundTest} writes its fixtures with {@link JdbcTemplate}: test at the layer
 * that can actually reach the failure.
 *
 * <p>The rows are written around the application for a second reason too — {@code AvailabilityEngine}
 * cannot <em>produce</em> an Appointment whose blocked range reaches this far, because it requires
 * the whole booking to fit inside one of a date's opening intervals.
 */
class AvailabilityOverlapBoundTest extends IntegrationTest {

    /** {@code Service.MAX_DURATION_MINUTES + Service.MAX_BUFFER_MINUTES} — the backward reach. */
    private static final Duration MAX_REACH_BACK = Duration.ofMinutes(1440 + 240);

    /** {@code Service.MAX_BUFFER_MINUTES} — the forward reach, a leading Buffer and nothing else. */
    private static final Duration MAX_REACH_FORWARD = Duration.ofMinutes(240);

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

    @Autowired
    private AppointmentRepository appointments;

    private BookingScenario aria;

    /** Copied off a real booking, so every foreign key is one the application itself wrote. */
    private UUID businessId;

    private UUID employeeId;
    private UUID serviceId;
    private UUID customerId;

    /** Midnight opening the Monday the scenario books against, in the Business's zone. */
    private Instant from;

    private Instant to;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        Map<String, Object> row = jdbc.queryForMap(
                "select business_id, employee_id, service_id, customer_id from appointments limit 1");
        businessId = (UUID) row.get("business_id");
        employeeId = (UUID) row.get("employee_id");
        serviceId = (UUID) row.get("service_id");
        customerId = (UUID) row.get("customer_id");

        from = aria.at(aria.monday, 0, 0).toInstant();
        to = aria.at(aria.monday.plusDays(1), 0, 0).toInstant();
    }

    /**
     * The backward bound at its limit, which is the only place it can be wrong.
     *
     * <p>A blocked range reaches forward from {@code startsAt} by at most the duration plus the
     * trailing Buffer — 1440 + 240 minutes. So the earliest Appointment that can still block time
     * inside Monday is one starting <em>just after</em> 28 hours before it, whose blocked range then
     * ends just inside. This fixture starts one minute after that limit and blocks the first minute
     * of Monday.
     *
     * <p>It is a real counterfactual, and specifically a counterfactual for the <em>Buffer</em> term:
     * this row starts more than a day before the range, so a bound written with the duration ceiling
     * alone — the calendar's bound, copied across without thinking — would exclude it, the engine
     * would stop seeing committed time, and the slot would be offered to a second Customer.
     */
    @Test
    @DisplayName("the furthest-reaching appointment still blocking the range is still seen")
    void an_appointment_that_began_twenty_eight_hours_earlier_is_not_lost() {
        Instant startsAt = from.minus(MAX_REACH_BACK).plus(Duration.ofMinutes(1));
        Instant endsAt = startsAt.plus(Duration.ofMinutes(1440));
        Instant blockedTo = endsAt.plus(Duration.ofMinutes(240));

        insertAppointment(startsAt, endsAt, startsAt, blockedTo, "REACHBACK");

        assertThat(blockedRanges())
                .as("its blocked range ends one minute inside Monday, so it overlaps")
                .hasSize(2);
    }

    /**
     * The same fixture one minute earlier, to show the test above is not passing by accident of range
     * arithmetic: an Appointment whose blocked range ends exactly as the range opens is genuinely
     * absent, and {@code blockedTo > :from} is strict.
     */
    @Test
    @DisplayName("an appointment whose blocked range ends as the range opens is not seen")
    void an_appointment_reaching_exactly_to_the_boundary_is_absent() {
        Instant startsAt = from.minus(MAX_REACH_BACK);
        Instant endsAt = startsAt.plus(Duration.ofMinutes(1440));
        Instant blockedTo = endsAt.plus(Duration.ofMinutes(240));

        insertAppointment(startsAt, endsAt, startsAt, blockedTo, "TOUCHING");

        assertThat(blockedRanges()).hasSize(1);
    }

    /**
     * The forward bound at its limit. A blocked range reaches <em>backward</em> from {@code startsAt}
     * by at most the leading Buffer, so the latest Appointment that can still block time inside the
     * range is one starting just under four hours after it ends.
     *
     * <p>Unbounded above, this row would be found anyway — the bound is what could wrongly exclude
     * it, so the test is the counterfactual for {@code startsAt < :to + MAX_BUFFER_MINUTES}.
     */
    @Test
    @DisplayName("an appointment starting after the range whose buffer reaches back into it is seen")
    void an_appointment_whose_leading_buffer_reaches_back_is_not_lost() {
        Instant startsAt = to.plus(MAX_REACH_FORWARD).minus(Duration.ofMinutes(1));
        Instant blockedFrom = startsAt.minus(Duration.ofMinutes(240));

        Instant endsAt = startsAt.plus(Duration.ofMinutes(60));

        insertAppointment(startsAt, endsAt, blockedFrom, endsAt, "REACHFWD");

        assertThat(blockedRanges())
                .as("its blocked range begins one minute before Monday ends, so it overlaps")
                .hasSize(2);
    }

    /**
     * <strong>The ceilings, enforced where they cannot be forgotten.</strong>
     *
     * <p>Both bounds are correct only while no Buffer can exceed four hours. Nothing used to hold
     * anyone to that — {@code appointments_time_order} checks that the blocked range contains the
     * Appointment, not that it hugs it — so raising {@code MAX_BUFFER_MINUTES} in Java without
     * widening the query would have made the engine quietly stop seeing some committed time. Unlike
     * the calendar, which would merely fail to draw something, that is how a double booking gets
     * written.
     *
     * <p>Written through {@link JdbcTemplate}, so what is proven is that the <em>database</em>
     * refuses it and not that some validator happened to run.
     */
    @Test
    @DisplayName("the database refuses a leading buffer longer than four hours")
    void a_leading_buffer_past_the_ceiling_cannot_be_written() {
        Instant startsAt = aria.at(aria.monday.plusDays(2), 12, 0).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(60));

        assertThatThrownBy(() -> insertAppointment(
                        startsAt, endsAt, startsAt.minus(Duration.ofMinutes(241)), endsAt, "LONGBEFORE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("appointments_buffer_before_max");
    }

    @Test
    @DisplayName("the database refuses a trailing buffer longer than four hours")
    void a_trailing_buffer_past_the_ceiling_cannot_be_written() {
        Instant startsAt = aria.at(aria.monday.plusDays(2), 12, 0).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(60));

        assertThatThrownBy(() -> insertAppointment(
                        startsAt, endsAt, startsAt, endsAt.plus(Duration.ofMinutes(241)), "LONGAFTER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("appointments_buffer_after_max");
    }

    /** Exactly the maximum is allowed on both sides — a ceiling, not a fencepost error. */
    @Test
    @DisplayName("buffers of exactly four hours are allowed")
    void buffers_of_exactly_the_maximum_are_written() {
        Instant startsAt = aria.at(aria.monday.plusDays(2), 12, 0).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(60));

        insertAppointment(
                startsAt,
                endsAt,
                startsAt.minus(Duration.ofMinutes(240)),
                endsAt.plus(Duration.ofMinutes(240)),
                "EXACTLYFOUR");

        assertThat(jdbc.queryForObject(
                        "select count(*) from appointments where confirmation_code = ?", Integer.class, "EXACTLYFOUR"))
                .isEqualTo(1);
    }

    /** What the engine reads, called exactly as {@code AvailabilityService} calls it. */
    private List<AppointmentRepository.BlockedRangeRow> blockedRanges() {
        return appointments.findByBusinessIdAndEmployeesOverlapping(
                businessId, List.of(employeeId), from, to, null);
    }

    private void insertAppointment(
            Instant startsAt, Instant endsAt, Instant blockedFrom, Instant blockedTo, String code) {
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
                java.sql.Timestamp.from(blockedFrom),
                java.sql.Timestamp.from(blockedTo),
                code);
    }
}
