package dev.reception.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@code /services} — what a Business sells. */
class ServiceEndpointTest extends IntegrationTest {

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

    /** A HashMap rather than Map.of, because Map.of throws on a null value and a description is optional. */
    private ResponseEntity<String> create(String name, int durationMinutes, String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", durationMinutes);
        body.put("price", price);
        return owner.post("/services", body);
    }

    private String createId(String name, int durationMinutes, String price) {
        return JsonPath.read(create(name, durationMinutes, price).getBody(), "$.id");
    }

    // -----------------------------------------------------------------------
    // Create and read
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a created service reads back with its price and the business's currency")
    void creates_and_reads_back() {
        ResponseEntity<String> created = create("Haircut", 45, "60.00");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = created.getBody();
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Haircut");
        assertThat((int) JsonPath.read(body, "$.durationMinutes")).isEqualTo(45);
        // A decimal string, never a JSON number: every JavaScript client that touched a number would
        // turn it into a binary float on the way in (docs/04-api-overview.md §2).
        assertThat((String) JsonPath.read(body, "$.price.amount")).isEqualTo("60.00");
        // Never accepted from the caller — it is the Business's.
        assertThat((String) JsonPath.read(body, "$.price.currency")).isEqualTo("USD");
        assertThat((boolean) JsonPath.read(body, "$.active")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$.employeeIds")).isEmpty();
    }

    @Test
    @DisplayName("a price given without decimals reads back at the column's own scale")
    void normalises_the_price_scale() {
        // Without this the response to a create says "60" and a later read says "60.00", and a form
        // that round-trips the value shows a different string each time.
        String body = create("Haircut", 45, "60").getBody();
        assertThat((String) JsonPath.read(body, "$.price.amount")).isEqualTo("60.00");
    }

    @Test
    @DisplayName("zero is a legitimate price")
    void allows_a_free_service() {
        assertThat(create("Consultation", 15, "0").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("buffers default to zero and are stored when given")
    void stores_buffers() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Colour");
        body.put("durationMinutes", 120);
        body.put("price", "150.00");
        body.put("bufferBeforeMinutes", 10);
        body.put("bufferAfterMinutes", 20);

        String created = owner.post("/services", body).getBody();
        assertThat((int) JsonPath.read(created, "$.bufferBeforeMinutes")).isEqualTo(10);
        assertThat((int) JsonPath.read(created, "$.bufferAfterMinutes")).isEqualTo(20);

        String withoutBuffers = create("Trim", 20, "20.00").getBody();
        assertThat((int) JsonPath.read(withoutBuffers, "$.bufferBeforeMinutes")).isZero();
    }

    @Test
    @DisplayName("the list comes back in name order")
    void lists_in_name_order() {
        create("Haircut", 45, "60.00");
        create("Beard trim", 20, "25.00");

        assertThat(JsonPath.<List<String>>read(owner.get("/services").getBody(), "$.services[*].name"))
                .containsExactly("Beard trim", "Haircut");
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a duplicate name is refused, and the message lands on the name field")
    void refuses_a_duplicate_name() {
        create("Haircut", 45, "60.00");

        ResponseEntity<String> second = create("Haircut", 30, "40.00");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<List<String>>read(second.getBody(), "$.errors[*].field")).containsExactly("name");
    }

    @Test
    @DisplayName("name uniqueness is case-insensitive")
    void refuses_a_duplicate_name_in_another_case() {
        // "Haircut" and "haircut" are two rows an owner cannot tell apart in a dropdown, which is
        // the failure services_business_name_unique exists to prevent.
        create("Haircut", 45, "60.00");

        assertThat(create("HAIRCUT", 30, "40.00").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a service may keep its own name through a patch")
    void allows_a_service_to_keep_its_name() {
        String id = createId("Haircut", 45, "60.00");

        // The collision that matters is with a different row. Without that distinction a patch that
        // does not touch the name would refuse itself.
        ResponseEntity<String> patched = owner.patch("/services/" + id, Map.of("name", "Haircut", "price", "70.00"));

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(patched.getBody(), "$.price.amount")).isEqualTo("70.00");
    }

    @Test
    @DisplayName("a duration off the five-minute grid is refused")
    void refuses_a_duration_off_the_grid() {
        ResponseEntity<String> response = create("Haircut", 47, "60.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors[*].field"))
                .containsExactly("durationMinutes");
    }

    @Test
    @DisplayName("a negative price is refused")
    void refuses_a_negative_price() {
        assertThat(create("Haircut", 45, "-1.00").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("an oversized buffer is refused")
    void refuses_an_oversized_buffer() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Haircut");
        body.put("durationMinutes", 45);
        body.put("price", "60.00");
        body.put("bufferAfterMinutes", 241);

        assertThat(owner.post("/services", body).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("every bad field is reported at once")
    void reports_every_bad_field_at_once() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Haircut");
        body.put("durationMinutes", 7);
        body.put("price", "-5");
        body.put("bufferBeforeMinutes", -1);

        assertThat(JsonPath.<List<String>>read(owner.post("/services", body).getBody(), "$.errors[*].field"))
                .containsExactlyInAnyOrder("durationMinutes", "bufferBeforeMinutes", "price");
    }

    // -----------------------------------------------------------------------
    // Patch
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a patch naming one field leaves the others alone")
    void patches_partially() {
        String id = createId("Haircut", 45, "60.00");

        owner.patch("/services/" + id, Map.of("durationMinutes", 60));

        String body = owner.get("/services/" + id).getBody();
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Haircut");
        assertThat((int) JsonPath.read(body, "$.durationMinutes")).isEqualTo(60);
        assertThat((String) JsonPath.read(body, "$.price.amount")).isEqualTo("60.00");
    }

    @Test
    @DisplayName("a blank description clears it")
    void clears_an_optional_field_with_a_blank() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Haircut");
        body.put("durationMinutes", 45);
        body.put("price", "60.00");
        body.put("description", "With a wash.");
        String id = JsonPath.read(owner.post("/services", body).getBody(), "$.id");

        // What a form sends when the owner empties the box; clearing should not need a second gesture.
        owner.patch("/services/" + id, Map.of("description", ""));

        assertThat(JsonPath.<String>read(owner.get("/services/" + id).getBody(), "$.description"))
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Activation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deactivating hides a service from the active listing but not from the management one")
    void deactivating_hides_it_from_the_active_listing() {
        String id = createId("Haircut", 45, "60.00");
        create("Beard trim", 20, "25.00");

        owner.post("/services/" + id + "/deactivate", Map.of());

        assertThat(JsonPath.<List<String>>read(owner.get("/services?active=true").getBody(), "$.services[*].name"))
                .containsExactly("Beard trim");
        // Omitting the filter lists everything, which is what the management screen wants.
        assertThat(JsonPath.<List<String>>read(owner.get("/services").getBody(), "$.services[*].name"))
                .containsExactly("Beard trim", "Haircut");
        assertThat(JsonPath.<List<String>>read(owner.get("/services?active=false").getBody(), "$.services[*].name"))
                .containsExactly("Haircut");
    }

    @Test
    @DisplayName("a deactivation reports how many upcoming appointments it affects")
    void reports_the_deactivation_impact() {
        String id = createId("Haircut", 45, "60.00");

        String body = owner.post("/services/" + id + "/deactivate", Map.of()).getBody();

        assertThat((boolean) JsonPath.read(body, "$.service.active")).isFalse();
        // Always zero until phase 06, because no appointment can yet exist. The field is published
        // now so the response shape is final and nothing is auto-cancelled when it stops being zero.
        assertThat(((Number) JsonPath.read(body, "$.affectedFutureAppointments")).longValue())
                .isZero();
    }

    @Test
    @DisplayName("re-activating restores it")
    void reactivates() {
        String id = createId("Haircut", 45, "60.00");
        owner.post("/services/" + id + "/deactivate", Map.of());

        owner.post("/services/" + id + "/activate", Map.of());

        assertThat((boolean) JsonPath.read(owner.get("/services/" + id).getBody(), "$.active"))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an unbooked service can be deleted")
    void deletes_an_unbooked_service() {
        String id = createId("Haircut", 45, "60.00");

        assertThat(owner.delete("/services/" + id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(owner.get("/services/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("reading, patching or deleting a service that does not exist is a 404")
    void unknown_services_are_not_found() {
        String missing = UUID.randomUUID().toString();

        assertThat(owner.get("/services/" + missing).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.patch("/services/" + missing, Map.of("name", "X")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.delete("/services/" + missing).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.post("/services/" + missing + "/deactivate", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
