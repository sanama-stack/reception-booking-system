package dev.reception.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.common.logging.RequestIdFilter;
import dev.reception.support.IntegrationTest;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/06-security.md §11, on the path that can actually leak.
 *
 * <p>{@link ProblemJsonTest} asserts the error contract against an unknown endpoint — a 404 raised
 * by the dispatcher, which never had a stack trace, a SQL statement or a class name to give away.
 * The claims in §11 are about the opposite case: an exception that escapes a controller, which is
 * the only way framework internals can reach a client. Nothing exercised it.
 *
 * <p>The exceptions are thrown by a controller registered in <em>this test's context alone</em>,
 * through a nested {@code @TestConfiguration}, which gives this class its own context cache key.
 * It is deliberately not added to the application: {@code EndpointCoverageTest} and
 * {@code PublicSurfaceSweepTest} both read the mappings of the context they run in, so a route that
 * escaped into theirs would fail them loudly rather than widen the public surface quietly.
 */
class ErrorLeakageTest extends IntegrationTest {

    /** Everything a response must never carry, whatever the exception was (§11). */
    private static final List<String> INTERNALS = List.of(
            "org.springframework",
            "java.lang",
            "java.sql",
            "Exception",
            "at dev.reception",
            "insert into",
            "select ",
            "null value in column",
            "appointments_no_overlap",
            "hunter2");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    // ---- the catch-all ---------------------------------------------------------------------

    @Test
    void an_exception_escaping_a_controller_becomes_a_generic_500() {
        ResponseEntity<String> response = get("/api/public/test-errors/unhandled");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"INTERNAL_ERROR\"");
        assertNothingLeaked(response);
    }

    /**
     * The request id is the whole of what a 500 gives a caller, and it is only worth having if it
     * matches the one in the log. The header is what the log line is keyed on.
     */
    @Test
    void the_generic_500_carries_a_request_id_that_matches_the_header() {
        ResponseEntity<String> response = get("/api/public/test-errors/unhandled");

        String header = response.getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(header).as("the filter still sets the header").isNotBlank();
        assertThat(response.getBody())
                .as("a request id in the body that differs from the header is worse than none")
                .contains("\"requestId\":\"" + header + "\"");
    }

    /** A deep cause chain is where a stack trace usually escapes — the top frame is rarely the leak. */
    @Test
    void a_nested_cause_chain_leaks_nothing_from_any_level() {
        ResponseEntity<String> response = get("/api/public/test-errors/nested");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertNothingLeaked(response);
    }

    // ---- the database paths ----------------------------------------------------------------

    /**
     * An unmapped integrity violation is a defect, and the driver's message for one carries the
     * failing statement verbatim. It is the single most likely way SQL reaches a client.
     */
    @Test
    void an_unmapped_integrity_violation_leaks_no_sql() {
        ResponseEntity<String> response = get("/api/public/test-errors/sql");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("\"code\":\"INTERNAL_ERROR\"");
        assertNothingLeaked(response);
    }

    /**
     * The mapped one. It is a 409 and a sentence a customer can act on — and it still must not name
     * the constraint that produced it, which is a fact about the schema.
     */
    @Test
    void the_mapped_overlap_violation_is_a_409_that_names_no_constraint() {
        ResponseEntity<String> response = get("/api/public/test-errors/overlap");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"SLOT_UNAVAILABLE\"").contains("booked while you were");
        assertNothingLeaked(response);
    }

    // ---- the test's own floor --------------------------------------------------------------

    /**
     * Every assertion above is a {@code doesNotContain}, and those pass just as happily against a
     * route that does not exist — a 404 leaks nothing either. This proves the throwing routes are
     * actually mapped, so the suite cannot quietly become vacuous.
     */
    @Test
    void the_throwing_routes_are_really_mapped() {
        for (String path : List.of("unhandled", "nested", "sql", "overlap")) {
            assertThat(get("/api/public/test-errors/" + path).getStatusCode())
                    .as("%s must reach the controller, not the 404 handler", path)
                    .isNotEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity("http://localhost:" + port + path, String.class);
    }

    private static void assertNothingLeaked(ResponseEntity<String> response) {
        for (String internal : INTERNALS) {
            assertThat(response.getBody())
                    .as("an error body must not carry %s", internal)
                    .doesNotContain(internal);
        }
    }

    /**
     * Registered only for this class. The routes sit under {@code /public/**} because that is the
     * surface reachable without a session, which keeps the test about error bodies rather than
     * about logging in.
     */
    @TestConfiguration
    static class ThrowingEndpoints {

        @Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    @RestController
    static class ThrowingController {

        /** The message carries a secret, a class name and a frame, so a leak of any kind shows. */
        @GetMapping("/public/test-errors/unhandled")
        String unhandled() {
            throw new IllegalStateException(
                    "java.lang.IllegalStateException at dev.reception.Booking with password=hunter2");
        }

        @GetMapping("/public/test-errors/nested")
        String nested() {
            throw new RuntimeException(
                    "wrapper",
                    new IllegalArgumentException(
                            "middle", new SQLException("ERROR: null value in column \"name\" of relation customers")));
        }

        /** What the driver actually hands Spring when a constraint it does not map is violated. */
        @GetMapping("/public/test-errors/sql")
        String sql() {
            throw new DataIntegrityViolationException(
                    "could not execute statement [ERROR: null value in column \"name\"]"
                            + " [insert into customers (id,name,phone) values (?,?,?)]",
                    new SQLException("ERROR: null value in column \"name\" of relation customers"));
        }

        @GetMapping("/public/test-errors/overlap")
        String overlap() {
            throw new DataIntegrityViolationException(
                    "could not execute statement [ERROR: conflicting key value violates exclusion"
                            + " constraint \"appointments_no_overlap\"]"
                            + " [insert into appointments (id,slot) values (?,?)]",
                    new SQLException("conflicting key value violates exclusion constraint"));
        }
    }
}
