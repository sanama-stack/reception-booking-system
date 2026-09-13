package dev.reception.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.reception.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slow-query logging, which docs/09-phase-plan.md asks for in {@code local} <em>"to catch a missing
 * index during the demo build"</em>.
 *
 * <p>Nothing configured it and nothing would have said so. A setting like this one is the easiest
 * kind of claim to make in a document and never notice is untrue, because its whole output is
 * absence: no line is exactly what a working threshold and a missing setting both look like.
 *
 * <p>So this asserts the mechanism and the wiring separately. {@link #a_slow_query_is_reported()}
 * lowers the threshold to a millisecond and proves Hibernate really emits on this logger — the half
 * that would break silently on a Hibernate upgrade that renamed the property. {@link
 * #the_local_profile_turns_it_on()} reads the shipped {@code application-local.yml}, because a
 * mechanism nothing enables is not a feature.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.log_slow_query=1")
@Transactional
class SlowQueryLoggingTest extends IntegrationTest {

    /** Hibernate's own, and not a name we get to choose. */
    private static final String SLOW_QUERY_LOGGER = "org.hibernate.SQL_SLOW";

    private static final String PROPERTY = "spring.jpa.properties.hibernate.log_slow_query";

    /** A value that would be a customer's if this were their row. */
    private static final String BOUND_VALUE = "+995599123456";

    @Autowired
    private EntityManager entityManager;

    private ListAppender<ILoggingEvent> captured;
    private ch.qos.logback.classic.Logger slowQueryLogger;
    private Level restoreLevel;

    @BeforeEach
    void capture() {
        slowQueryLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SLOW_QUERY_LOGGER);
        restoreLevel = slowQueryLogger.getLevel();
        slowQueryLogger.setLevel(Level.INFO);

        captured = new ListAppender<>();
        captured.setContext(slowQueryLogger.getLoggerContext());
        captured.start();
        slowQueryLogger.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        slowQueryLogger.detachAppender(captured);
        captured.stop();
        slowQueryLogger.setLevel(restoreLevel);
    }

    @Test
    @DisplayName("a query over the threshold is reported, with the statement that caused it")
    void a_slow_query_is_reported() {
        runQueryBoundTo(BOUND_VALUE);

        assertThat(captured.list)
                .as("nothing reached %s. A threshold of one millisecond that reports nothing means "
                        + "Hibernate is no longer emitting here — most likely the property was renamed.",
                        SLOW_QUERY_LOGGER)
                .isNotEmpty();
        assertThat(captured.list.get(0).getFormattedMessage())
                .as("a line that does not name the statement cannot find the missing index")
                .contains("businesses");
    }

    /**
     * The reason this is safe to leave on in {@code local}.
     *
     * <p>It is the only setting in this repository that prints SQL by default, and a statement is
     * one interpolation away from being the row. Hibernate logs the prepared statement with its
     * placeholders; if a future version started inlining bound values, every developer's console
     * would quietly become a PII sink (docs/06-security.md §10).
     */
    @Test
    @DisplayName("the reported statement carries placeholders, never the values bound into them")
    void a_slow_query_line_carries_no_bound_values() {
        runQueryBoundTo(BOUND_VALUE);

        assertThat(captured.list).isNotEmpty();
        assertThat(captured.list.get(0).getFormattedMessage())
                .as("the line a developer's console would show")
                .doesNotContain(BOUND_VALUE);
    }

    @Test
    @DisplayName("the local profile is the one that turns it on")
    void the_local_profile_turns_it_on() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-local.yml"));
        Properties local = yaml.getObject();

        assertThat(local).isNotNull();
        assertThat(local.getProperty(PROPERTY))
                .as("%s is what the phase plan asks for in local, and this file is where it lives", PROPERTY)
                .isNotNull()
                .satisfies(value -> assertThat(Integer.parseInt(value))
                        .as("""
                            Zero or negative switches it off. So, in every way that matters, does a \
                            threshold above a second: it would still read as configured while \
                            reporting nothing a demo build could ever trip.""")
                        .isBetween(1, 1000));
    }

    /**
     * A query that is slow on purpose, with a value bound into it.
     *
     * <p><strong>The sleep is not decoration.</strong> The first version of this test lowered the
     * threshold to a millisecond and let an ordinary count be slow enough — which it is, the first
     * time, while the statement is being prepared and the connection warmed. Whichever test ran
     * second then found nothing and failed, and it was a different test on a different run. A
     * threshold test needs a query that is reliably over the line, not one that usually is.
     *
     * <p>The sleep sits in a scalar subquery rather than in the {@code where} clause so it runs
     * whatever the filter matches: against an empty table a predicate may never be evaluated at all.
     *
     * <p>Issued through Hibernate because this logger only sees statements Hibernate runs — the same
     * query through {@code JdbcTemplate} would report nothing and read like a broken threshold.
     */
    private void runQueryBoundTo(String value) {
        entityManager
                .createNativeQuery(
                        "select count(*) + (select 0 from (select pg_sleep(0.05)) t) "
                                + "from businesses where name = :name",
                        Long.class)
                .setParameter("name", value)
                .getSingleResult();
    }
}
