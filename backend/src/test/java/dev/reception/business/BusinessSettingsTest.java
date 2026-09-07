package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET} and {@code PATCH /business} over real HTTP.
 *
 * <p>Everything here is asserted by reading the value back rather than by trusting the response to
 * the write. A patch that returns the value it was given while writing nothing is a bug this suite
 * has to be able to see.
 */
class BusinessSettingsTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient owner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = new AuthTestClient(rest, port);
        owner.register("nino@aria.test", PASSWORD, "Salon Aria");
    }

    @Test
    @DisplayName("a freshly registered business reads back with its defaults")
    void reads_the_defaults_registration_created() {
        String body = owner.get("/business").getBody();

        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Salon Aria");
        assertThat((String) JsonPath.read(body, "$.slug")).isEqualTo("salon-aria");
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("UTC");
        assertThat((String) JsonPath.read(body, "$.currency")).isEqualTo("USD");
        assertThat((int) JsonPath.read(body, "$.slotIntervalMinutes")).isEqualTo(15);
        assertThat((int) JsonPath.read(body, "$.minLeadTimeMinutes")).isEqualTo(60);
        assertThat((int) JsonPath.read(body, "$.maxAdvanceDays")).isEqualTo(60);
        assertThat((int) JsonPath.read(body, "$.cancellationWindowHours")).isEqualTo(24);
        assertThat((boolean) JsonPath.read(body, "$.aiEnabled")).isTrue();
        assertThat((int) JsonPath.read(body, "$.aiDailyCostCapCents")).isEqualTo(500);
        assertThat((String) JsonPath.read(body, "$.bookingUrl")).isEqualTo("/book/salon-aria");
    }

    @Test
    @DisplayName("every profile and settings field can be patched and read back")
    void patches_every_field() {
        Map<String, Object> everything = new HashMap<>();
        everything.put("name", "Salon Aria Tbilisi");
        everything.put("timezone", "Asia/Tbilisi");
        everything.put("currency", "GEL");
        everything.put("description", "A three-chair salon on Rustaveli.");
        everything.put("addressLine", "12 Rustaveli Ave");
        everything.put("city", "Tbilisi");
        everything.put("country", "GE");
        everything.put("phone", "+995322000000");
        everything.put("email", "hello@aria.test");
        everything.put("website", "https://aria.test");
        everything.put("slotIntervalMinutes", 30);
        everything.put("minLeadTimeMinutes", 120);
        everything.put("maxAdvanceDays", 90);
        everything.put("cancellationWindowHours", 48);
        everything.put("cancellationPolicy", "Cancel at least two days ahead.");
        everything.put("aiEnabled", false);
        everything.put("aiAdditionalInfo", "We do not take walk-ins.");
        everything.put("aiDailyCostCapCents", 1500);

        assertThat(owner.patch("/business", everything).getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = owner.get("/business").getBody();
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Salon Aria Tbilisi");
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("Asia/Tbilisi");
        assertThat((String) JsonPath.read(body, "$.currency")).isEqualTo("GEL");
        assertThat((String) JsonPath.read(body, "$.description")).isEqualTo("A three-chair salon on Rustaveli.");
        assertThat((String) JsonPath.read(body, "$.addressLine")).isEqualTo("12 Rustaveli Ave");
        assertThat((String) JsonPath.read(body, "$.city")).isEqualTo("Tbilisi");
        assertThat((String) JsonPath.read(body, "$.country")).isEqualTo("GE");
        assertThat((String) JsonPath.read(body, "$.phone")).isEqualTo("+995322000000");
        assertThat((String) JsonPath.read(body, "$.email")).isEqualTo("hello@aria.test");
        assertThat((String) JsonPath.read(body, "$.website")).isEqualTo("https://aria.test");
        assertThat((int) JsonPath.read(body, "$.slotIntervalMinutes")).isEqualTo(30);
        assertThat((int) JsonPath.read(body, "$.minLeadTimeMinutes")).isEqualTo(120);
        assertThat((int) JsonPath.read(body, "$.maxAdvanceDays")).isEqualTo(90);
        assertThat((int) JsonPath.read(body, "$.cancellationWindowHours")).isEqualTo(48);
        assertThat((String) JsonPath.read(body, "$.cancellationPolicy"))
                .isEqualTo("Cancel at least two days ahead.");
        assertThat((boolean) JsonPath.read(body, "$.aiEnabled")).isFalse();
        assertThat((String) JsonPath.read(body, "$.aiAdditionalInfo")).isEqualTo("We do not take walk-ins.");
        assertThat((int) JsonPath.read(body, "$.aiDailyCostCapCents")).isEqualTo(1500);
    }

    @Test
    @DisplayName("a patch naming one field leaves the sixteen it does not mention alone")
    void a_partial_patch_does_not_erase_the_rest() {
        owner.patch("/business", Map.of("city", "Tbilisi", "description", "A salon."));

        owner.patch("/business", Map.of("maxAdvanceDays", 30));

        String body = owner.get("/business").getBody();
        assertThat((String) JsonPath.read(body, "$.city")).isEqualTo("Tbilisi");
        assertThat((String) JsonPath.read(body, "$.description")).isEqualTo("A salon.");
        assertThat((int) JsonPath.read(body, "$.maxAdvanceDays")).isEqualTo(30);
    }

    @Test
    @DisplayName("a blank value clears an optional field — which is what an emptied form input sends")
    void a_blank_value_clears_an_optional_field() {
        owner.patch("/business", Map.of("city", "Tbilisi"));

        owner.patch("/business", Map.of("city", ""));

        assertThat((String) JsonPath.read(owner.get("/business").getBody(), "$.city"))
                .isNull();
    }

    @Test
    @DisplayName("changing the slug moves the public booking url, and the old slug stops resolving")
    void a_slug_change_moves_the_public_url() {
        owner.patch("/business", Map.of("slug", "aria-tbilisi"));

        String body = owner.get("/business").getBody();
        assertThat((String) JsonPath.read(body, "$.slug")).isEqualTo("aria-tbilisi");
        assertThat((String) JsonPath.read(body, "$.bookingUrl")).isEqualTo("/book/aria-tbilisi");
        assertThat((String) JsonPath.read(owner.get("/business/onboarding").getBody(), "$.bookingUrl"))
                .isEqualTo("/book/aria-tbilisi");
        // The old slug is not held anywhere: nothing in the response still names it.
        assertThat(body).doesNotContain("salon-aria");
    }

    @Test
    @DisplayName("a slug another business already holds is refused, not silently suffixed")
    void a_taken_slug_is_refused() {
        AuthTestClient other = new AuthTestClient(rest, port);
        other.register("dato@auto.test", PASSWORD, "Datos Auto");

        ResponseEntity<String> response = owner.patch("/business", Map.of("slug", "datos-auto"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("SLUG_TAKEN");
        // Registration suffixes a derived slug; a requested one is refused, because the owner would
        // otherwise print an address they were never given.
        assertThat((String) JsonPath.read(owner.get("/business").getBody(), "$.slug"))
                .isEqualTo("salon-aria");
    }

    @Test
    @DisplayName("patching the slug to the one already held is not a conflict with itself")
    void re_submitting_the_current_slug_is_allowed() {
        assertThat(owner.patch("/business", Map.of("slug", "salon-aria")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a slug is lowercased rather than rejected for its case alone")
    void a_slug_is_normalised_before_it_is_validated() {
        owner.patch("/business", Map.of("slug", "  Aria-Tbilisi  "));

        assertThat((String) JsonPath.read(owner.get("/business").getBody(), "$.slug"))
                .isEqualTo("aria-tbilisi");
    }

    @Test
    @DisplayName("an unreal timezone is rejected with a sentence naming the field")
    void rejects_a_timezone_that_is_not_a_place() {
        ResponseEntity<String> response = owner.patch("/business", Map.of("timezone", "Europe/Atlantis"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat((String) JsonPath.read(response.getBody(), "$.errors[0].field")).isEqualTo("timezone");
    }

    @Test
    @DisplayName("every failure in one submission is reported at once")
    void reports_every_invalid_field_together() {
        ResponseEntity<String> response = owner.patch(
                "/business", Map.of("timezone", "Europe/Atlantis", "currency", "XYZ", "country", "ZZ"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<java.util.List<String>>read(response.getBody(), "$.errors[*].field"))
                .containsExactlyInAnyOrder("timezone", "currency", "country");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
        "slotIntervalMinutes, 7",
        "slotIntervalMinutes, 90",
        "minLeadTimeMinutes, -1",
        "minLeadTimeMinutes, 10081",
        "maxAdvanceDays, 0",
        "maxAdvanceDays, 366",
        "cancellationWindowHours, -1",
        "cancellationWindowHours, 169",
        "aiDailyCostCapCents, -1",
    })
    @DisplayName("a setting outside its range is rejected before it can reach the CHECK constraint")
    void rejects_settings_outside_their_range(String field, int value) {
        ResponseEntity<String> response = owner.patch("/business", Map.of(field, value));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("the Receptionist's extra information is capped at 2000 characters")
    void rejects_over_long_ai_additional_info() {
        ResponseEntity<String> response =
                owner.patch("/business", Map.of("aiAdditionalInfo", "x".repeat(2001)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.errors[0].field")).isEqualTo("aiAdditionalInfo");

        assertThat(owner.patch("/business", Map.of("aiAdditionalInfo", "x".repeat(2000)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a business cannot be left without a name")
    void rejects_a_blank_name() {
        assertThat(owner.patch("/business", Map.of("name", "")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("the configuration surface is not reachable without a session")
    void requires_a_session() {
        AuthTestClient anonymous = new AuthTestClient(rest, port);
        assertThat(anonymous.get("/business").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
