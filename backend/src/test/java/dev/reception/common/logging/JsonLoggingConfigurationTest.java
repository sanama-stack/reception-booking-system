package dev.reception.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.Status;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;

/**
 * The JSON appender is inactive under the {@code test} profile, so the suite would otherwise never
 * load it — and a broken encoder configuration would surface as a container that refuses to start
 * rather than as a red test. This loads the real appender definition.
 */
class JsonLoggingConfigurationTest {

    @Test
    void the_json_appender_definition_configures_cleanly() throws JoranException {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);

        // A bare <included> file needs a <configuration> wrapper to be a document in its own right.
        configurator.doConfigure(new java.io.ByteArrayInputStream(
                """
                <configuration>
                  <include resource="logback-json.xml"/>
                  <root level="INFO"><appender-ref ref="JSON"/></root>
                </configuration>
                """
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(context.getStatusManager().getCopyOfStatusList())
                .as("logback reported no configuration errors")
                .noneMatch(status -> status.getLevel() == Status.ERROR);

        var appender = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("JSON");
        assertThat(appender).isInstanceOf(ConsoleAppender.class);
        assertThat(((ConsoleAppender<?>) appender).getEncoder()).isInstanceOf(LogstashEncoder.class);
    }
}
