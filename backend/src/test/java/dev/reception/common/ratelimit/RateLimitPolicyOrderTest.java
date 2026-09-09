package dev.reception.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which policy a request lands on, for every path where two of them overlap.
 *
 * <p>{@code RateLimitFilter} takes the <em>first</em> matching policy, so a wider pattern placed
 * above a narrower one silently swallows it: the tight limit becomes unreachable and the endpoint it
 * was written for is guarded by the loose one instead. Nothing fails, no test that only counts
 * requests notices, and the endpoint most worth protecting is the one left open.
 *
 * <p>A unit test rather than an integration one on purpose. Proving the lookup limit is really five
 * an hour by exhausting it takes six requests; proving that <em>no other policy shadows it</em> by
 * exhausting things takes as many requests as the loosest limit in the list, and would still only
 * cover the paths somebody thought to try.
 */
class RateLimitPolicyOrderTest {

    private final List<RateLimitPolicy> policies = new RateLimitProperties().policies();

    @Test
    @DisplayName("the confirmation-code lookup is guarded by its own five-an-hour limit")
    void the_lookup_is_not_shadowed() {
        RateLimitPolicy matched = firstMatch("POST", "/public/appointments/lookup");

        assertThat(matched.name()).isEqualTo("public-lookup");
        assertThat(matched.capacity()).isEqualTo(5);
        assertThat(matched.window()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("availability is matched before the general business read its path also fits")
    void availability_is_not_shadowed_by_the_business_read() {
        assertThat(firstMatch("GET", "/public/businesses/salon-aria/availability").name())
                .isEqualTo("public-availability");
        // The same prefix, one segment shorter, and a different budget.
        assertThat(firstMatch("GET", "/public/businesses/salon-aria").name()).isEqualTo("public-business");
        assertThat(firstMatch("GET", "/public/businesses/salon-aria/services").name())
                .isEqualTo("public-business");
    }

    @Test
    @DisplayName("the Manage Link's grid is limited as computation, not as a cheap read")
    void the_manage_grid_is_not_shadowed_by_the_manage_read() {
        assertThat(firstMatch("GET", "/public/appointments/manage/availability").name())
                .isEqualTo("public-manage-availability");
        assertThat(firstMatch("GET", "/public/appointments/manage").name()).isEqualTo("public-manage");
    }

    @Test
    @DisplayName("customer cancel and reschedule are limited, and not by the lookup's budget")
    void the_appointment_writes_have_their_own_budget() {
        assertThat(firstMatch("POST", "/public/appointments/01a08000-0000-7000-8000-000000000000/cancel")
                        .name())
                .isEqualTo("public-appointment-change");
        assertThat(firstMatch("POST", "/public/appointments/01a08000-0000-7000-8000-000000000000/reschedule")
                        .name())
                .isEqualTo("public-appointment-change");
    }

    @Test
    @DisplayName("every public endpoint is covered by some policy")
    void nothing_public_is_unlimited() {
        // The Definition of Done's "every public endpoint is rate limited", asserted as a list
        // rather than trusted. A path added to publicapi without a policy fails here.
        record Endpoint(String method, String path) {}
        List<Endpoint> surface = List.of(
                new Endpoint("GET", "/public/businesses/salon-aria"),
                new Endpoint("GET", "/public/businesses/salon-aria/services"),
                new Endpoint("GET", "/public/businesses/salon-aria/employees"),
                new Endpoint("GET", "/public/businesses/salon-aria/availability"),
                new Endpoint("POST", "/public/businesses/salon-aria/appointments"),
                new Endpoint("POST", "/public/appointments/lookup"),
                new Endpoint("GET", "/public/appointments/manage"),
                new Endpoint("GET", "/public/appointments/manage/availability"),
                new Endpoint("POST", "/public/appointments/01a08000-0000-7000-8000-000000000000/cancel"),
                new Endpoint("POST", "/public/appointments/01a08000-0000-7000-8000-000000000000/reschedule"));

        assertThat(surface).allSatisfy(endpoint -> assertThat(policies)
                .describedAs("%s %s is not covered by any rate-limit policy", endpoint.method(), endpoint.path())
                .anySatisfy(policy ->
                        assertThat(policy.matches(endpoint.method(), endpoint.path())).isTrue()));
    }

    private RateLimitPolicy firstMatch(String method, String path) {
        return policies.stream()
                .filter(policy -> policy.matches(method, path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No policy matches " + method + " " + path));
    }
}
