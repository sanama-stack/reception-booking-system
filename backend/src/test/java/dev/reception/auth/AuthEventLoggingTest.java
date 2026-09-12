package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <strong>What signing in leaves behind, and what it must not.</strong>
 *
 * <p>docs/06-security.md §14: <em>"Authentication events (login, refresh, revocation) are logged
 * with user id and IP."</em> Before this class, that was true of <strong>none of the three</strong>.
 * {@code AuthService} contained no log statement at all; the only authentication line anywhere in
 * the application was the replay warning in {@link RefreshTokenFamilyRevoker}, which carried a user
 * id and <em>no address</em>. An audit trail that starts at the one event an attacker triggers
 * deliberately, and cannot say where it came from, is not an audit trail.
 *
 * <p>The data was never the problem: {@code RequestFingerprint} has carried the user agent and the
 * peer address since phase 02, and the refresh token row stores both. It simply never reached a
 * log.
 *
 * <p><strong>The list of events is derived.</strong> {@link #every_auth_endpoint_is_accounted_for}
 * reads the {@code /auth} surface off {@link RequestMappingHandlerMapping} and requires every write
 * on it to be either a logged event or an explicit exemption — {@code EndpointCatalogue}'s
 * classify-or-fail, one section over. A typed list of three would not fail for the fourth
 * authentication endpoint somebody adds, which is the endpoint this section is about.
 *
 * <p><strong>Two failures are possible and this class separates them.</strong> The line can be
 * missing, or it can be too good: the obvious way to make an authentication event useful is to log
 * the credentials it carried. So every assertion here has a negative twin — the password, the
 * access token and the refresh token must appear in no line at all.
 */
class AuthEventLoggingTest extends IntegrationTest {

    private static final String EMAIL = "nino@salon.test";
    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * Every write on the {@code /auth} surface, and the event it must record. {@code null} is an
     * exemption and needs the reason beside it, because "this one is fine" is the sentence that
     * precedes a hole.
     */
    private static final Map<String, String> EXPECTED_EVENTS = Map.of(
            "POST /auth/register", "register",
            "POST /auth/login", "login",
            "POST /auth/refresh", "refresh",
            "POST /auth/logout", "logout");

    /**
     * The endpoints {@link #no_line_carries_a_credential} actually calls, reconciled against {@link
     * #EXPECTED_EVENTS} so the credential sweep cannot quietly stop covering one of them.
     */
    private static final Set<String> DRIVEN =
            Set.of("POST /auth/register", "POST /auth/login", "POST /auth/refresh", "POST /auth/logout");

    /** Reads, which record nothing because they change nothing. */
    private static final Map<String, String> EXEMPT =
            Map.of("GET /auth/me", "answers about the caller's own session and starts, rotates and ends nothing");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private ListAppender<ILoggingEvent> captured;
    private Logger root;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        captured.setContext(root.getLoggerContext());
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("register, login, refresh and logout each record the user and the address")
    void every_authentication_event_is_recorded() {
        AuthTestClient client = new AuthTestClient(rest, port);

        String registered = client.register(EMAIL, PASSWORD, "Salon Aria").getBody();
        String userId = JsonPath.read(registered, "$.user.id");
        assertEventRecorded("register", userId);

        client.forgetCookies();
        client.login(EMAIL, PASSWORD);
        assertEventRecorded("login", userId);

        client.post("/auth/refresh", Map.of());
        assertEventRecorded("refresh", userId);

        client.post("/auth/logout", Map.of());
        assertEventRecorded("logout", userId);
    }

    /**
     * The negative twin, and the reason the assertions above are not simply "log more".
     *
     * <p>An authentication event is the one place in the system where a password, an access token
     * and a refresh token are all in scope at once.
     */
    @Test
    @DisplayName("and no line carries the password or either token")
    void no_line_carries_a_credential() {
        AuthTestClient client = new AuthTestClient(rest, port);
        client.register(EMAIL, PASSWORD, "Salon Aria");
        // Every write on the surface, not a sample of it. A plant that logged the password from
        // login() survived an earlier version of this test that drove only register, refresh and
        // logout — the endpoint that receives the credential was the one it did not exercise.
        client.forgetCookies();
        client.login(EMAIL, PASSWORD);
        String refreshToken = client.cookieValue("refresh_token").orElseThrow();
        String accessToken = client.cookieValue("access_token").orElseThrow();
        client.post("/auth/refresh", Map.of());
        client.post("/auth/logout", Map.of());

        Set<String> leaked = new TreeSet<>();
        for (String line : lines()) {
            if (line.contains(PASSWORD)) {
                leaked.add("the password");
            }
            if (line.contains(refreshToken)) {
                leaked.add("the refresh token");
            }
            if (line.contains(accessToken)) {
                leaked.add("the access token");
            }
        }

        assertThat(leaked)
                .as("a log line is not a place to put a credential (docs/06-security.md §10)")
                .isEmpty();

        assertThat(lines())
                .as(
                        """
                        And the capture saw the run at all. Without this, "no line carried a \
                        credential" is equally true of an appender that was never attached — which \
                        is the whole of what this test would then be asserting.""")
                .anyMatch(line -> line.contains("Authentication event"));
    }

    /**
     * Classify or fail, one section over from {@code EndpointCatalogue}.
     *
     * <p>Derived from Spring's routing table rather than typed, because the authentication endpoint
     * that goes unlogged is the one added after this file was written, and forgetting it in the
     * controller and forgetting it in a typed list are one act.
     */
    @Test
    @DisplayName("every endpoint on the /auth surface is either a recorded event or an exemption")
    void every_auth_endpoint_is_accounted_for() {
        Set<String> surface = authSurface();

        assertThat(surface)
                .as("the /auth surface, derived from the handler mapping — empty means the derivation broke")
                .contains("POST /auth/login", "POST /auth/refresh", "POST /auth/logout");

        Set<String> unaccounted = new TreeSet<>(surface);
        unaccounted.removeAll(EXPECTED_EVENTS.keySet());
        unaccounted.removeAll(EXEMPT.keySet());
        assertThat(unaccounted)
                .as(
                        """
                        These endpoints are on the /auth surface and this file says nothing about \
                        them. Either they record an authentication event — add them to \
                        EXPECTED_EVENTS and they will be driven and asserted — or they do not, and \
                        the reason belongs in EXEMPT where the next reader will find it.""")
                .isEmpty();

        Set<String> vanished = new TreeSet<>(EXPECTED_EVENTS.keySet());
        vanished.addAll(EXEMPT.keySet());
        vanished.removeAll(surface);
        assertThat(vanished).as("named here and mapped nowhere").isEmpty();

        assertThat(DRIVEN)
                .as(
                        """
                        Every endpoint that records an event must also be DRIVEN by                         no_line_carries_a_credential, or a credential logged from the one that is                         not driven goes unseen — which is exactly what happened to an earlier                         version of this file.""")
                .containsAll(EXPECTED_EVENTS.keySet());
    }

    /**
     * One event, with the two fields §14 names.
     *
     * <p>The {@code ip} check is the part that needs saying: {@code ip=null} would satisfy any
     * assertion that only looked for the key, and a fingerprint that lost its address is exactly
     * the regression this is written against.
     */
    private void assertEventRecorded(String event, String userId) {
        List<String> matching = lines().stream()
                .filter(line -> line.contains("event=" + event))
                .toList();

        assertThat(matching)
                .as("no line recorded the %s event. docs/06-security.md §14", event)
                .isNotEmpty();

        assertThat(matching)
                .as("the %s event must name the user it was about", event)
                .anyMatch(line -> line.contains("user_id=" + userId));

        assertThat(matching)
                .as(
                        """
                        the %s event must carry the address it came from, and carry a real one — \
                        "ip=null" is what a lost RequestFingerprint looks like and it satisfies any \
                        check that only looks for the key""",
                        event)
                .anyMatch(line -> line.matches(".*\\bip=(?!null\\b)\\S+.*"));
    }

    private List<String> lines() {
        return captured.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private Set<String> authSurface() {
        Set<String> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.reception")) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith("/auth")) {
                    continue;
                }
                info.getMethodsCondition()
                        .getMethods()
                        .forEach(method -> endpoints.add(method.asHttpMethod().name() + " " + pattern));
            }
        });
        return endpoints;
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }
}
