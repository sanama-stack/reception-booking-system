package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Login, refresh rotation, replay detection and logout.
 *
 * <p>The tests that matter are the two about rotation: that a refresh invalidates what it replaced,
 * and that presenting a replaced token ends every session in its family. Everything else here is
 * conventional.
 */
class SessionLifecycleTest extends IntegrationTest {

    private static final String EMAIL = "nino@aria.test";
    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient client;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        client = new AuthTestClient(rest, port);
        client.register(EMAIL, PASSWORD, "Salon Aria");
        client.forgetCookies();
    }

    @Test
    void login_sets_both_cookies_and_returns_the_session() {
        ResponseEntity<String> response = client.login(EMAIL, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"role\":\"OWNER\"").contains("\"slug\":\"salon-aria\"");
        assertThat(client.cookieValue("access_token")).isPresent();
        assertThat(client.cookieValue("refresh_token")).isPresent();
    }

    /**
     * One response for both causes. Distinguishing them would turn the endpoint into an oracle for
     * which addresses have accounts (docs/06-security.md §2).
     */
    @Test
    void a_wrong_password_and_an_unknown_email_are_answered_identically() {
        ResponseEntity<String> wrongPassword = client.login(EMAIL, "not-the-right-password");
        client.forgetCookies();
        ResponseEntity<String> unknownEmail = client.login("nobody@aria.test", PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(bodyWithoutRequestId(unknownEmail)).isEqualTo(bodyWithoutRequestId(wrongPassword));
        assertThat(wrongPassword.getBody()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    @Test
    void a_failed_login_sets_no_cookie() {
        client.login(EMAIL, "not-the-right-password");

        assertThat(client.cookieValue("access_token")).isEmpty();
        assertThat(client.cookieValue("refresh_token")).isEmpty();
    }

    @Test
    void me_requires_authentication() {
        ResponseEntity<String> response = client.getAnonymously("/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    /**
     * The {@code 401}-for-unknown-paths rule applies only to callers who are not authenticated. A
     * signed-in caller who asks for a path that does not exist is told so.
     *
     * <p>Nothing about enumeration depends on this — someone signed in may freely learn which of
     * <em>their own</em> endpoints exist. What depends on it is the client: `lib/api/client.ts`
     * reads {@code 401 UNAUTHENTICATED} as "the session is over" and signs the user out, which is
     * only safe while a signed-in caller cannot provoke that code by mistyping a path. If this ever
     * became a {@code 401}, a stray request would sign people out mid-session, and it would look
     * like the fifteen-minute bug all over again.
     */
    @Test
    void a_signed_in_caller_asking_for_an_unknown_path_is_told_it_does_not_exist() {
        client.login(EMAIL, PASSWORD);

        ResponseEntity<String> response = client.get("/no-such-endpoint");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void me_describes_the_signed_in_owner_and_their_business() {
        client.login(EMAIL, PASSWORD);

        ResponseEntity<String> response = client.get("/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"email\":\"" + EMAIL + "\"")
                .contains("\"slug\":\"salon-aria\"")
                .contains("\"role\":\"OWNER\"");
    }

    @Test
    void refresh_issues_a_new_refresh_token_and_revokes_the_old_one() {
        client.login(EMAIL, PASSWORD);
        String original = client.cookieValue("refresh_token").orElseThrow();

        ResponseEntity<String> response = client.post("/auth/refresh", null);
        String rotated = client.cookieValue("refresh_token").orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated).isNotEqualTo(original);
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.hash(original)).orElseThrow().isRevoked())
                .isTrue();
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.hash(rotated)).orElseThrow().isRevoked())
                .isFalse();
    }

    @Test
    void the_session_still_works_after_a_refresh() {
        client.login(EMAIL, PASSWORD);
        client.post("/auth/refresh", null);

        assertThat(client.get("/auth/me").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The replay case. A refresh token is single-use, so a second presentation means two parties
     * hold it. We cannot tell which is asking, so the whole family goes.
     */
    @Test
    void replaying_a_rotated_token_revokes_the_entire_family() {
        client.login(EMAIL, PASSWORD);
        String original = client.cookieValue("refresh_token").orElseThrow();
        client.post("/auth/refresh", null);
        String successor = client.cookieValue("refresh_token").orElseThrow();

        client.overwriteCookie("refresh_token", original);
        ResponseEntity<String> replay = client.post("/auth/refresh", null);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).contains("\"code\":\"TOKEN_REUSED\"");
        // Not just the replayed token: the successor the legitimate client is holding is dead too.
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.hash(successor)).orElseThrow().isRevoked())
                .isTrue();
    }

    @Test
    void a_revoked_family_cannot_refresh_again() {
        client.login(EMAIL, PASSWORD);
        String original = client.cookieValue("refresh_token").orElseThrow();
        client.post("/auth/refresh", null);
        client.overwriteCookie("refresh_token", original);
        client.post("/auth/refresh", null);

        ResponseEntity<String> afterRevocation = client.post("/auth/refresh", null);

        assertThat(afterRevocation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void an_unknown_refresh_token_is_refused_without_revoking_anything() {
        client.login(EMAIL, PASSWORD);
        client.overwriteCookie("refresh_token", "a-token-this-server-never-issued");

        ResponseEntity<String> response = client.post("/auth/refresh", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(refreshTokens.findAll()).noneMatch(RefreshToken::isRevoked);
    }

    @Test
    void refreshing_without_a_cookie_is_refused() {
        ResponseEntity<String> response = client.post("/auth/refresh", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void logout_clears_both_cookies_and_revokes_the_refresh_token() {
        client.login(EMAIL, PASSWORD);
        String refreshToken = client.cookieValue("refresh_token").orElseThrow();

        ResponseEntity<String> response = client.post("/auth/logout", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.cookieValue("access_token")).isEmpty();
        assertThat(client.cookieValue("refresh_token")).isEmpty();
        assertThat(refreshTokens
                        .findByTokenHash(RefreshTokenService.hash(refreshToken))
                        .orElseThrow()
                        .isRevoked())
                .isTrue();
    }

    @Test
    void logout_is_idempotent() {
        client.login(EMAIL, PASSWORD);
        client.post("/auth/logout", null);

        assertThat(client.post("/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logout_without_a_session_is_still_a_success() {
        assertThat(client.post("/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** A tampered or unsigned access token is not a session. */
    @Test
    void a_forged_access_token_is_rejected() {
        client.login(EMAIL, PASSWORD);
        client.overwriteCookie("access_token", "not.a.jwt");

        ResponseEntity<String> response = client.get("/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_validates_the_shape_of_its_request() {
        ResponseEntity<String> response = client.post("/auth/login", Map.of("email", "", "password", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    /** The request id differs per request and is not part of what the two responses reveal. */
    private static String bodyWithoutRequestId(ResponseEntity<String> response) {
        return String.valueOf(response.getBody()).replaceAll("\"requestId\":\"[^\"]*\"", "");
    }
}
