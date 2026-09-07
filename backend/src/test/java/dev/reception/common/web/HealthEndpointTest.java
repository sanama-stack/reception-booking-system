package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthEndpointTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void health_reports_up_with_both_dependencies_reachable() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "http://localhost:" + port + "/api/health",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("status", "UP");

        @SuppressWarnings("unchecked")
        Map<String, String> components =
                (Map<String, String>) response.getBody().get("components");
        assertThat(components).containsEntry("database", "UP").containsEntry("mail", "UP");
    }
}
