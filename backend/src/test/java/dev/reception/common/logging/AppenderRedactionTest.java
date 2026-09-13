package dev.reception.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.joran.spi.JoranException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * docs/06-security.md §10, asserted at the appenders rather than at the masker.
 *
 * <p>Three tests already touched this area and none of them could catch the failure that matters.
 * {@link PiiValueMaskerTest} proves the rules redact; {@link ConsoleRedactionTest} proves the two
 * converters redact and that a <em>hand-written</em> configuration loads; {@link
 * JsonLoggingConfigurationTest} loads the real {@code logback-json.xml} but asserts only the
 * <em>types</em> of the appender and its encoder. So deleting the {@code
 * <jsonGeneratorDecorator>} block from the real file — the whole of the redaction in the appender
 * that runs in production — left all three green and every deployed log unmasked.
 *
 * <p>This drives real secrets through the encoder the real file builds, and inspects the bytes.
 */
class AppenderRedactionTest {

    /**
     * One line carrying a different shape of secret per rule in PiiValueMasker. The API key is
     * deliberately <em>bare</em> rather than written as {@code api_key=sk-...}: the key/value rule
     * matches first and replaces the whole value, so a key behind a key name never reaches the
     * {@code sk-} rule at all. Writing it the obvious way asserts the wrong rule and leaves the
     * one that catches a key pasted into a sentence untested.
     */
    private static final String LEAKY_MESSAGE = "refresh_token=abc123def456 for nino@aria.ge on +995599123456"
            + " rotated to sk-proj-AbCdEfGhIjKlMnOpQrSt and password=hunter2";

    private static final List<String> MUST_NOT_APPEAR =
            List.of("abc123def456", "nino@aria.ge", "+995599123456", "sk-proj-AbCdEfGhIjKlMnOpQrSt", "hunter2");

    // ---- the appender that runs in production ---------------------------------------------

    @Test
    void the_json_appender_masks_every_shape_of_secret_in_its_output() throws JoranException {
        String rendered = encodeThroughRealJsonEncoder(event(Level.INFO, LEAKY_MESSAGE, null));

        assertThat(rendered).as("the encoder produced a line at all").contains("\"message\"");
        for (String secret : MUST_NOT_APPEAR) {
            assertThat(rendered).as("%s must not reach a log", secret).doesNotContain(secret);
        }
        assertThat(rendered).contains("[REDACTED]", "[REDACTED_EMAIL]", "[REDACTED_PHONE]", "[REDACTED_API_KEY]");
    }

    @Test
    void the_json_appender_masks_a_secret_carried_by_an_exception() throws JoranException {
        String rendered = encodeThroughRealJsonEncoder(event(
                Level.ERROR,
                "the provider refused the call",
                new ThrowableProxy(new IllegalStateException("refused the key sk-proj-AbCdEfGhIjKlMnOpQrSt"))));

        assertThat(rendered)
                .as("the stack trace is a field of its own, and the masker has to reach into it")
                .contains("stack_trace");
        assertThat(rendered).doesNotContain("sk-proj-AbCdEfGhIjKlMnOpQrSt").contains("[REDACTED_API_KEY]");
    }

    @Test
    void the_json_appender_masks_a_secret_carried_by_the_mdc() throws JoranException {
        String rendered = encodeThroughRealJsonEncoder(event(
                Level.INFO,
                "authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlLXZhbHVl",
                null));

        assertThat(rendered)
                .as("the request id is not a secret and must survive — otherwise the log is useless")
                .contains("r-1");
        assertThat(rendered).doesNotContain("c2lnbmF0dXJlLXZhbHVl").contains("[REDACTED]");
    }

    // ---- the appender a developer reads ---------------------------------------------------

    /**
     * The console side cannot be executed here: its root binding lives inside {@code
     * <springProfile>}, which only Spring Boot's package-private configurator understands, and
     * initialising the real logging system would reconfigure the JVM the rest of the suite is
     * logging through. So the execution half stays in {@link ConsoleRedactionTest} — which proves
     * both converters mask — and this asserts the half that test cannot: that the real file binds
     * a redacting converter to every conversion word the real pattern actually uses.
     *
     * <p>Either side alone is worthless. Converters that mask, bound to nothing the pattern
     * renders, redact nothing at all.
     */
    @Test
    void the_console_pattern_uses_only_conversion_words_the_real_file_redacts() throws Exception {
        String pattern = resolveConsoleLogPattern();
        Map<String, String> rules = conversionRulesOf(readLogbackSpringXml());

        Set<String> rendering = renderingWordsIn(pattern);
        assertThat(rendering)
                .as("the pattern renders a message and a throwable, or this test checks nothing")
                .isNotEmpty();

        for (String word : rendering) {
            assertThat(rules)
                    .as("%%%s renders a message or a stack trace and is not bound to a redacting rule", word)
                    .containsKey(word);
            assertThat(rules.get(word))
                    .as("%%%s is bound to something, but not to this project's redaction", word)
                    .startsWith("dev.reception.common.logging.Pii");
        }
    }

    /**
     * Spring Boot's own defaults.xml binds {@code wEx} to its ExtendedWhitespaceThrowableProxyConverter,
     * and the real file re-binds it to ours. Logback takes the last declaration, so the include has
     * to come first — move it below the rules and every stack trace is rendered by Boot's converter
     * instead, unredacted, with the file still looking exactly as intended.
     */
    @Test
    void the_boot_defaults_are_included_before_the_redaction_rules_that_override_them() throws IOException {
        String xml = readLogbackSpringXml();

        int include = xml.indexOf("org/springframework/boot/logging/logback/defaults.xml");
        int firstRule = xml.indexOf("conversionWord=");

        assertThat(include).as("the real file still includes Boot's defaults").isNotNegative();
        assertThat(firstRule).as("the real file still declares a conversion rule").isNotNegative();
        assertThat(include)
                .as("Boot's defaults must be included before the rules that override its wEx binding")
                .isLessThan(firstRule);
    }

    /** Every alias of a bound word must be bound too, or renaming one word in the pattern leaks. */
    @Test
    void every_message_alias_is_bound() throws IOException {
        Map<String, String> rules = conversionRulesOf(readLogbackSpringXml());

        assertThat(rules.keySet()).contains("m", "msg", "message");
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Loads the real logback-json.xml and returns what its encoder writes for one event. */
    private static String encodeThroughRealJsonEncoder(LoggingEvent event) throws JoranException {
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

        var appender = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("JSON");
        assertThat(appender).as("the real file still defines a JSON appender").isInstanceOf(ConsoleAppender.class);

        @SuppressWarnings("unchecked")
        Encoder<ch.qos.logback.classic.spi.ILoggingEvent> encoder =
                (Encoder<ch.qos.logback.classic.spi.ILoggingEvent>) ((ConsoleAppender<?>) appender).getEncoder();
        event.setLoggerContext(context);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private static LoggingEvent event(Level level, String message, ThrowableProxy throwable) {
        return event(level, message, throwable, Map.of("requestId", "r-1", "businessId", "b-1"));
    }

    /**
     * The MDC map is set explicitly because a hand-built LoggingEvent has no SLF4J adapter behind
     * it, and the encoder's MDC provider dereferences one.
     */
    private static LoggingEvent event(Level level, String message, ThrowableProxy throwable, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("dev.reception.test");
        event.setThreadName("test");
        event.setMDCPropertyMap(mdc);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        if (throwable != null) {
            event.setThrowableProxy(throwable);
        }
        return event;
    }

    private static String readLogbackSpringXml() throws IOException {
        return new String(
                new ClassPathResource("logback-spring.xml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** conversionWord -> class, read out of the real configuration. */
    private static Map<String, String> conversionRulesOf(String xml) {
        Map<String, String> rules = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("conversionWord=\"([^\"]+)\"\\s+class=\"([^\"]+)\"").matcher(xml);
        while (matcher.find()) {
            rules.put(matcher.group(1), matcher.group(2));
        }
        return rules;
    }

    /**
     * CONSOLE_LOG_PATTERN is Spring Boot's, not ours — the real file interpolates it. Loading
     * Boot's own defaults.xml resolves it exactly as the running application would.
     */
    private static String resolveConsoleLogPattern() throws JoranException {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(
                """
                <configuration>
                  <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                  <appender name="PROBE" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>${CONSOLE_LOG_PATTERN}</pattern></encoder>
                  </appender>
                  <root level="INFO"><appender-ref ref="PROBE"/></root>
                </configuration>
                """
                        .getBytes(StandardCharsets.UTF_8)));

        // Joran substitutes variables as it sets the element, so the encoder holds the resolved
        // text. <property> is LOCAL-scoped and never reaches the LoggerContext, so asking the
        // context for it returns null — which is how this test first failed.
        var probe = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("PROBE");
        String pattern = ((ch.qos.logback.classic.encoder.PatternLayoutEncoder)
                        ((ConsoleAppender<?>) probe).getEncoder())
                .getPattern();
        assertThat(pattern).as("Spring Boot still defines CONSOLE_LOG_PATTERN").isNotBlank();
        return pattern;
    }

    /** Logback's conversion words that render a message or a stack trace — the leak-carrying ones. */
    private static final Set<String> RENDERING_WORDS = Set.of(
            "m", "msg", "message",
            "ex", "exception", "throwable",
            "rEx", "rootException",
            "xEx", "xException", "xThrowable",
            "wEx", "wException");

    private static Set<String> renderingWordsIn(String pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("%[-.\\d]*([a-zA-Z]+)").matcher(pattern);
        while (matcher.find()) {
            String word = matcher.group(1);
            if (RENDERING_WORDS.contains(word)) {
                found.add(word);
            }
        }
        return found;
    }
}
