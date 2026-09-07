package dev.reception.auth.web;

import dev.reception.auth.AuthCookies;
import dev.reception.auth.AuthService;
import dev.reception.auth.AuthenticatedUser;
import dev.reception.auth.JwtService;
import dev.reception.auth.RefreshTokenService;
import dev.reception.auth.RefreshTokenService.RequestFingerprint;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five authentication endpoints (docs/04-api-overview.md §4).
 *
 * <p>The controller validates shape, delegates, and turns a session into cookies. It contains no
 * {@code if} about a business rule, which is the layering rule this codebase enforces rather than
 * suggests (docs/02-product-architecture.md §2).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;
    private final AuthCookies cookies;
    private final Clock clock;

    public AuthController(AuthService auth, AuthCookies cookies, Clock clock) {
        this.auth = auth;
        this.cookies = cookies;
        this.clock = clock;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponses.SessionResponse> register(
            @Valid @RequestBody AuthRequests.Register request, HttpServletRequest httpRequest) {
        AuthService.Session session = auth.register(
                request.email(),
                request.password(),
                request.fullName(),
                request.businessName(),
                fingerprintOf(httpRequest));
        return respond(HttpStatus.CREATED, session);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponses.SessionResponse> login(
            @Valid @RequestBody AuthRequests.Login request, HttpServletRequest httpRequest) {
        AuthService.Session session =
                auth.login(request.email(), request.password(), fingerprintOf(httpRequest));
        return respond(HttpStatus.OK, session);
    }

    /**
     * Rotates the refresh token and reissues the access token.
     *
     * <p>Unauthenticated by design: the whole point is that the access token has expired. The
     * refresh cookie is the credential.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponses.SessionResponse> refresh(HttpServletRequest httpRequest) {
        String presented = cookies.read(httpRequest, AuthCookies.REFRESH_TOKEN)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNAUTHENTICATED, "No session to refresh. Please sign in."));
        AuthService.Session session = auth.refresh(presented, fingerprintOf(httpRequest));
        return respond(HttpStatus.OK, session);
    }

    /** Idempotent — logging out twice, or without a session at all, is a {@code 204}. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        cookies.read(httpRequest, AuthCookies.REFRESH_TOKEN).ifPresent(auth::logout);
        return ResponseEntity.noContent()
                .header(AuthCookies.header(), cookies.clearAccessToken())
                .header(AuthCookies.header(), cookies.clearRefreshToken())
                .build();
    }

    @GetMapping("/me")
    public AuthResponses.SessionResponse me(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser principal = JwtService.principalOf(jwt);
        return AuthResponses.SessionResponse.of(auth.describe(principal));
    }

    /**
     * Writes both cookies alongside the body.
     *
     * <p>The access cookie's lifetime matches the token's, so the browser drops it exactly when the
     * server would stop accepting it. The refresh cookie outlives it, which is what makes a silent
     * refresh possible at all.
     */
    private ResponseEntity<AuthResponses.SessionResponse> respond(HttpStatus status, AuthService.Session session) {
        Duration accessMaxAge = Duration.between(clock.instant(), session.accessToken().expiresAt());
        return ResponseEntity.status(status)
                .header(
                        AuthCookies.header(),
                        cookies.setAccessToken(session.accessToken().value(), accessMaxAge))
                .header(
                        AuthCookies.header(),
                        cookies.setRefreshToken(session.refreshToken(), RefreshTokenService.REFRESH_TOKEN_TTL))
                .body(AuthResponses.SessionResponse.of(session));
    }

    /**
     * Where the session was created from. Both values are recorded for the user's session list
     * later and neither is ever trusted for a decision — the user agent is attacker-controlled and
     * the address is whatever the proxy reports.
     */
    private RequestFingerprint fingerprintOf(HttpServletRequest request) {
        return new RequestFingerprint(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
    }
}
