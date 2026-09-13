package dev.reception.ai.application;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reception.ai.support.ScriptedChatModel;
import dev.reception.appointments.BookingScenario;
import dev.reception.common.error.ApiException;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import dev.reception.tenancy.TenantAdoption;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a model call leaves behind in the log, and what it must not.
 *
 * <p>docs/06-security.md §10: <em>log entries for AI turns record token counts, latency, tool names
 * and outcomes — not message content.</em> Before this, {@code OpenAiChatModel} and the
 * orchestration loop between them contained <strong>no log statement at all</strong> about a model
 * call. Cost was computed by {@link CostTracker} for the daily cap and then thrown away; an owner
 * asking why their bill moved, or why a turn took eleven seconds, had nothing to read.
 *
 * <p>Two failures are possible and this class separates them. The line can be missing or wrong —
 * the wrong token counts, a cost that is not the cost the cap was charged. Or the line can be
 * <em>too good</em>: the easiest way to log a useful AI trace is to log the conversation, and the
 * conversation is the customer's name, their phone number and what they wanted. So every test here
 * runs a real turn through the loop carrying a distinctive phrase, and then asserts it appears in
 * no log line anywhere — not in a message, not in an argument.
 */
class AiCallLoggingTest extends IntegrationTest {

    /** Words a customer said. If any of these reach a log, §10 is broken. */
    private static final String CUSTOMER_WORDS = "my name is Nino Kapanadze and my number is +995599123456";

    private static final String ASSISTANT_WORDS = "Of course Nino, I have Tuesday at two.";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ScriptedChatModel model;

    @Autowired
    private TenantAdoption tenants;

    @Autowired
    private CostTracker costs;

    @Autowired
    private AiProperties ai;

    private final ObjectMapper json = new ObjectMapper();

    private ListAppender<ILoggingEvent> captured;
    private ch.qos.logback.classic.Logger applicationLogger;
    private Level restoreLevel;
    private BookingScenario aria;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        model.reset();
        aria = BookingScenario.open(rest, port, clock);
        tenants.adopt(UUID.fromString(jdbc.queryForObject("select id::text from businesses", String.class)));

        applicationLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dev.reception");
        restoreLevel = applicationLogger.getLevel();
        applicationLogger.setLevel(Level.DEBUG);
        captured = new ListAppender<>();
        captured.setContext(applicationLogger.getLoggerContext());
        captured.start();
        applicationLogger.addAppender(captured);
    }

    @AfterEach
    void tearDown() {
        applicationLogger.detachAppender(captured);
        captured.stop();
        applicationLogger.setLevel(restoreLevel);
    }

    @Test
    @DisplayName("a completed model call records model, latency, tokens and the cost the cap was charged")
    void a_model_call_is_measured() {
        model.willSay(ASSISTANT_WORDS, 1000, 200);

        conversations.respond(startSession(), CUSTOMER_WORDS);

        String line = theLineStartingWith("Model call completed");
        assertThat(line)
                // The one identifier on the line, and the only thing joining it to the row a cost
                // question is actually about.
                .containsPattern("conversation_id=[0-9a-f-]{36}")
                .contains("model=" + ai.getModel())
                .contains("prompt_tokens=1000")
                .contains("completion_tokens=200")
                .contains("tool_calls=0")
                .containsPattern("latency_ms=\\d+");

        int expected = costs.costCentsFor(1000, 200);
        assertThat(expected).as("a turn this size must cost something, or the assertion below is empty").isPositive();
        assertThat(line)
                .as("the cost logged is the estimate the daily cap is charged, not a second opinion")
                .contains("cost_cents=" + expected);
    }

    @Test
    @DisplayName("a tool call records its name and outcome, and never its arguments")
    void a_tool_call_is_named_but_not_quoted() {
        model.willCall("get_services", "{}").willSay(ASSISTANT_WORDS, 900, 80);

        conversations.respond(startSession(), CUSTOMER_WORDS);

        assertThat(theLineStartingWith("Tool call")).contains("tool=get_services").contains("outcome=ok");
        assertNothingTheCustomerSaidWasLogged();
    }

    /**
     * The outcome half has to be able to say {@code error}, or it is a constant.
     *
     * <p>A booking for a service that does not exist fails inside the tool and comes back as an
     * error result, which is the shape the loop feeds to the model rather than an exception.
     */
    @Test
    @DisplayName("a tool that fails is logged as a failure, not merely as a call")
    void a_failing_tool_call_says_so() {
        model.willCall(
                        "create_appointment",
                        """
                        {"service_id":"%s","employee_id":"%s","starts_at":"%s",\
                        "customer_name":"Nino Kapanadze","customer_phone":"+995599123456",\
                        "customer_email":null,"note":null}"""
                                .formatted(UUID.randomUUID(), aria.employeeId, aria.at(aria.monday, 14, 0)))
                .willSay("I could not book that.", 900, 40);

        conversations.respond(startSession(), CUSTOMER_WORDS);

        assertThat(theLineStartingWith("Tool call")).contains("tool=create_appointment").contains("outcome=error");
        assertNothingTheCustomerSaidWasLogged();
    }

    @Test
    @DisplayName("a provider failure is logged with its latency and without the conversation")
    void a_failed_model_call_is_measured_too() {
        model.willFail();

        String session = startSession();
        assertThatThrownBy(() -> conversations.respond(session, CUSTOMER_WORDS)).isInstanceOf(ApiException.class);

        assertThat(theLineStartingWith("Model call failed"))
                .contains("model=" + ai.getModel())
                .containsPattern("latency_ms=\\d+");
        assertNothingTheCustomerSaidWasLogged();
    }

    /**
     * The standing claim, asserted on the ordinary path rather than only the exotic ones.
     *
     * <p>A tool call carries the customer's own words as arguments and the assistant's reply is the
     * conversation itself. Both pass through this method inches from a logger.
     */
    @Test
    @DisplayName("a whole turn leaves no trace of what was said")
    void a_turn_logs_no_content() {
        model.willCall("get_services", "{}").willSay(ASSISTANT_WORDS, 1000, 200);

        conversations.respond(startSession(), CUSTOMER_WORDS);

        assertThat(captured.list).as("a turn that logged nothing at all would pass this vacuously").isNotEmpty();
        assertNothingTheCustomerSaidWasLogged();
    }

    /**
     * {@code kv} rather than string interpolation, because a field is the point.
     *
     * <p>Rendered into the message alone, {@code cost_cents=3} is text somebody has to parse with a
     * regular expression to sum a day's spend. This asserts the encoder that ships turns them into
     * fields of the JSON object.
     */
    @Test
    @DisplayName("the structured arguments become JSON fields, not just words in the message")
    void the_metrics_are_fields() throws Exception {
        String rendered = encodeThroughRealJsonEncoder();

        JsonNode line = json.readTree(rendered);
        assertThat(line.path("cost_cents").asInt()).as("rendered: %s", rendered).isEqualTo(3);
        assertThat(line.path("latency_ms").asLong()).isEqualTo(412L);
        assertThat(line.path("model").asText()).isEqualTo("gpt-4o-mini");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private String startSession() {
        return conversations.start(Optional.empty()).sessionToken();
    }

    /** The one captured line with this prefix, and the assertion that there is one. */
    private String theLineStartingWith(String prefix) {
        List<String> matching = captured.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(prefix))
                .toList();

        assertThat(matching)
                .as("no log line began with \"%s\". Captured %d events: %s", prefix, captured.list.size(), captured.list
                        .stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList())
                .isNotEmpty();
        return matching.get(0);
    }

    private void assertNothingTheCustomerSaidWasLogged() {
        for (ILoggingEvent event : captured.list) {
            String everything = event.getFormattedMessage() + " "
                    + (event.getArgumentArray() == null ? "" : java.util.Arrays.toString(event.getArgumentArray()));
            assertThat(everything)
                    .as("a log line carried conversation content: %s", everything)
                    .doesNotContain("Nino Kapanadze")
                    .doesNotContain("+995599123456")
                    .doesNotContain("Tuesday at two")
                    .doesNotContain(CUSTOMER_WORDS);
        }
    }

    /** An event carrying the same structured arguments the loop emits, through the shipped encoder. */
    private static String encodeThroughRealJsonEncoder() throws Exception {
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
        @SuppressWarnings("unchecked")
        Encoder<ILoggingEvent> encoder = (Encoder<ILoggingEvent>) ((ConsoleAppender<?>) appender).getEncoder();

        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setLoggerName("dev.reception.test");
        event.setThreadName("test");
        event.setMDCPropertyMap(java.util.Map.of());
        event.setMessage("Model call completed: {} {} {}");
        event.setArgumentArray(
                new Object[] {kv("model", "gpt-4o-mini"), kv("latency_ms", 412L), kv("cost_cents", 3)});
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerContext(context);

        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
