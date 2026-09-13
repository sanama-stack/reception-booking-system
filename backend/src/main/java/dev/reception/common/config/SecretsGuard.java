package dev.reception.common.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the {@code prod} profile while {@code JWT_SECRET}, {@code MANAGE_LINK_SECRET} or
 * {@code DB_PASSWORD} still holds its local default (docs/06-security.md §9).
 *
 * <p>Three of the five secrets §9 lists, and the omissions are deliberate: an empty
 * {@code MAIL_PASSWORD} is legitimate for a relay wanting no auth, and a placeholder
 * {@code OPENAI_API_KEY} is a working application rather than a broken one. Said "any secret"
 * until 2026-09-12, which was two-fifths wrong.
 *
 * <p>Local defaults are all prefixed {@code local-dev-only-} precisely so this check can be a
 * string comparison rather than a heuristic about entropy.
 */
@Component
@Profile("prod")
public class SecretsGuard implements InitializingBean {

    private static final String LOCAL_DEFAULT_PREFIX = "local-dev-only-";
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final String jwtSecret;
    private final String manageLinkSecret;
    private final String databasePassword;

    public SecretsGuard(
            @Value("${app.security.jwt-secret:}") String jwtSecret,
            @Value("${app.security.manage-link-secret:}") String manageLinkSecret,
            @Value("${spring.datasource.password:}") String databasePassword) {
        this.jwtSecret = jwtSecret;
        this.manageLinkSecret = manageLinkSecret;
        this.databasePassword = databasePassword;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> problems = new LinkedHashSet<>();
        check("JWT_SECRET", jwtSecret, true, problems);
        check("MANAGE_LINK_SECRET", manageLinkSecret, true, problems);
        check("DB_PASSWORD", databasePassword, false, problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in the prod profile: " + String.join("; ", problems));
        }
    }

    /** Never includes the value itself in the message — the message reaches the log. */
    private void check(String name, String value, boolean lengthMatters, Set<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is not set");
            return;
        }
        if (value.startsWith(LOCAL_DEFAULT_PREFIX)) {
            problems.add(name + " still holds its local development default");
            return;
        }
        if (lengthMatters && value.length() < MINIMUM_SECRET_LENGTH) {
            problems.add(name + " is shorter than " + MINIMUM_SECRET_LENGTH + " characters");
        }
    }
}
