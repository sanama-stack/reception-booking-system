package dev.reception.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/**
 * The refusal in docs/06-security.md §9, shown to fire.
 *
 * <p>{@link SecretsGuard} is {@code @Profile("prod")}, so no other test in this suite ever
 * constructs it — which is how it reached phase 11 as code that <em>reads</em> correctly rather
 * than a control that has been shown to work. docs/deployment.md §5 says so in as many words and
 * tells a first deployment not to trust it. This is the test that sentence was waiting for.
 *
 * <p>Every case starts a context with the real property keys rather than calling {@code
 * afterPropertiesSet()} directly, because half of what can break here is wiring: a guard that is
 * not registered under {@code prod}, or one reading a key nothing sets, refuses nothing at all.
 * Both directions of a key rename fail closed — an unresolved key takes the empty default and is
 * reported as "not set" — so the danger is not drift in the key but drift in the <em>default</em>,
 * which is what {@link #every_local_default_still_carries_the_prefix_the_guard_matches()} pins.
 */
class SecretsGuardTest {

    private static final String JWT_KEY = "app.security.jwt-secret";
    private static final String MANAGE_KEY = "app.security.manage-link-secret";
    private static final String DB_KEY = "spring.datasource.password";

    /** Long enough to clear the 32-character floor, and obviously not a credential. */
    private static final String REAL = "deployment-would-put-a-real-value-here-0123456789";

    /** The literals application.yml falls back to. Pinned against the file below. */
    private static final String JWT_DEFAULT = "local-dev-only-jwt-secret-change-me-please";
    private static final String MANAGE_DEFAULT = "local-dev-only-manage-link-secret-change-me";
    private static final String DB_DEFAULT = "local-dev-only-postgres-password";

    private final ApplicationContextRunner prod = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=prod")
            .withUserConfiguration(SecretsGuard.class);

    // ---- wiring ---------------------------------------------------------------------------

    @Test
    void the_guard_is_absent_outside_the_prod_profile() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecretsGuard.class)
                .withPropertyValues(JWT_KEY + "=" + JWT_DEFAULT)
                .run(context -> {
                    assertThat(context)
                            .as("a local default must not stop a developer's context")
                            .hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecretsGuard.class);
                });
    }

    @Test
    void the_guard_is_registered_under_prod() {
        prod.withPropertyValues(good()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SecretsGuard.class);
        });
    }

    @Test
    void the_prod_context_starts_when_no_secret_holds_a_default() {
        prod.withPropertyValues(good()).run(context -> assertThat(context).hasNotFailed());
    }

    // ---- the refusal ----------------------------------------------------------------------

    @Test
    void the_prod_context_refuses_to_start_while_the_jwt_secret_holds_its_local_default() {
        prod.withPropertyValues(good(JWT_KEY, JWT_DEFAULT))
                .run(context -> assertRefused(context, "JWT_SECRET", "local development default"));
    }

    @Test
    void the_prod_context_refuses_to_start_while_the_manage_link_secret_holds_its_local_default() {
        prod.withPropertyValues(good(MANAGE_KEY, MANAGE_DEFAULT))
                .run(context -> assertRefused(context, "MANAGE_LINK_SECRET", "local development default"));
    }

    @Test
    void the_prod_context_refuses_to_start_while_the_database_password_holds_its_local_default() {
        prod.withPropertyValues(good(DB_KEY, DB_DEFAULT))
                .run(context -> assertRefused(context, "DB_PASSWORD", "local development default"));
    }

    @Test
    void a_secret_that_is_not_set_at_all_is_refused() {
        prod.withPropertyValues(good(JWT_KEY, "")).run(context -> assertRefused(context, "JWT_SECRET", "is not set"));
    }

    @Test
    void a_signing_secret_shorter_than_thirty_two_characters_is_refused() {
        prod.withPropertyValues(good(JWT_KEY, "short-but-not-a-local-default"))
                .run(context -> assertRefused(context, "JWT_SECRET", "shorter than 32"));
    }

    /**
     * Deliberately asymmetric: the length floor exists because the two signing secrets are HMAC
     * keys, and a database password's strength is the database's business, not this guard's.
     * Pinned so that adding a floor there becomes a decision rather than a drive-by.
     */
    @Test
    void the_database_password_is_not_length_checked() {
        prod.withPropertyValues(good(DB_KEY, "short")).run(context -> assertThat(context).hasNotFailed());
    }

    /** A deployer fixing one variable per restart is a loop worth not building. */
    @Test
    void every_offending_secret_is_named_in_one_message() {
        prod.withPropertyValues(JWT_KEY + "=" + JWT_DEFAULT, MANAGE_KEY + "=", DB_KEY + "=" + DB_DEFAULT)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context))
                            .contains("JWT_SECRET")
                            .contains("MANAGE_LINK_SECRET")
                            .contains("DB_PASSWORD");
                });
    }

    /** The message reaches the log, so the value must not be in it. */
    @Test
    void the_refusal_names_the_variable_and_never_the_value() {
        prod.withPropertyValues(
                        JWT_KEY + "=" + JWT_DEFAULT,
                        MANAGE_KEY + "=" + MANAGE_DEFAULT,
                        DB_KEY + "=" + DB_DEFAULT)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context))
                            .contains("JWT_SECRET")
                            .doesNotContain(JWT_DEFAULT)
                            .doesNotContain(MANAGE_DEFAULT)
                            .doesNotContain(DB_DEFAULT)
                            .doesNotContain(REAL);
                });
    }

    // ---- the default the guard matches ----------------------------------------------------

    /**
     * The guard recognises a default by the {@code local-dev-only-} prefix, so a default that
     * loses the prefix is a default the guard stops catching — silently, with every test above
     * still green because they carry their own literals. This reads the real file.
     */
    @Test
    void every_local_default_still_carries_the_prefix_the_guard_matches() throws IOException {
        String yaml = new String(
                new ClassPathResource("application.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(defaultOf(yaml, "JWT_SECRET")).isEqualTo(JWT_DEFAULT).startsWith("local-dev-only-");
        assertThat(defaultOf(yaml, "MANAGE_LINK_SECRET")).isEqualTo(MANAGE_DEFAULT).startsWith("local-dev-only-");
        assertThat(defaultOf(yaml, "DB_PASSWORD")).isEqualTo(DB_DEFAULT).startsWith("local-dev-only-");
    }

    private static String defaultOf(String yaml, String variable) {
        Matcher matcher = Pattern.compile("\\$\\{" + Pattern.quote(variable) + ":([^}]*)}").matcher(yaml);
        assertThat(matcher.find()).as("application.yml still reads %s", variable).isTrue();
        return matcher.group(1);
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Every secret sound, except the one named. */
    private static String[] good(String spoiledKey, String spoiledValue) {
        return new String[] {
            JWT_KEY + "=" + (JWT_KEY.equals(spoiledKey) ? spoiledValue : REAL),
            MANAGE_KEY + "=" + (MANAGE_KEY.equals(spoiledKey) ? spoiledValue : REAL),
            DB_KEY + "=" + (DB_KEY.equals(spoiledKey) ? spoiledValue : REAL),
        };
    }

    private static String[] good() {
        return good("", "");
    }

    private static void assertRefused(AssertableApplicationContext context, String variable, String because) {
        assertThat(context).as("the prod context must refuse to start").hasFailed();
        assertThat(rootCauseMessage(context))
                .contains("Refusing to start in the prod profile")
                .contains(variable)
                .contains(because);
    }

    private static String rootCauseMessage(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
