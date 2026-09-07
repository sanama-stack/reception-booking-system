package dev.reception.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The console format is human-readable during development and JSON everywhere else. Only the JSON
 * encoder redacts on its own, so the console pattern needs its own rule — and this is where that
 * is checked, because a pretty console that leaks a password would be a security regression
 * bought with formatting (docs/06-security.md §10).
 */
class ConsoleRedactionTest {

    @Test
    void the_console_pattern_redacts_the_message() {
        LoggerContext context = new LoggerContext();
        PiiMessageConverter converter = new PiiMessageConverter();
        converter.setContext(context);
        converter.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLevel(Level.INFO);
        event.setMessage("connecting with password=hunter2 for nino@aria.ge on +995599123456");

        String rendered = converter.convert(event);

        assertThat(rendered)
                .doesNotContain("hunter2")
                .doesNotContain("nino@aria.ge")
                .doesNotContain("+995599123456")
                .contains("[REDACTED]", "[REDACTED_EMAIL]", "[REDACTED_PHONE]");
    }

    @Test
    void the_console_pattern_redacts_a_stack_trace() {
        LoggerContext context = new LoggerContext();
        PiiThrowableConverter converter = new PiiThrowableConverter();
        converter.setContext(context);
        converter.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLevel(Level.ERROR);
        event.setMessage("boom");
        // An exception message is a common carrier of exactly what must not be logged.
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new IllegalStateException("refused api_key=sk-proj-AbCdEfGhIjKlMnOpQrSt")));

        String rendered = converter.convert(event);

        assertThat(rendered).doesNotContain("sk-proj-AbCdEfGhIjKlMnOpQrSt").contains("[REDACTED");
    }

    @Test
    void the_logging_configuration_loads_without_warnings_or_errors() {
        LoggerContext context = new LoggerContext();
        ch.qos.logback.classic.joran.JoranConfigurator configurator =
                new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);

        assertThat(catchConfigure(configurator, context)).isNull();

        // Deprecated attributes and unreferenced appenders both surface as WARN, and a startup
        // warning nobody needs is how people learn to ignore the ones that matter. Configuring
        // from a stream rather than a file emits an unavoidable "Null ConfigurationWatchList"
        // warning of its own, so the assertion names the two conditions it is actually about.
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .noneMatch(status -> status.getLevel() >= Status.ERROR)
                .noneMatch(status -> status.getLevel() == Status.WARN
                        && (status.getMessage().contains("deprecated")
                                || status.getMessage().contains("not referenced")));
    }

    private Throwable catchConfigure(
            ch.qos.logback.classic.joran.JoranConfigurator configurator, LoggerContext context) {
        try {
            configurator.doConfigure(new ByteArrayInputStream(
                    """
                    <configuration>
                      <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                      <conversionRule conversionWord="m" class="dev.reception.common.logging.PiiMessageConverter"/>
                      <conversionRule conversionWord="wEx" class="dev.reception.common.logging.PiiThrowableConverter"/>
                      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder><pattern>${CONSOLE_LOG_PATTERN}</pattern></encoder>
                      </appender>
                      <root level="INFO"><appender-ref ref="CONSOLE"/></root>
                    </configuration>
                    """
                            .getBytes(StandardCharsets.UTF_8)));
            return null;
        } catch (Exception e) {
            return e;
        }
    }
}
