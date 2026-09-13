package dev.reception.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantContextFilter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * docs/06-security.md §10's first sentence: <em>structured JSON logs with a request id and, for
 * tenant operations, {@code business_id}</em>.
 *
 * <p>Both halves existed and neither was tested end to end. {@link RequestIdFilter} was referenced
 * by exactly one test, and that one was about error responses; {@code JsonLoggingConfigurationTest}
 * asserts the appender's <em>type</em>; {@code AppenderRedactionTest} proves a hand-built MDC
 * survives the encoder. Nothing connected a real request to a real log line — so deleting {@code
 * MDC.put} from either filter left a suite that could not tell, and a production log with no way
 * back from a customer's report to the request that caused it.
 *
 * <p>This drives real HTTP at the running application and reads the MDC off the events the
 * application itself logged, then encodes one through the real {@code logback-json.xml} to prove
 * the keys reach the output rather than merely the context. The two halves are separate failures:
 * an MDC nothing emits is as useless as an emitter with nothing in the MDC.
 */
class RequestLoggingTest extends IntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private final ObjectMapper json = new ObjectMapper();

    private ListAppender<ILoggingEvent> captured;
    private ch.qos.logback.classic.Logger applicationLogger;
    private Level restoreLevel;

    /**
     * Captures what the application logs, at DEBUG.
     *
     * <p>DEBUG because the line a rejected request produces is {@code log.debug} in {@code
     * GlobalExceptionHandler}. The level is restored afterwards: a test that leaves the application
     * logger at DEBUG changes what every later test in the JVM emits.
     */
    @BeforeEach
    void captureApplicationLogs() {
        databaseCleaner.clean();

        applicationLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dev.reception");
        restoreLevel = applicationLogger.getLevel();
        applicationLogger.setLevel(Level.DEBUG);

        captured = new ListAppender<>();
        captured.setContext(applicationLogger.getLoggerContext());
        captured.start();
        applicationLogger.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        applicationLogger.detachAppender(captured);
        captured.stop();
        applicationLogger.setLevel(restoreLevel);
    }

    @Test
    @DisplayName("a tenant request puts its id and its business on the log line it produces")
    void an_authenticated_request_carries_both_keys() {
        AuthTestClient client = new AuthTestClient(rest, port);
        client.register("nino@aria.test", PASSWORD, "Salon Aria");
        String businessId = businessOfCurrentSession(client).path("id").asText();

        // A 404 rather than a success, because the rejected path is the one that logs — and it is
        // also the path somebody actually traces a log line back from.
        ResponseEntity<String> response = client.get("/appointments/" + UUID.randomUUID());
        String requestId = requestIdHeaderOf(response);

        ILoggingEvent event = theEventLoggedFor(requestId);
        assertThat(event.getMDCPropertyMap())
                .as("the log line the customer's request produced")
                .containsEntry(RequestIdFilter.MDC_KEY, requestId)
                .containsEntry(TenantContextFilter.MDC_KEY, businessId);
    }

    @Test
    @DisplayName("an anonymous request is still traceable, and invents no tenant")
    void an_anonymous_request_carries_an_id_and_no_business() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/api/public/appointments/lookup",
                HttpMethod.POST,
                new HttpEntity<>("{\"confirmationCode\":\"NOSUCH\",\"phone\":\"+995599123456\"}", headers),
                String.class);

        String requestId = requestIdHeaderOf(response);
        ILoggingEvent event = theEventLoggedFor(requestId);

        assertThat(event.getMDCPropertyMap())
                .as("a request that resolved no tenant must not carry one")
                .containsKey(RequestIdFilter.MDC_KEY)
                .doesNotContainKey(TenantContextFilter.MDC_KEY);
    }

    /**
     * The other tenant filter, and the one that matters most in a log.
     *
     * <p>An authenticated request derives its business from the token; a public one derives it from
     * the slug in the path, in a different filter, by a different line of code. Public traffic is
     * where abuse arrives, so a public log line that cannot be attributed to a business is the one
     * that costs somebody an afternoon.
     */
    @Test
    @DisplayName("a public request naming a business carries that business on its log line")
    void a_public_request_carries_the_business_its_path_names() {
        AuthTestClient client = new AuthTestClient(rest, port);
        client.register("nino@aria.test", PASSWORD, "Salon Aria");
        JsonNode business = businessOfCurrentSession(client);
        client.forgetCookies();

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/api/public/businesses/" + business.path("slug").asText()
                        + "/availability?serviceId=" + UUID.randomUUID() + "&from=2026-09-14&to=2026-09-15",
                HttpMethod.GET,
                null,
                String.class);

        ILoggingEvent event = theEventLoggedFor(requestIdHeaderOf(response));
        assertThat(event.getMDCPropertyMap())
                .as("resolved from the slug, with nobody authenticated")
                .containsEntry(TenantContextFilter.MDC_KEY, business.path("id").asText());
    }

    @Test
    @DisplayName("an id supplied by the proxy is honoured, so a trace crosses the hop")
    void a_well_formed_supplied_id_is_kept() {
        String supplied = "edge-7f3a2b91";

        ResponseEntity<String> response = getHealthWith(supplied);

        assertThat(requestIdHeaderOf(response))
                .as("the whole point of honouring the header is that the id does not change here")
                .isEqualTo(supplied);
    }

    /**
     * The reason the header is validated rather than trusted.
     *
     * <p>An id is written verbatim into every log line the request produces. A caller who can put
     * arbitrary text there can forge whatever a log reader — or whatever parses the log — takes for
     * a field of its own.
     */
    @Test
    @DisplayName("an id that is not one is replaced, and its text reaches no log line")
    void a_forged_supplied_id_is_replaced() {
        String forged = "x\" level=\"ERROR\" fake=\"everything is fine";

        ResponseEntity<String> response = getHealthWith(forged);
        String actual = requestIdHeaderOf(response);

        assertThat(actual).isNotEqualTo(forged);
        assertThat(UUID.fromString(actual)).as("replaced with a fresh id, not merely trimmed").isNotNull();
        assertThat(captured.list)
                .as("no log line may carry the text a stranger sent")
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap().getOrDefault(RequestIdFilter.MDC_KEY, ""))
                        .doesNotContain("level=")
                        .doesNotContain("fake="));
    }

    @Test
    @DisplayName("an id longer than the field allows is replaced too")
    void an_oversized_supplied_id_is_replaced() {
        String oversized = "a".repeat(65);

        String actual = requestIdHeaderOf(getHealthWith(oversized));

        assertThat(actual).isNotEqualTo(oversized);
        assertThat(actual).hasSize(36);
    }

    /**
     * The other half. An MDC nothing emits is not a log.
     *
     * <p>Both keys are declared one line apart in {@code logback-json.xml}, which is exactly the
     * shape that lets one of them be deleted without anybody noticing — the appender still works,
     * the lines still arrive, and only the field somebody needed a month later is gone.
     */
    @Test
    @DisplayName("the real JSON encoder emits both keys as fields of their own")
    void the_json_encoder_emits_the_request_id_and_the_business_id() throws Exception {
        String rendered = encodeThroughRealJsonEncoder(Map.of(
                RequestIdFilter.MDC_KEY,
                "edge-7f3a2b91",
                TenantContextFilter.MDC_KEY,
                "3f1c7a58-0000-7000-8000-000000000000"));

        JsonNode line = json.readTree(rendered);
        assertThat(line.path(RequestIdFilter.MDC_KEY).asText())
                .as("rendered line: %s", rendered)
                .isEqualTo("edge-7f3a2b91");
        assertThat(line.path(TenantContextFilter.MDC_KEY).asText())
                .isEqualTo("3f1c7a58-0000-7000-8000-000000000000");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private ResponseEntity<String> getHealthWith(String suppliedRequestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestIdFilter.HEADER, suppliedRequestId);
        return rest.exchange(
                "http://localhost:" + port + "/api/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String requestIdHeaderOf(ResponseEntity<String> response) {
        String header = response.getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(header)
                .as("every response carries the id, which is how a customer's report reaches a log line")
                .isNotBlank();
        return header;
    }

    /**
     * The captured event for one request — and the assertion that there is one.
     *
     * <p>Without it every MDC assertion in this class is vacuous the day the application stops
     * logging on that path, because an empty list satisfies nothing and fails nothing.
     */
    private ILoggingEvent theEventLoggedFor(String requestId) {
        Optional<ILoggingEvent> event = captured.list.stream()
                .filter(candidate -> requestId.equals(candidate.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY)))
                .findFirst();

        assertThat(event)
                .as(
                        "no log line carried request id %s. Either the request produced no log at all — in "
                                + "which case this test is asserting nothing — or the id never reached the MDC, "
                                + "which is the defect itself. Captured %d events.",
                        requestId, captured.list.size())
                .isPresent();
        return event.get();
    }

    /** The encoder the shipped configuration builds, as {@code AppenderRedactionTest} builds it. */
    private static String encodeThroughRealJsonEncoder(Map<String, String> mdc) throws JoranException {
        LoggerContext context = new LoggerContext();
        context.start();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(
                """
                <configuration>
                  <include resource="logback-json.xml"/>
                  <root level="INFO"><appender-ref ref="JSON"/></root>
                </configuration>
                """
                        .getBytes(StandardCharsets.UTF_8)));

        var appender = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
                .getAppender("JSON");
        assertThat(appender).as("the real file still defines a JSON appender").isInstanceOf(ConsoleAppender.class);

        @SuppressWarnings("unchecked")
        Encoder<ILoggingEvent> encoder = (Encoder<ILoggingEvent>) ((ConsoleAppender<?>) appender).getEncoder();

        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setLoggerName("dev.reception.test");
        event.setThreadName("test");
        // Explicitly: a hand-built event has no SLF4J adapter behind it and the MDC provider
        // dereferences one (T85).
        event.setMDCPropertyMap(mdc);
        event.setMessage("a turn completed");
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private JsonNode businessOfCurrentSession(AuthTestClient client) {
        try {
            JsonNode business = json.readTree(client.get("/auth/me").getBody()).path("business");
            assertThat(business.path("id").asText())
                    .as("/auth/me still reports the business id")
                    .isNotBlank();
            return business;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the session's business", e);
        }
    }
}
