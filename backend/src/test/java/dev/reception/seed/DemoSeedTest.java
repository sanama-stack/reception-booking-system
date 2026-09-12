package dev.reception.seed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code make seed}, against a real database.
 *
 * <p>Phase 11's Definition of Done says {@code git clone && make up && make seed} produces a fully
 * working, populated system. That sentence is checked here rather than at a demo: the seeder is
 * driven exactly as {@code SeedRunner} drives it, and everything it wrote is then read back in
 * <strong>SQL</strong>, in each Business's own timezone.
 *
 * <p>Reading it back in SQL is the point. {@link BlueprintCheck} already proves the blueprint is
 * coherent, but it proves it about the same objects the seeder reads — so on its own it could only
 * ever confirm that the plan agrees with itself. These assertions go to the columns, through the
 * conversion to UTC and back, on a JVM deliberately running at UTC+14, which is where a timezone
 * defect would actually show up.
 */
class DemoSeedTest extends IntegrationTest {

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private SeedReset reset;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheDatabase() {
        databaseCleaner.clean();
    }

    @Test
    @DisplayName("both demo tenants land, each in its own timezone and currency")
    void the_two_tenants_are_written() {
        seedAll();

        List<Map<String, Object>> businesses = jdbc.queryForList(
                "select name, slug, timezone, currency, country from businesses order by slug");

        assertThat(businesses)
                .containsExactly(
                        Map.of(
                                "name", "Dato's Auto",
                                "slug", "datos-auto",
                                "timezone", "Europe/Berlin",
                                "currency", "EUR",
                                "country", "DE"),
                        Map.of(
                                "name", "Salon Aria",
                                "slug", "salon-aria",
                                "timezone", "Asia/Tbilisi",
                                "currency", "GEL",
                                "country", "GE"));
    }

    @Test
    @DisplayName("every appointment sits inside its business's opening hours, read in the business's own clock")
    void no_appointment_falls_outside_the_business_hours() {
        seedAll();

        Integer outside = jdbc.queryForObject(
                """
                select count(*) from appointments a
                  join businesses b on b.id = a.business_id
                 where not exists (
                   select 1 from business_hours h
                    where h.business_id = b.id
                      and h.day_of_week = extract(isodow from (a.starts_at at time zone b.timezone))
                      and (a.starts_at at time zone b.timezone)::time >= h.opens_at
                      and (a.ends_at   at time zone b.timezone)::time <= h.closes_at)
                """,
                Integer.class);

        assertThat(outside).isZero();
    }

    @Test
    @DisplayName("every appointment sits inside the employee's own working schedule")
    void no_appointment_falls_outside_the_working_schedule() {
        seedAll();

        Integer outside = jdbc.queryForObject(
                """
                select count(*) from appointments a
                  join businesses b on b.id = a.business_id
                 where not exists (
                   select 1 from employee_schedules s
                    where s.employee_id = a.employee_id
                      and s.day_of_week = extract(isodow from (a.starts_at at time zone b.timezone))
                      and (a.starts_at at time zone b.timezone)::time >= s.starts_at
                      and (a.ends_at   at time zone b.timezone)::time <= s.ends_at)
                """,
                Integer.class);

        assertThat(outside).isZero();
    }

    @Test
    @DisplayName("an appointment's price is in its own business's currency")
    void the_currency_follows_the_business() {
        seedAll();

        Integer mismatched = jdbc.queryForObject(
                """
                select count(*) from appointments a
                  join businesses b on b.id = a.business_id
                 where a.currency <> b.currency
                """,
                Integer.class);

        assertThat(mismatched).isZero();
    }

    @Test
    @DisplayName("the dataset holds past and future appointments in all four statuses")
    void the_statuses_are_mixed() {
        seedAll();

        // Analytics has nothing to report without completed history, and the demo script has
        // nothing to close out without a confirmed appointment that has already happened.
        assertThat(jdbc.queryForList("select distinct status from appointments", String.class))
                .containsExactlyInAnyOrder("CONFIRMED", "COMPLETED", "NO_SHOW", "CANCELLED");

        Integer past = jdbc.queryForObject("select count(*) from appointments where starts_at < now()", Integer.class);
        Integer future =
                jdbc.queryForObject("select count(*) from appointments where starts_at >= now()", Integer.class);
        assertThat(past).isPositive();
        assertThat(future).isPositive();
    }

    @Test
    @DisplayName("a service with an employee gap is on screen from the first minute")
    void the_barber_is_not_assigned_to_colour() {
        seedAll();

        // Salon Aria's barber performs neither Colour nor Balayage, deliberately: a catalog where
        // every Employee does everything cannot demonstrate what the engine does when one does not.
        Integer assignments = jdbc.queryForObject(
                """
                select count(*) from employee_services a
                  join employees e on e.id = a.employee_id
                  join services s on s.id = a.service_id
                 where e.full_name = 'Giorgi Tsiklauri' and s.name in ('Colour', 'Balayage')
                """,
                Integer.class);

        assertThat(assignments).isZero();
    }

    @Test
    @DisplayName("seeding twice leaves one copy, not two")
    void the_seed_is_repeatable() {
        seedAll();
        Map<String, Object> first = counts();

        List<Blueprint.Tenant> tenants = DemoTenants.all();
        reset.clear(
                tenants.stream().map(tenant -> tenant.profile().slug()).toList(),
                tenants.stream().map(tenant -> tenant.owner().email()).toList());
        tenants.forEach(seeder::seed);

        // The same numbers, not doubled ones — and no EMAIL_TAKEN from the second registration,
        // which is what the reset exists to prevent. `make seed` is used more than once by
        // definition: the first run is the one nobody needs.
        assertThat(counts()).isEqualTo(first);
    }

    @Test
    @DisplayName("the outbox is emptied, so Mailpit starts with nothing in it")
    void the_seeds_own_cancellation_mail_is_cleared() {
        List<UUID> businessIds =
                DemoTenants.all().stream().map(seeder::seed).map(TenantSeeder.Seeded::businessId).toList();

        // Cancelling goes through the real service, which enqueues a real cancellation email. That
        // is the seed's own doing rather than the demo's, so the runner clears it — otherwise the
        // first thing a reviewer sees in Mailpit is mail nobody sent.
        assertThat(jdbc.queryForObject("select count(*) from notifications", Integer.class))
                .isPositive();

        reset.clearOutbox(businessIds);

        assertThat(jdbc.queryForObject("select count(*) from notifications", Integer.class))
                .isZero();
    }

    private void seedAll() {
        DemoTenants.all().forEach(seeder::seed);
    }

    private Map<String, Object> counts() {
        return jdbc.queryForMap(
                """
                select (select count(*) from businesses)   businesses,
                       (select count(*) from users)        users,
                       (select count(*) from services)     services,
                       (select count(*) from employees)    employees,
                       (select count(*) from customers)    customers,
                       (select count(*) from appointments) appointments
                """);
    }
}
