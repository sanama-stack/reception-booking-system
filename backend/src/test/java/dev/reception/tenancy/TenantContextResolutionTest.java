package dev.reception.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.auth.MembershipRepository;
import dev.reception.business.BusinessRepository;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The tenancy seam, asserted end to end.
 *
 * <p>Phase 02 has no tenant-scoped resource endpoint yet, so there is nothing to probe with another
 * tenant's id — the isolation suite proper starts in phase 03 and is completed reflectively in
 * phase 11. What can be asserted now is the property everything else will rest on: the business a
 * request operates as comes from the session, and a caller cannot influence it.
 */
class TenantContextResolutionTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    @Test
    void a_session_operates_as_the_business_its_membership_names() {
        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");
        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");

        assertThat(aria.get("/auth/me").getBody()).contains("\"slug\":\"salon-aria\"").doesNotContain("datos-auto");
        assertThat(auto.get("/auth/me").getBody()).contains("\"slug\":\"datos-auto\"").doesNotContain("salon-aria");
    }

    /**
     * The rule the whole isolation design rests on (docs/04-api-overview.md §1). A caller naming
     * another tenant must be ignored, not obeyed — and the way this codebase achieves that is that
     * there is no parameter to obey.
     */
    @Test
    void a_business_id_supplied_by_the_caller_is_ignored() {
        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");
        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");
        var otherBusinessId = businesses.findBySlug("datos-auto").orElseThrow().getId();

        ResponseEntity<String> viaQuery = aria.get("/auth/me?businessId=" + otherBusinessId);

        assertThat(viaQuery.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(viaQuery.getBody()).contains("\"slug\":\"salon-aria\"").doesNotContain(otherBusinessId.toString());
    }

    /** The token carries the tenant, so it must survive a rotation unchanged. */
    @Test
    void the_tenant_survives_a_refresh() {
        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");

        aria.post("/auth/refresh", null);

        assertThat(aria.get("/auth/me").getBody()).contains("\"slug\":\"salon-aria\"");
    }

    @Test
    void registration_creates_exactly_one_owner_membership_pointing_at_the_new_business() {
        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");

        var business = businesses.findBySlug("salon-aria").orElseThrow();
        assertThat(memberships.findAll())
                .singleElement()
                .satisfies(membership -> assertThat(membership.businessId()).isEqualTo(business.getId()));
    }

    /** Nothing in the login body can name a tenant either. */
    @Test
    void login_ignores_a_business_id_in_the_request_body() {
        AuthTestClient aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");
        AuthTestClient auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");
        var otherBusinessId = businesses.findBySlug("datos-auto").orElseThrow().getId();

        AuthTestClient fresh = new AuthTestClient(rest, port);
        ResponseEntity<String> response = fresh.post(
                "/auth/login",
                Map.of("email", "nino@aria.test", "password", PASSWORD, "businessId", otherBusinessId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"slug\":\"salon-aria\"");
    }
}
