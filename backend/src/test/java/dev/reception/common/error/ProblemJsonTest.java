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

    /**
     * The wrong method on a real, public path. It exercises the "no such endpoint" mapping without
     * needing a session, and it is the case a client is most likely to hit by accident.
     */
    @Test
    void an_unknown_endpoint_returns_problem_json_with_a_machine_readable_code() {
        ResponseEntity<String> response =
                rest.getForEntity("http://localhost:" + port + "/api/auth/login", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody())
                .contains("\"code\":\"NOT_FOUND\"")
                .contains("\"type\":\"https://reception.dev/errors/not-found\"")
                .contains("\"requestId\"");
    }

    /**
     * An unauthenticated request to a path that does not exist is answered exactly like one to a
     * path that does: {@code 401}, not {@code 404}.
     *
     * <p>This is the same reasoning that makes a cross-tenant lookup a {@code 404} rather than a
     * {@code 403} (docs/06-security.md §3) — a status that varies with existence is a way to
     * enumerate the thing it is protecting. Phase 01 saw a {@code 404} here only because nothing
     * was yet protected.
     */
    @Test
    void an_unauthenticated_request_does_not_reveal_whether_an_endpoint_exists() {
        ResponseEntity<String> unknown =
                rest.getForEntity("http://localhost:" + port + "/api/no-such-endpoint", String.class);
        ResponseEntity<String> real = rest.getForEntity("http://localhost:" + port + "/api/auth/me", String.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(unknown.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
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
