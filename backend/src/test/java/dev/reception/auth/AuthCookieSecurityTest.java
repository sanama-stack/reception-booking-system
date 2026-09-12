package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The half of docs/06-security.md §2's cookie sentence no other test can reach.
 *
 * <p>"Both delivered as {@code httpOnly; SameSite=Lax; Path=/}, {@code Secure} outside the {@code
 * local} profile." {@code RegistrationTest} asserts the first three against a real HTTP response and
 * <strong>cannot assert the fourth</strong>: the whole suite runs under the {@code test} profile,
 * where {@code app.security.cookies.secure} is deliberately {@code false} because the origin is
 * plain http. So every existing assertion about these cookies is made at the one setting where the
 * attribute is supposed to be absent, and nothing in the suite had ever read the value that ships.
 *
 * <p>That is {@link dev.reception.common.config.SecretsGuardTest}'s shape again — a control whose
 * only live configuration is one no test starts. Setting {@code application-prod.yml} to {@code
 * secure: false}, or flipping the {@code @Value} default, left 986 tests green while every
 * production session cookie travelled where any network observer could take it. A stolen access
 * cookie is a logged-in session; a stolen refresh cookie is a renewable one.
 *
 * <p>Two things have to hold and they fail independently: the flag must reach the header at all
 * (§1), and the configuration must resolve it to {@code true} everywhere that is not a developer's
 * machine (§2). A test of either alone passes while the other is broken.
 */
class AuthCookieSecurityTest {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    // ---- 1. the flag reaches the header ---------------------------------------------------

    /**
     * The positive and negative case together, because only the pair says the attribute tracks the
     * property. Asserting {@code Secure} is present when it is {@code true} also passes for a
     * builder that hardcodes it; asserting it is absent when {@code false} also passes for a builder
     * that dropped {@code .secure(...)} altogether.
     */
    @Test
    @DisplayName("the Secure attribute is present when the property is on and absent when it is off")
    void the_secure_attribute_tracks_the_property() {
        assertThat(new AuthCookies(true).setAccessToken("a-token", FIFTEEN_MINUTES))
                .containsIgnoringCase("Secure");
        assertThat(new AuthCookies(false).setAccessToken("a-token", FIFTEEN_MINUTES))
                .doesNotContainIgnoringCase("Secure");
    }

    /**
     * Every cookie this class writes, not just the access one.
     *
     * <p>A refresh cookie sent in the clear is worse than an access cookie sent in the clear: it
     * renews. And the two <em>clear</em> methods go through the same builder, so a divergence there
     * would mean the attribute set on the way in is dropped on the way out — which is how a cookie
     * gets replaced by a non-Secure one of the same name.
     */
    @Test
    @DisplayName("both cookies and both clears carry it, because they are one builder")
    void every_cookie_this_class_writes_carries_it() {
        AuthCookies cookies = new AuthCookies(true);

        assertThat(cookies.setAccessToken("a", FIFTEEN_MINUTES)).containsIgnoringCase("Secure");
        assertThat(cookies.setRefreshToken("r", FIFTEEN_MINUTES)).containsIgnoringCase("Secure");
        assertThat(cookies.clearAccessToken()).containsIgnoringCase("Secure");
        assertThat(cookies.clearRefreshToken()).containsIgnoringCase("Secure");
    }

    /**
     * The attributes {@code RegistrationTest} already covers, asserted here at {@code secure=true}
     * as well.
     *
     * <p>Not a duplicate: that test proves them over real HTTP at the one setting the suite runs,
     * and this proves turning {@code Secure} on did not cost one of the others. They are built in a
     * single chained call, which is exactly where a lost {@code .httpOnly(true)} hides.
     */
    @Test
    @DisplayName("turning Secure on does not cost httpOnly, SameSite or Path")
    void the_other_attributes_survive_it() {
        assertThat(new AuthCookies(true).setAccessToken("a", FIFTEEN_MINUTES))
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .containsIgnoringCase("Path=/");
    }

    // ---- 2. the configuration resolves it -------------------------------------------------

    /**
     * The real {@code application-prod.yml}, resolved the way Boot resolves it.
     *
     * <p>{@link ConfigDataApplicationContextInitializer} is what makes this read the file rather
     * than a property this test typed — the distinction that matters, since the defect being
     * guarded against is the file saying the wrong thing. A regex over the YAML would pass while an
     * overriding property somewhere else won.
     */
    @Test
    @DisplayName("the prod profile resolves the cookie to Secure, read off the real file")
    void prod_resolves_it_to_true() {
        runner("prod").run(context -> assertThat(context.getBean(AuthCookies.class).setAccessToken("a", FIFTEEN_MINUTES))
                .containsIgnoringCase("Secure"));
    }

    /**
     * <strong>The default fails safe, and that is the assertion.</strong>
     *
     * <p>Probed with a profile that has no file, because <em>"no profile"</em> is not the question it
     * looks like: {@code application.yml} sets {@code spring.profiles.default: local}, so an empty
     * profile list means {@code local} and resolves to {@code false}. The {@code @Value} fallback is
     * unreachable through the config files as they stand, and this test asserted the opposite until
     * it was run. What the fallback actually governs is a profile nobody has written a file for yet
     * — a {@code staging} added next month — and there it decides, so it is pinned here.
     *
     * <p>A "simplification" of {@code :true} to {@code :false} is invisible in every other test in
     * this repository, and would hand that new environment a plain cookie by omission.
     */
    @Test
    @DisplayName("a profile with no file of its own gets Secure by default, not the other way round")
    void the_fallback_is_the_safe_one() {
        runner("staging")
                .run(context -> assertThat(
                                context.getBean(AuthCookies.class).setAccessToken("a", FIFTEEN_MINUTES))
                        .as("nothing sets the key under this profile, so the @Value default decides")
                        .containsIgnoringCase("Secure"));
    }

    /**
     * And the reason the test above could not simply omit the profile.
     *
     * <p>Recorded as an assertion rather than a comment because it is load-bearing in both
     * directions: {@code default: local} is what stops an IDE run from pointing production-ish
     * settings at a database that is not there, and it is also what makes "unset" mean "insecure
     * cookie". Changing it silently changes what every unprofiled deployment gets.
     */
    @Test
    @DisplayName("an unset profile means local, which is why unset is not Secure")
    void an_unset_profile_is_local() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AuthCookies.class)
                .run(context -> {
                    assertThat(context.getEnvironment().getDefaultProfiles()).containsExactly("local");
                    assertThat(context.getBean(AuthCookies.class).setAccessToken("a", FIFTEEN_MINUTES))
                            .doesNotContainIgnoringCase("Secure");
                });
    }

    /**
     * The two profiles that turn it off, named and bounded.
     *
     * <p>They are the plain-http ones — a developer's machine and this suite — and that is the whole
     * of the exemption §2 grants. The test exists so adding a third is a deliberate act with a
     * failing build attached, rather than a line in a YAML nobody re-reads.
     */
    @Test
    @DisplayName("local and test are the only profiles that turn it off")
    void only_the_plain_http_profiles_turn_it_off() {
        for (String profile : new String[] {"local", "test"}) {
            runner(profile)
                    .run(context -> assertThat(
                                    context.getBean(AuthCookies.class).setAccessToken("a", FIFTEEN_MINUTES))
                            .as("%s serves over plain http, where Secure would make the cookie unusable", profile)
                            .doesNotContainIgnoringCase("Secure"));
        }
    }

    private static ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .withUserConfiguration(AuthCookies.class);
    }
}
