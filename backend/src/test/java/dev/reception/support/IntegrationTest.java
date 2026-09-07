package dev.reception.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test.
 *
 * <p>Real PostgreSQL 16, never H2: the schema depends on {@code btree_gist} exclusion constraints,
 * partial unique indexes, {@code citext} and {@code tstzrange}, and H2 supports none of them.
 * Testing against H2 would test a different system than the one shipped
 * (docs/08-testing-strategy.md §3).
 *
 * <p>Real Mailpit too, so {@code /api/health} is exercised against an actual SMTP transport rather
 * than a mock that can only ever say yes.
 *
 * <p>Both containers are static and therefore shared by every test in the suite; the JVM reaps them
 * on exit through Testcontainers' Ryuk. Per-test isolation is transactional rollback, not a fresh
 * database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
public abstract class IntegrationTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("reception")
                    .withUsername("reception")
                    .withPassword("test-only-password");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.21"))
                    .withExposedPorts(1025, 8025)
                    .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static {
        POSTGRES.start();
        MAILPIT.start();

        System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
        System.setProperty("spring.datasource.username", POSTGRES.getUsername());
        System.setProperty("spring.datasource.password", POSTGRES.getPassword());
        System.setProperty("spring.mail.host", MAILPIT.getHost());
        System.setProperty("spring.mail.port", String.valueOf(MAILPIT.getMappedPort(1025)));
    }

    /** Base URL of Mailpit's HTTP API, for the notification assertions that arrive in phase 07. */
    protected static String mailpitApiUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }
}
