package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

/**
 * The isolation probes for phase 03's six endpoint groups — the first phase that has any, because
 * it is the first with tenant-scoped resources to probe (docs/09-phase-plan.md §5, rule 5).
 *
 * <p>Two shapes are covered, because there are two ways to reach another tenant's data. An endpoint
 * that takes an id is handed one that exists but belongs to someone else, and must answer
 * {@code 404} — not {@code 403}, which would confirm the row exists (docs/06-security.md §3). An
 * endpoint that takes no id is asked for its collection, and must return only its own.
 *
 * <p>Every id used here is real. Probing with a random UUID would pass against an implementation
 * that had no tenant filter at all.
 */
class BusinessIsolationTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    /** Salon Aria — the caller doing the probing. */
    private AuthTestClient aria;

    /** Datos Auto — the tenant whose ids are being borrowed. */
    private AuthTestClient auto;

    private String autoClosureId;
    private String autoFaqId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        aria = new AuthTestClient(rest, port);
        aria.register("nino@aria.test", PASSWORD, "Salon Aria");

        auto = new AuthTestClient(rest, port);
        auto.register("dato@auto.test", PASSWORD, "Datos Auto");
        auto.patch("/business", Map.of("city", "Batumi", "description", "Datos private description"));
        auto.put("/business/hours", Map.of("hours", List.of(Map.of("dayOfWeek", 7, "opensAt", "11:00", "closesAt", "19:00"))));
        autoClosureId = JsonPath.read(
                auto.post("/business/closures", Map.of("startDate", "2026-08-01", "endDate", "2026-08-14"))
                        .getBody(),
                "$.closure.id");
        autoFaqId = JsonPath.read(
                auto.post("/business/faqs", Map.of("question", "Datos question?", "answer", "Datos answer."))
                        .getBody(),
                "$.id");
    }

    @Test
    @DisplayName("GET /business returns only the caller's own profile")
    void business_profile_is_not_shared() {
        String body = aria.get("/business").getBody();

        assertThat((String) JsonPath.read(body, "$.slug")).isEqualTo("salon-aria");
        assertThat(body).doesNotContain("Batumi").doesNotContain("Datos private description");
    }

    @Test
    @DisplayName("PATCH /business changes the caller's own business and no one else's")
    void a_patch_cannot_reach_another_tenant() {
        aria.patch("/business", Map.of("city", "Tbilisi"));

        assertThat((String) JsonPath.read(auto.get("/business").getBody(), "$.city")).isEqualTo("Batumi");
    }

    @Test
    @DisplayName("GET /business/hours returns only the caller's own week")
    void hours_are_not_shared() {
        assertThat(JsonPath.<List<Integer>>read(aria.get("/business/hours").getBody(), "$.hours[*].dayOfWeek"))
                .containsExactly(1, 2, 3, 4, 5)
                .doesNotContain(7);
    }

    @Test
    @DisplayName("PUT /business/hours replaces only the caller's own week")
    void replacing_hours_cannot_reach_another_tenant() {
        aria.put("/business/hours", Map.of("hours", List.of()));

        assertThat(JsonPath.<List<Integer>>read(auto.get("/business/hours").getBody(), "$.hours[*].dayOfWeek"))
                .containsExactly(7);
    }

    @Test
    @DisplayName("GET /business/closures returns only the caller's own")
    void closures_are_not_shared() {
        assertThat(JsonPath.<List<String>>read(aria.get("/business/closures").getBody(), "$.closures[*].id"))
                .isEmpty();
    }

    @Test
    @DisplayName("deleting another tenant's closure is a 404, and leaves it in place")
    void another_tenants_closure_cannot_be_deleted() {
        assertThat(aria.delete("/business/closures/" + autoClosureId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(JsonPath.<List<String>>read(auto.get("/business/closures").getBody(), "$.closures[*].id"))
                .containsExactly(autoClosureId);
    }

    @Test
    @DisplayName("GET /business/faqs returns only the caller's own")
    void faqs_are_not_shared() {
        assertThat(JsonPath.<List<String>>read(aria.get("/business/faqs").getBody(), "$.faqs[*].id"))
                .isEmpty();
    }

    @Test
    @DisplayName("patching another tenant's FAQ is a 404, and leaves it unchanged")
    void another_tenants_faq_cannot_be_patched() {
        assertThat(aria.patch("/business/faqs/" + autoFaqId, Map.of("answer", "Rewritten by a stranger."))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat((String) JsonPath.read(auto.get("/business/faqs").getBody(), "$.faqs[0].answer"))
                .isEqualTo("Datos answer.");
    }

    @Test
    @DisplayName("deleting another tenant's FAQ is a 404, and leaves it in place")
    void another_tenants_faq_cannot_be_deleted() {
        assertThat(aria.delete("/business/faqs/" + autoFaqId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(JsonPath.<List<String>>read(auto.get("/business/faqs").getBody(), "$.faqs[*].id"))
                .containsExactly(autoFaqId);
    }

    @Test
    @DisplayName("GET /business/onboarding describes only the caller's own business")
    void onboarding_is_not_shared() {
        assertThat((String) JsonPath.read(aria.get("/business/onboarding").getBody(), "$.bookingUrl"))
                .isEqualTo("/book/salon-aria");
    }

    /**
     * The same probe, with the tenant named where a careless implementation would read it. Nothing
     * here should change any answer, because no endpoint declares a parameter that could carry one
     * — which {@code TenantRepositoryShapeTest} enforces at compile time and this confirms at run
     * time.
     */
    @Test
    @DisplayName("naming another tenant in the query string changes nothing")
    void a_caller_supplied_business_id_is_ignored() {
        String otherId = JsonPath.read(auto.get("/business").getBody(), "$.id");

        String body = aria.get("/business?businessId=" + otherId).getBody();

        assertThat((String) JsonPath.read(body, "$.slug")).isEqualTo("salon-aria");
        assertThat(body).doesNotContain(otherId);
    }
}
