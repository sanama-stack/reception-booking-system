package dev.reception.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
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

/** {@code /business/faqs} — the text the Receptionist is allowed to repeat. */
class FaqEndpointTest extends IntegrationTest {

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

    private ResponseEntity<String> create(String question, String answer) {
        return owner.post("/business/faqs", Map.of("question", question, "answer", answer));
    }

    @Test
    @DisplayName("a created FAQ reads back, appended to the end of the list")
    void creates_and_lists() {
        create("Do you take walk-ins?", "No, appointments only.");
        create("Where do I park?", "There is a car park behind the building.");

        String body = owner.get("/business/faqs").getBody();
        assertThat(JsonPath.<List<String>>read(body, "$.faqs[*].question"))
                .containsExactly("Do you take walk-ins?", "Where do I park?");
        assertThat(JsonPath.<List<Integer>>read(body, "$.faqs[*].sortOrder")).containsExactly(0, 1);
    }

    @Test
    @DisplayName("reordering is a patch of sortOrder, and the list follows it")
    void reorders() {
        String firstId = JsonPath.read(create("First", "A.").getBody(), "$.id");
        String secondId = JsonPath.read(create("Second", "B.").getBody(), "$.id");

        owner.patch("/business/faqs/" + firstId, Map.of("sortOrder", 1));
        owner.patch("/business/faqs/" + secondId, Map.of("sortOrder", 0));

        assertThat(JsonPath.<List<String>>read(owner.get("/business/faqs").getBody(), "$.faqs[*].question"))
                .containsExactly("Second", "First");
    }

    @Test
    @DisplayName("a negative position is refused")
    void rejects_a_negative_sort_order() {
        String id = JsonPath.read(create("First", "A.").getBody(), "$.id");

        assertThat(owner.patch("/business/faqs/" + id, Map.of("sortOrder", -1)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("two FAQs left at the same position still come back in a stable order")
    void orders_ties_by_creation() {
        create("First", "A.");
        create("Second", "B.");
        // Both at 0: without the created_at tie-break this is whatever the heap returns, and the
        // list would reshuffle itself between reloads.
        String secondId = JsonPath.read(owner.get("/business/faqs").getBody(), "$.faqs[1].id");
        owner.patch("/business/faqs/" + secondId, Map.of("sortOrder", 0));

        assertThat(JsonPath.<List<String>>read(owner.get("/business/faqs").getBody(), "$.faqs[*].question"))
                .containsExactly("First", "Second");
    }

    @Test
    @DisplayName("a patch naming one field leaves the others alone")
    void patches_partially() {
        String id = JsonPath.read(create("Do you take walk-ins?", "No.").getBody(), "$.id");

        owner.patch("/business/faqs/" + id, Map.of("answer", "No — appointments only."));

        String body = owner.get("/business/faqs").getBody();
        assertThat((String) JsonPath.read(body, "$.faqs[0].question")).isEqualTo("Do you take walk-ins?");
        assertThat((String) JsonPath.read(body, "$.faqs[0].answer")).isEqualTo("No — appointments only.");
    }

    @Test
    @DisplayName("a deleted FAQ is gone")
    void deletes() {
        String id = JsonPath.read(create("Do you take walk-ins?", "No.").getBody(), "$.id");

        assertThat(owner.delete("/business/faqs/" + id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(JsonPath.<List<String>>read(owner.get("/business/faqs").getBody(), "$.faqs[*].id"))
                .isEmpty();
    }

    @Test
    @DisplayName("patching or deleting a FAQ that does not exist is a 404")
    void unknown_faqs_are_not_found() {
        UUID unknown = UUID.randomUUID();

        assertThat(owner.patch("/business/faqs/" + unknown, Map.of("answer", "x"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.delete("/business/faqs/" + unknown).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the fifty-first FAQ is refused — every one of them enters the system prompt")
    void caps_the_number_of_faqs() {
        for (int i = 0; i < BusinessFaq.MAX_PER_BUSINESS; i++) {
            assertThat(create("Question " + i, "Answer " + i).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> response = create("One too many", "No.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat((String) JsonPath.read(response.getBody(), "$.errors[0].message")).contains("50");
    }

    @Test
    @DisplayName("question and answer lengths are capped")
    void caps_the_text_lengths() {
        assertThat(create("q".repeat(301), "A.").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(create("Q?", "a".repeat(1001)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(create("q".repeat(300), "a".repeat(1000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("an empty question or answer is refused — a blank FAQ teaches the Receptionist nothing")
    void refuses_blank_text() {
        assertThat(create("", "A.").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(create("Q?", "   ").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
