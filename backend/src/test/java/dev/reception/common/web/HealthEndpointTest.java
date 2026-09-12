package dev.reception.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.IntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The health endpoint, on both sides of every check.
 *
 * <p>Until now this class held one test: both dependencies reachable, both reported {@code UP}.
 * That test cannot fail for the reason this endpoint exists. Replace the body of {@code
 * checkDatabase()} with {@code return UP;} and it stays green — as does an endpoint that has
 * stopped checking anything at all and simply says so. Two handoffs declined to tick the phase-11
 * row on the strength of it, and were right to: a monitor that cannot report {@code DOWN} is worse
 * than no monitor, because something is watching it.
 *
 * <p>So each dependency is failed here, one at a time, against a real refused connection rather
 * than a stub that throws. <strong>Every failing case asserts the other component is still {@code
 * UP}</strong>, which is what keeps these from passing against a controller that reports {@code
 * DOWN} unconditionally — the failure mode symmetrical to the one that made the original test
 * vacuous.
 *
 * <p>The failing cases construct the controller rather than going over HTTP, because the
 * dependencies have to be broken ones and the application's are shared with every other test in the
 * suite. The status mapping is still under test: it is the controller that decides {@code 503}, and
 * {@link #both_reachable_is_up()} runs the whole wiring over the wire.
 */
class HealthEndpointTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    /** The application's own, reachable — the working half of every mixed case below. */
    @Autowired
    private DataSource workingDatabase;

    @Autowired
    private JavaMailSenderImpl workingMail;

    @Test
    @DisplayName("both dependencies reachable, over the wire, is 200 and UP")
    void both_reachable_is_up() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "http://localhost:" + port + "/api/health",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(components(response)).containsEntry("database", "UP").containsEntry("mail", "UP");
    }

    @Test
    @DisplayName("a database that refuses the connection is 503, and only the database is DOWN")
    void a_refused_database_is_reported_down() {
        ResponseEntity<Map<String, Object>> response =
                new HealthController(unreachableDatabase(), workingMail).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
        assertThat(components(response))
                .as("the failing dependency is named, and the healthy one is not dragged down with it")
                .containsEntry("database", "DOWN")
                .containsEntry("mail", "UP");
    }

    @Test
    @DisplayName("a mail server that refuses the connection is 503, and only mail is DOWN")
    void a_refused_mail_server_is_reported_down() {
        ResponseEntity<Map<String, Object>> response =
                new HealthController(workingDatabase, unreachableMail()).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
        assertThat(components(response))
                .as("mail is checked independently of the database, not inferred from it")
                .containsEntry("mail", "DOWN")
                .containsEntry("database", "UP");
    }

    @Test
    @DisplayName("both gone is still one answer, with both named")
    void both_unreachable_are_both_reported_down() {
        ResponseEntity<Map<String, Object>> response =
                new HealthController(unreachableDatabase(), unreachableMail()).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(components(response)).containsEntry("database", "DOWN").containsEntry("mail", "DOWN");
    }

    /**
     * The reason this endpoint is hand-written instead of Actuator's: it is reachable without
     * authentication, and a health payload is the easiest place in a system to give away where the
     * database lives. A failure has to say <em>which</em> dependency is down and nothing else.
     */
    @Test
    @DisplayName("a failure names the component and gives away nothing else")
    void a_failure_leaks_no_internals() {
        ResponseEntity<Map<String, Object>> response =
                new HealthController(unreachableDatabase(), unreachableMail()).health();

        assertThat(response.getBody()).isNotNull();
        String rendered = response.getBody().toString();

        assertThat(rendered)
                .as("the body a stranger receives: %s", rendered)
                .doesNotContain("jdbc:")
                .doesNotContain("postgresql")
                .doesNotContain("Connection refused")
                .doesNotContain("SQLException")
                .doesNotContain("MessagingException")
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost");
        assertThat(response.getBody().keySet())
                .as("two keys, and no third that arrived by accident")
                .containsExactlyInAnyOrder("status", "components");
    }

    /**
     * A port nothing is listening on, taken by binding one and releasing it.
     *
     * <p>A hard-coded port would be a test that passes until the day something is listening there.
     */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reserve a closed port", e);
        }
    }

    /** A real driver against a real refusal, rather than a stub that throws what we expect. */
    private static DataSource unreachableDatabase() {
        DriverManagerDataSource dead = new DriverManagerDataSource();
        dead.setUrl("jdbc:postgresql://127.0.0.1:" + closedPort() + "/reception");
        dead.setUsername("reception");
        dead.setPassword("not-used-the-port-is-shut");
        return dead;
    }

    private static JavaMailSenderImpl unreachableMail() {
        JavaMailSenderImpl dead = new JavaMailSenderImpl();
        dead.setHost("127.0.0.1");
        dead.setPort(closedPort());
        return dead;
    }

    private static Map<String, String> components(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> components =
                (Map<String, String>) response.getBody().get("components");
        return components;
    }
}
