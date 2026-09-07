package dev.reception.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The error contract is published (docs/04-api-overview.md §3), so it is asserted from phase 01 —
 * every later phase's error codes ride on this shape.
 */
class ProblemJsonTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void an_unknown_endpoint_returns_problem_json_with_a_machine_readable_code() {
        ResponseEntity<String> response =
                rest.getForEntity("http://localhost:" + port + "/api/no-such-endpoint", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .hasToString("application/problem+json");
        assertThat(response.getBody())
                .contains("\"code\":\"NOT_FOUND\"")
                .contains("\"type\":\"https://reception.dev/errors/not-found\"")
                .contains("\"requestId\"");
    }

    @Test
    void an_error_body_never_leaks_framework_internals() {
        ResponseEntity<String> response =
                rest.getForEntity("http://localhost:" + port + "/api/no-such-endpoint", String.class);

        assertThat(response.getBody())
                .doesNotContain("org.springframework")
                .doesNotContain("Exception")
                .doesNotContain("at dev.reception");
    }
}
