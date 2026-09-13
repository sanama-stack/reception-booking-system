package dev.reception.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.appointments.BookingScenario;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /analytics/summary} — phase 10's one metrics endpoint.
 *
 * <p>The three things the phase document says are easy to get wrong are each asserted against their
 * counterfactual rather than merely exercised: revenue is checked <em>after</em> the Service's price
 * has been edited, the rates are checked over an empty range where the wrong answer is a plausible
 * {@code 0}, and the day boundaries are checked by moving the Business to another timezone and
 * watching the same instant change date.
 */
class AnalyticsSummaryTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private Clock clock;

    private BookingScenario aria;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        aria = BookingScenario.open(rest, port, clock);
    }

    @Test
    @DisplayName("the counts are the four statuses and their total, over the range the owner picked")
    void counts_cover_the_range() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));
        complete(aria.bookedAt(aria.at(aria.monday, 10, 0)));
        cancel(aria.bookedAt(aria.at(aria.monday, 11, 0)));
        noShow(aria.bookedAt(aria.at(aria.monday, 12, 0)));

        String body = summary(aria.monday, aria.monday).getBody();

        assertThat((int) JsonPath.read(body, "$.counts.confirmed")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.counts.completed")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.counts.cancelled")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.counts.noShow")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.counts.total")).isEqualTo(4);
        assertThat(JsonPath.<String>read(body, "$.range.timezone")).isEqualTo(BookingScenario.TBILISI.getId());
    }

    /**
     * <strong>The end of the range is the owner's last day, not the instant it begins.</strong>
     * Resolved exclusively, an appointment on the closing day would vanish from the report — and the
     * owner would never know, because the number would simply be lower.
     */
    @Test
    @DisplayName("an appointment on the last day of the range is inside it")
    void the_range_end_is_inclusive() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        assertThat((int) JsonPath.read(summary(aria.monday.minusDays(3), aria.monday).getBody(), "$.counts.total"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("revenue counts COMPLETED only, and never the appointments that were not attended")
    void revenue_counts_completed_only() {
        complete(aria.bookedAt(aria.at(aria.monday, 9, 0)));
        aria.bookedAt(aria.at(aria.monday, 10, 0));
        cancel(aria.bookedAt(aria.at(aria.monday, 11, 0)));
        noShow(aria.bookedAt(aria.at(aria.monday, 12, 0)));

        String body = summary(aria.monday, aria.monday).getBody();

        // One completed appointment at the fixture's 60.00, and three that earned nothing.
        assertThat(JsonPath.<String>read(body, "$.revenue.amount")).isEqualTo("60.00");
        // The registration default, which the fixture never changes. Asserted rather than ignored:
        // an amount whose currency is guessed by the reader is not a price.
        assertThat(JsonPath.<String>read(body, "$.revenue.currency")).isEqualTo("USD");
        assertThat(JsonPath.<String>read(body, "$.revenue.basis")).isEqualTo("COMPLETED_ONLY");
    }

    /**
     * <strong>The remainder, named rather than dropped.</strong>
     *
     * <p>Appointments keep the currency they were priced in, so after the Business switches currency
     * its older revenue is in a currency the headline figure no longer reports — and summing the two
     * would add lari to dollars and call the result money. {@link
     * dev.reception.analytics.AnalyticsService.Revenue} answers for the current currency alone and
     * puts every other currency found among the same COMPLETED appointments in {@code excluded},
     * each with its own sum, per ADR-0010.
     *
     * <p><strong>The assertion on {@code excluded[*].amount} is the positive control.</strong> A
     * derivation that silently produces nothing — wrong status, wrong comparison, an empty
     * projection — satisfies "the remainder exists and is a list" and every assertion about the
     * headline figure. Only a known non-zero sum, asserted by value, can tell the two apart.
     *
     * <p>A Service stamps the Business's currency at creation and never re-stamps it ({@code
     * Service#currency}), so creating one after the change is the only way a second currency can
     * exist among one business's appointments. That is also why the fixture's own Haircut stays USD.
     */
    @Test
    @DisplayName("after a currency change, revenue reports the new currency and names the remainder beside it")
    void revenue_names_the_remainder_after_a_currency_change() {
        // Priced in the registration default, and attended, before anything changes.
        complete(aria.bookedAt(aria.at(aria.monday, 9, 0)));
        // A second appointment in the same old currency that was never attended. It is here so the
        // remainder has a wrong answer available to it: an entry filtered by currency but not by
        // status reports 120.00.
        cancel(aria.bookedAt(aria.at(aria.monday, 10, 0)));

        aria.owner.patch("/business", Map.of("currency", "GEL"));
        String inGel = BookingScenario.createService(aria.owner, "Blow Dry", 60, "45.00", 0, 0);
        aria.owner.put(
                "/employees/" + aria.employeeId + "/services",
                Map.of("serviceIds", List.of(aria.serviceId, inGel)));
        complete(bookService(inGel, aria.at(aria.monday, 11, 0)));

        String body = summary(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<String>read(body, "$.revenue.currency")).isEqualTo("GEL");
        assertThat(JsonPath.<String>read(body, "$.revenue.amount"))
                .as("the GEL appointment alone; the USD one is not silently added to it")
                .isEqualTo("45.00");
        assertThat(JsonPath.<List<String>>read(body, "$.revenue.excluded[*].currency"))
                .as("one entry per other currency found, and USD is the only one")
                .containsExactly("USD");
        assertThat(JsonPath.<List<String>>read(body, "$.revenue.excluded[*].amount"))
                .as("60.00 and not 120.00: basis covers the remainder too, so the cancelled one is out")
                .containsExactly("60.00");
        assertThat((int) JsonPath.read(body, "$.counts.completed"))
                .as("both attended appointments are counted; only their money is in two currencies")
                .isEqualTo(2);
    }

    /**
     * <strong>Empty, not null, and not absent.</strong>
     *
     * <p>Matching {@code topServices} and deliberately unlike {@code rates}, which is null when
     * there were no appointments at all. The distinction ADR-0010 draws is that {@code rates} has no
     * meaningful zero and a remainder does: "nothing is excluded" is a fact, and a screen that has
     * to branch on null before it can say so will eventually forget to.
     */
    @Test
    @DisplayName("with one currency the remainder is an empty list, not null and not absent")
    void the_remainder_is_empty_when_there_is_none() {
        complete(aria.bookedAt(aria.at(aria.monday, 9, 0)));

        String body = summary(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<String>read(body, "$.revenue.amount")).isEqualTo("60.00");
        assertThat(JsonPath.<List<Object>>read(body, "$.revenue.excluded"))
                .as("a null here reads as empty to any client that uses ?? [] and hides the day it is not")
                .isEmpty();
    }

    /**
     * The snapshot, proven the only way it can be: by moving the price afterwards.
     *
     * <p>An implementation that joined to the catalog passes every other test in this class. It
     * fails this one, and it would have failed it in production as a number that silently changed
     * when nothing about last month did.
     */
    @Test
    @DisplayName("raising the price afterwards does not rewrite the revenue already earned")
    void revenue_is_immune_to_a_later_price_change() {
        complete(aria.bookedAt(aria.at(aria.monday, 9, 0)));
        assertThat(JsonPath.<String>read(summary(aria.monday, aria.monday).getBody(), "$.revenue.amount"))
                .isEqualTo("60.00");

        aria.owner.patch("/services/" + aria.serviceId, Map.of("price", "150.00"));

        assertThat(JsonPath.<String>read(summary(aria.monday, aria.monday).getBody(), "$.revenue.amount"))
                .isEqualTo("60.00");
    }

    /**
     * <strong>Null, not zero.</strong> "0% cancellation" over no appointments is a lie an owner will
     * act on, and it is the answer every naive implementation gives.
     */
    @Test
    @DisplayName("an empty range returns zeroes and null rates, not an error and not 0%")
    void an_empty_range_has_no_rate_at_all() {
        ResponseEntity<String> response = summary(aria.pastMonday, aria.pastMonday);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((int) JsonPath.read(response.getBody(), "$.counts.total")).isZero();
        assertThat(JsonPath.<Object>read(response.getBody(), "$.rates.cancellation")).isNull();
        assertThat(JsonPath.<Object>read(response.getBody(), "$.rates.noShow")).isNull();
        assertThat(JsonPath.<String>read(response.getBody(), "$.revenue.amount")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("the rates are the share of the range's appointments, to a tenth of a percent")
    void rates_are_reported_when_there_is_a_denominator() {
        cancel(aria.bookedAt(aria.at(aria.monday, 9, 0)));
        noShow(aria.bookedAt(aria.at(aria.monday, 10, 0)));
        aria.bookedAt(aria.at(aria.monday, 11, 0));
        aria.bookedAt(aria.at(aria.monday, 12, 0));

        String body = summary(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<Object>read(body, "$.rates.cancellation").toString()).isEqualTo("0.25");
        assertThat(JsonPath.<Object>read(body, "$.rates.noShow").toString()).isEqualTo("0.25");
    }

    /**
     * The boundary question, asked the only way that can fail honestly.
     *
     * <p>16:00 in Tbilisi is 12:00 UTC, which is <strong>00:00 the next day in Auckland</strong>.
     * The appointment does not move; the calendar under it does. A report whose days were cut in UTC
     * or in the server's zone would answer the same both times, and that is the defect.
     */
    @Test
    @DisplayName("the day boundaries follow the business timezone, not the server's")
    void day_boundaries_are_the_businesss_own() {
        aria.bookedAt(aria.at(aria.monday, 16, 0));

        assertThat((int) JsonPath.read(summary(aria.monday, aria.monday).getBody(), "$.counts.total"))
                .isEqualTo(1);

        aria.owner.patch("/business", Map.of("timezone", "Pacific/Auckland"));

        assertThat((int) JsonPath.read(summary(aria.monday, aria.monday).getBody(), "$.counts.total"))
                .as("in Auckland that instant is midnight the following day")
                .isZero();
        assertThat((int)
                        JsonPath.read(
                                summary(aria.monday.plusDays(1), aria.monday.plusDays(1)).getBody(), "$.counts.total"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the top services are ordered by count, and ties break the same way every time")
    void top_services_are_ordered_deterministically() {
        String second = BookingScenario.createService(aria.owner, "Beard Trim", 60, "20.00", 0, 0);
        String third = BookingScenario.createService(aria.owner, "Shave", 60, "25.00", 0, 0);
        aria.owner.put(
                "/employees/" + aria.employeeId + "/services",
                Map.of("serviceIds", List.of(aria.serviceId, second, third)));

        // Haircut twice; the other two once each, so their order is decided by the tie-break alone.
        aria.bookedAt(aria.at(aria.monday, 9, 0));
        aria.bookedAt(aria.at(aria.monday, 10, 0));
        bookService(second, aria.at(aria.monday, 11, 0));
        bookService(third, aria.at(aria.monday, 12, 0));

        String body = summary(aria.monday, aria.monday).getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.topServices[*].name"))
                .containsExactly(
                        "Haircut",
                        // Ids are UUIDv7 and therefore ascending by creation, so the tie-break puts
                        // the service created first ahead of the one created after it.
                        second.compareTo(third) < 0 ? "Beard Trim" : "Shave",
                        second.compareTo(third) < 0 ? "Shave" : "Beard Trim");
        assertThat((int) JsonPath.read(body, "$.topServices[0].count")).isEqualTo(2);
    }

    /**
     * <strong>The periods ignore the range, and that is the decision rather than an accident.</strong>
     *
     * <p>The contract does not say whether {@code today}/{@code thisWeek}/{@code thisMonth} are
     * bounded by the requested range. Bounded, a report on last September would answer "today: 0"
     * for every business on earth; unbounded, they answer the question the dashboard home actually
     * asks. This asserts the second reading by requesting a range that contains nothing at all and
     * watching the periods still count.
     *
     * <p>The fixture's Monday is seven to thirteen days out, so it is never today and never inside
     * the current week — but it is in the current month whenever the month has not turned between
     * the two, which is what makes the positive case here computable rather than hardcoded.
     */
    @Test
    @DisplayName("the periods answer about now, not about the range that was asked for")
    void periods_are_relative_to_today_not_to_the_range() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));
        LocalDate today = LocalDate.now(clock.withZone(BookingScenario.TBILISI));

        // A range that deliberately contains none of it.
        String body = summary(aria.pastMonday, aria.pastMonday).getBody();

        assertThat((int) JsonPath.read(body, "$.counts.total"))
                .as("the range itself is empty")
                .isZero();
        assertThat((int) JsonPath.read(body, "$.periods.today"))
                .as("the fixture books at least a week out, so nothing is today")
                .isZero();
        assertThat((int) JsonPath.read(body, "$.periods.thisWeek"))
                .as("and never inside the current week either")
                .isZero();
        assertThat((int) JsonPath.read(body, "$.periods.thisMonth"))
                .as("but the month still counts it, from outside the range entirely")
                .isEqualTo(aria.monday.getMonth() == today.getMonth()
                                && aria.monday.getYear() == today.getYear()
                        ? 1
                        : 0);
    }

    @Test
    @DisplayName("a range wider than 366 days is refused rather than quietly narrowed")
    void a_range_over_a_year_is_refused() {
        ResponseEntity<String> response = summary(aria.monday, aria.monday.plusDays(366));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("exactly 366 days is allowed, because both ends are inclusive")
    void the_widest_allowed_range_is_answered() {
        assertThat(summary(aria.monday, aria.monday.plusDays(365)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a backwards range is refused, not swapped")
    void a_backwards_range_is_refused() {
        ResponseEntity<String> response = summary(aria.monday, aria.monday.minusDays(1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }

    /**
     * The isolation probe for this endpoint (docs/09-phase-plan.md §5, rule 5).
     *
     * <p>There is no id to borrow here — the endpoint takes only dates — so the leak this guards
     * against is the aggregate itself: a {@code group by} that forgot its {@code businessId} returns
     * a perfectly well-formed summary of somebody else's business mixed into your own.
     */
    @Test
    @DisplayName("another business's appointments are in nobody else's numbers")
    void the_summary_counts_one_tenant_only() {
        aria.bookedAt(aria.at(aria.monday, 9, 0));

        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", BookingScenario.PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("timezone", BookingScenario.TBILISI.getId()));
        String autoService = BookingScenario.createService(auto, "Oil Change", 30, "40.00", 0, 0);
        String autoEmployee = BookingScenario.createEmployee(auto, "Dato Kapanadze");
        auto.put("/employees/" + autoEmployee + "/services", Map.of("serviceIds", List.of(autoService)));
        BookingScenario.setSchedule(auto, autoEmployee, "09:00", "17:00");
        auto.post(
                "/appointments",
                Map.of(
                        "serviceId", autoService,
                        "employeeId", autoEmployee,
                        "startsAt", aria.at(aria.monday, 9, 0).toString(),
                        "customerName", "Giorgi",
                        "customerPhone", "+995555987654"));

        assertThat((int) JsonPath.read(summary(aria.monday, aria.monday).getBody(), "$.counts.total"))
                .as("Aria sees only Aria")
                .isEqualTo(1);
        assertThat((int) JsonPath.read(auto.get(path(aria.monday, aria.monday)).getBody(), "$.counts.total"))
                .as("and Auto sees only Auto")
                .isEqualTo(1);
    }

    private ResponseEntity<String> summary(LocalDate from, LocalDate to) {
        return aria.owner.get(path(from, to));
    }

    private static String path(LocalDate from, LocalDate to) {
        return "/analytics/summary?from=" + from + "&to=" + to;
    }

    private String bookService(String serviceId, OffsetDateTime startsAt) {
        ResponseEntity<String> response = aria.owner.post(
                "/appointments",
                Map.of(
                        "serviceId", serviceId,
                        "employeeId", aria.employeeId,
                        "startsAt", startsAt.toString(),
                        "customerName", "Ana Tsereteli",
                        "customerPhone", BookingScenario.CUSTOMER_PHONE));
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Fixture could not book: " + response.getBody());
        }
        return JsonPath.read(response.getBody(), "$.appointment.id");
    }

    private void complete(String appointmentId) {
        aria.owner.post("/appointments/" + appointmentId + "/status", Map.of("status", "COMPLETED"));
    }

    private void noShow(String appointmentId) {
        aria.owner.post("/appointments/" + appointmentId + "/status", Map.of("status", "NO_SHOW"));
    }

    private void cancel(String appointmentId) {
        aria.owner.post("/appointments/" + appointmentId + "/cancel", Map.of());
    }
}
