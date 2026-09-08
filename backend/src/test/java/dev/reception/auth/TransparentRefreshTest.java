package dev.reception.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reception.support.AuthTestClient;
import dev.reception.support.DatabaseCleaner;
import dev.reception.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * What the server tells a client whose access token is gone, which is the half of the transparent
 * refresh that had no test at all (the phase 02 handoff §8 names the gap).
 *
 * <p>The case these tests exist for is unglamorous and was live for two phases: fifteen minutes
 * into a session the browser reaches the access cookie's max age and <em>deletes</em> it, so the
 * next request carries no access token rather than an expired one. The server answered
 * {@code UNAUTHENTICATED}, the client is told never to refresh that, and the user got an error
 * state and a "Try again" that failed identically — every fifteen minutes, on a session the very
 * next {@code POST /auth/refresh} would have restored.
 *
 * <p>Nothing in the suite could see it, because {@link AuthTestClient} sends whatever cookies it
 * holds forever and has no notion of a max age. {@link AuthTestClient#expireCookie} is the missing
 * browser behaviour, and it is the whole reason these tests can fail.
 */
class TransparentRefreshTest extends IntegrationTest {

    private static final String EMAIL = "keti@aria.test";
    private static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private AuthTestClient client;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        client = new AuthTestClient(rest, port);
        client.register(EMAIL, PASSWORD, "Salon Aria");
    }

    /**
     * The regression. Before the fix this answered {@code UNAUTHENTICATED} and the client, obeying
     * the published meaning of that code, sent the user to sign in instead of refreshing.
     */
    @Test
    void a_session_whose_access_cookie_reached_its_max_age_is_told_it_can_refresh() {
        client.expireCookie("access_token");

        ResponseEntity<String> response = client.get("/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"SESSION_REFRESHABLE\"");
    }

    /**
     * And the advice is good: the refresh the code asks for succeeds and the retried request is
     * answered normally. This is the whole user-visible behaviour — an expiry nobody notices.
     */
    @Test
    void the_refresh_that_code_asks_for_restores_the_session() {
        client.expireCookie("access_token");
        client.get("/auth/me");

        ResponseEntity<String> refresh = client.post("/auth/refresh", null);
        ResponseEntity<String> retried = client.get("/auth/me");

        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.cookieValue("access_token")).isPresent();
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).contains("\"email\":\"" + EMAIL + "\"");
    }

    /** A signed-out visitor has nothing to refresh with, and must not be told to try. */
    @Test
    void a_visitor_with_no_cookies_at_all_is_not_told_to_refresh() {
        client.forgetCookies();

        ResponseEntity<String> response = client.get("/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    /**
     * A refresh cookie alone does not make everything refreshable. A token that was presented and
     * rejected for a reason other than expiry would be rejected again after a refresh, so the
     * refreshable branch requires the access cookie to be absent rather than merely unusable.
     */
    @Test
    void a_forged_access_token_is_not_refreshable_even_beside_a_valid_refresh_cookie() {
        client.overwriteCookie("access_token", "not.a.jwt");

        ResponseEntity<String> response = client.get("/auth/me");

        assertThat(client.cookieValue("refresh_token")).isPresent();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    /**
     * The case the entry point was originally written for still works. A browser does not produce
     * it — it drops the cookie first — but a client that keeps a dead token past its max age does,
     * and the new branch must not have swallowed it.
     */
    @Test
    void an_expired_token_that_is_still_presented_is_told_it_expired() {
        client.overwriteCookie("access_token", expiredAccessToken());

        ResponseEntity<String> response = client.get("/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"TOKEN_EXPIRED\"");
    }

    /**
     * The anti-enumeration property survives the new code. Which of the three 401 codes you get
     * depends on the cookies you sent; it never depends on whether the path exists
     * (docs/04-api-overview.md §3). The cost is that a client typo spends one rotation, which is
     * the trade this project has already made everywhere else.
     */
    @Test
    void an_unknown_path_is_answered_exactly_like_a_known_one() {
        client.expireCookie("access_token");

        ResponseEntity<String> known = client.get("/auth/me");
        ResponseEntity<String> unknown = client.get("/no-such-endpoint");

        assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode());
        assertThat(codeOf(unknown)).isEqualTo(codeOf(known));
    }

    /** A token this server signed, expired an hour ago. The decoder rejects it on expiry alone. */
    private String expiredAccessToken() {
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtService.ISSUER)
                .issuedAt(expiredAt.minus(JwtService.ACCESS_TOKEN_TTL))
                .expiresAt(expiredAt)
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_BUSINESS_ID, UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_ROLE, Role.OWNER.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String codeOf(ResponseEntity<String> response) {
        return String.valueOf(response.getBody()).replaceAll("(?s).*\"code\":\"([A-Z_]+)\".*", "$1");
    }
}
