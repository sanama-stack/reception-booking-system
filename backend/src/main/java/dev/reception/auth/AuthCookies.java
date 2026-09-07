package dev.reception.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the two authentication cookies.
 *
 * <p>Tokens travel as {@code httpOnly} cookies on a single origin, so no token is ever reachable
 * from JavaScript and an XSS bug cannot steal a session. That is the security payoff of the Caddy
 * decision, and it is why there is no token-handling code in the frontend at all
 * (docs/06-security.md §2, ADR-0001).
 *
 * <p>{@code SameSite=Lax} plus a same-origin-only API removes the classic CSRF shape; state-changing
 * requests additionally require {@code Content-Type: application/json}, which a cross-site form
 * post cannot set (docs/06-security.md §13).
 */
@Component
public class AuthCookies {

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final boolean secure;

    public AuthCookies(@Value("${app.security.cookies.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public String setAccessToken(String value, Duration maxAge) {
        return build(ACCESS_TOKEN, value, maxAge).toString();
    }

    public String setRefreshToken(String value, Duration maxAge) {
        return build(REFRESH_TOKEN, value, maxAge).toString();
    }

    /** Expiry in the past with an empty value — the only reliable way to remove a cookie. */
    public String clearAccessToken() {
        return build(ACCESS_TOKEN, "", Duration.ZERO).toString();
    }

    public String clearRefreshToken() {
        return build(REFRESH_TOKEN, "", Duration.ZERO).toString();
    }

    public Optional<String> read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** The header name every caller uses, so {@code Set-Cookie} is never spelled out by hand. */
    public static String header() {
        return HttpHeaders.SET_COOKIE;
    }

    private ResponseCookie build(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                // False only in the local and test profiles, where the origin is plain http.
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
