package dev.reception.perf;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.analytics.AnalyticsService;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentQueryService;
import dev.reception.calendar.CalendarService;
import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.tenancy.TenantAdoption;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The three NFR checks of {@code docs/01-prd.md} §5, against 31 600 Appointments.
 *
 * <p><strong>Not part of the build.</strong> Tagged {@code perf} and therefore excluded like
 * {@code llm} and {@code probe}, because it needs a database CI does not have. Build it first:
 *
 * <pre>
 *   backend/tools/perf-dataset/generate.sh
 *   cd backend && ./gradlew test -PincludeTags=perf --rerun
 * </pre>
 *
 * <p><strong>Why this is a test and not a `psql` session.</strong> Three sessions measured this
 * system's queries by pasting SQL into `psql`, and each had to solve the same two problems by hand:
 * the statement Hibernate actually sends is not the one in the {@code @Query} annotation, and a
 * literal in an {@code EXPLAIN} is not a bound parameter — PostgreSQL constant-folds the first and
 * cannot the second, so a plan taken that way can be one the application never gets (T30).
 *
 * <p>Going through the services solves both by construction. The statement is Hibernate's because
 * Hibernate wrote it; the parameters are bound because JDBC bound them; and the driver switches to
 * a server-side prepared statement after five executions, so the later runs below are on the
 * generic plan that the `PREPARE`/`EXECUTE` recipe existed to reach. It also measures what the NFR
 * is actually about — the operation, entity hydration included — rather than the raw query.
 *
 * <p><strong>What it still does not measure.</strong> No HTTP, no JSON serialisation, no network:
 * the numbers below are one JVM calling one database on the same machine. The buffers are warm,
 * which is G16's complaint about every performance number in this project; `README.md` beside the
 * generator has the recipe for taking a cold one.
 */
@Tag("perf")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=${PERF_JDBC_URL:jdbc:postgresql://localhost:9085/reception_perf}",
            "spring.flyway.enabled=true"
        })
@ActiveProfiles("test")
class NfrBenchmarkTest {

    /** Tenant 0 of the generated dataset — the one holding 10 000 of the 31 600 rows. */
    private static final UUID TARGET = UUID.fromString("11111111-0000-4000-8000-000000000000");

    /** Five discarded, twenty measured. Five is the pgjdbc threshold at which the plan goes generic. */
    private static final int WARMUP = 5;
    private static final int MEASURED = 20;

    @Autowired
    private TenantAdoption tenants;

    @Autowired
    private AvailabilityService availability;

    @Autowired
    private AppointmentQueryService appointments;

    @Autowired
    private AnalyticsService analytics;

    @Autowired
    private CalendarService calendar;

    @Autowired
    private ServiceCatalogService catalog;

    @Autowired
    private Clock clock;

    @Autowired
    private EntityManager entityManager;

    private LocalDate today;

    @BeforeEach
    void adoptTheTargetTenant() {
        tenants.adopt(TARGET);
        today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("availability over 7 days, five employees — p95 < 300 ms")
    void availability_over_a_week() {
        UUID serviceId = catalog.list(true).getFirst().getId();

        Percentiles measured = measure(
                "availability, 7 days",
                () -> availability.find(serviceId, today, today.plusDays(6), null, null));

        // The NFR names three employees; the fixture has five, so this is the harder case.
        assertThat(measured.p95()).isLessThan(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("the appointment list at 10 000 rows — p95 < 200 ms")
    void the_appointment_list() {
        Percentiles measured = measure(
                "appointment list, first page, no filters",
                () -> appointments.list(null, null, null, null, 0, 20));

        assertThat(measured.p95()).isLessThan(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("the analytics summary over 90 days — p95 < 500 ms")
    void the_analytics_summary() {
        Percentiles measured =
                measure("analytics summary, 90 days", () -> analytics.summarise(today.minusDays(89), today));

        assertThat(measured.p95()).isLessThan(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("the calendar week, on Hibernate's own statement — G15")
    void the_calendar_week() {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        // G15: the 46x and 83x recorded for this query in phase 10 were hand-written-SQL numbers.
        // This is the same query as the application issues it. There is no NFR for the calendar, so
        // the assertion is the one the bound exists to make true — that the work is proportional to
        // the week on screen and not to everything the tenant has ever booked.
        Percentiles measured = measure("calendar, one week", () -> calendar.between(monday, monday.plusDays(6)));

        assertThat(measured.p95()).isLessThan(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("the calendar's lower bound, against the query without it — G15")
    void the_calendar_bound_against_its_counterfactual() {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        Instant from = monday.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = monday.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();

        // G15: phase 10 recorded 46x and 83x for this bound, and both were hand-written-SQL numbers
        // on literals. These two are the same JPQL with and without the one line that bounds
        // startsAt from below, run through Hibernate, with parameters bound and past the point the
        // driver switches to a server-side prepared statement — so the ratio is between two plans
        // the application could actually get.
        Percentiles bounded = measure("calendar overlap, bounded", () -> entityManager
                .createQuery(
                        """
                        select a from Appointment a
                         where a.businessId = :businessId
                           and a.startsAt < :to
                           and a.startsAt >= :earliestStart
                           and a.endsAt > :from
                         order by a.startsAt asc
                        """,
                        Appointment.class)
                .setParameter("businessId", TARGET)
                .setParameter("to", to)
                .setParameter("earliestStart", from.minus(Duration.ofMinutes(Service.MAX_DURATION_MINUTES)))
                .setParameter("from", from)
                .getResultList());

        Percentiles unbounded = measure("calendar overlap, UNBOUNDED", () -> entityManager
                .createQuery(
                        """
                        select a from Appointment a
                         where a.businessId = :businessId
                           and a.startsAt < :to
                           and a.endsAt > :from
                         order by a.startsAt asc
                        """,
                        Appointment.class)
                .setParameter("businessId", TARGET)
                .setParameter("to", to)
                .setParameter("from", from)
                .getResultList());

        System.out.printf(
                "calendar bound: %.1fx on p95 (%.2f ms bounded, %.2f ms unbounded)%n",
                (double) unbounded.p95().toNanos() / bounded.p95().toNanos(),
                millis(bounded.p95()),
                millis(unbounded.p95()));

        // READ THAT RATIO CAREFULLY. It is around 1.2x, and phase 10 recorded 46x for the same
        // bound — both are right, and the difference is what G15 asked to find out. 46x was a
        // ratio of QUERY times. This is a ratio of OPERATIONS, and hydrating a week of
        // Appointments costs the same on both sides, so it dilutes the difference. Measured
        // separately on this dataset, the query alone is 0.26 ms against 3.64 ms and 30 buffers
        // against 841 — 14x and 28x.
        //
        // And the constant factor is not the point either way. The unbounded query reads 8 800
        // rows to return 140, and 8 800 is the number of Appointments this tenant has ever had:
        // it is a slope, not a factor. At 30 000 phase 10 watched the same plan give up on the
        // index and sequentially scan the whole table. That is the cliff the bound exists to
        // avoid, and no measurement at one table size can show it.
        assertThat(bounded.p95()).isLessThan(unbounded.p95());
    }

    private Percentiles measure(String what, Supplier<?> operation) {
        for (int i = 0; i < WARMUP; i++) {
            operation.get();
        }

        List<Duration> samples = new ArrayList<>(MEASURED);
        for (int i = 0; i < MEASURED; i++) {
            long startedAt = System.nanoTime();
            Object result = operation.get();
            samples.add(Duration.ofNanos(System.nanoTime() - startedAt));
            // Held so the JIT cannot decide the call had no effect and remove it.
            assertThat(result).isNotNull();
        }

        Percentiles percentiles = Percentiles.of(samples);
        System.out.printf(
                "%-40s  p50 %6.2f ms   p95 %6.2f ms   max %6.2f ms   (%d runs after %d warm-up)%n",
                what,
                millis(percentiles.p50()),
                millis(percentiles.p95()),
                millis(percentiles.max()),
                MEASURED,
                WARMUP);
        return percentiles;
    }

    private static double millis(Duration duration) {
        return duration.toNanos() / 1_000_000.0;
    }

    private record Percentiles(Duration p50, Duration p95, Duration max) {

        static Percentiles of(List<Duration> samples) {
            List<Duration> sorted = samples.stream().sorted().toList();
            return new Percentiles(
                    sorted.get((int) Math.floor(sorted.size() * 0.50)),
                    // The lowest sample at or above the 95th percentile, which for twenty samples
                    // is the nineteenth. Ceiling rather than interpolation: a p95 that can land
                    // between two observations is a number nothing actually took.
                    sorted.get((int) Math.min(Math.ceil(sorted.size() * 0.95) - 1, sorted.size() - 1)),
                    sorted.getLast());
        }
    }
}
