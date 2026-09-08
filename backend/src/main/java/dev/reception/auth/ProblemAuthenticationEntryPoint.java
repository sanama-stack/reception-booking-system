package dev.reception.auth;

import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.ProblemJsonWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers an unauthenticated request with problem+json rather than the resource server's default
 * {@code WWW-Authenticate} challenge.
 *
 * <p>The distinction it draws matters to the client: {@code TOKEN_EXPIRED} and
 * {@code SESSION_REFRESHABLE} both mean "refresh and retry once", anything else means "send the
 * user to sign in". Getting that wrong produces either a refresh loop or a user bounced to the
 * login screen every fifteen minutes.
 *
 * <p><strong>Why two refreshable codes.</strong> {@code TOKEN_EXPIRED} is the case this class was
 * written for and is almost never the case it meets. The access cookie's max age matches the
 * token's lifetime, so a browser deletes it at the very instant the token stops being accepted —
 * and a deleted cookie is not an expired token, it is <em>no</em> token. Every mid-session expiry
 * therefore arrived here as an anonymous request and left as {@code UNAUTHENTICATED}, which the
 * client is told never to refresh: the exact fifteen-minute bounce named above, hidden because no
 * test emulates a browser honouring max age. What the server can still see is that the caller
 * kept a refresh cookie, and that is enough to say the session is recoverable.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemJsonWriter writer;
    private final AuthCookies cookies;

    public ProblemAuthenticationEntryPoint(ProblemJsonWriter writer, AuthCookies cookies) {
        this.writer = writer;
        this.cookies = cookies;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (isExpired(authException)) {
            writer.write(
                    response,
                    ErrorCode.TOKEN_EXPIRED,
                    "Your session token has expired. Refresh it and try again.",
                    request.getRequestURI());
            return;
        }
        if (isRefreshable(request)) {
            writer.write(
                    response,
                    ErrorCode.SESSION_REFRESHABLE,
                    "Your session token is gone but the session is not. Refresh it and try again.",
                    request.getRequestURI());
            return;
        }
        writer.write(
                response, ErrorCode.UNAUTHENTICATED, "Authentication is required.", request.getRequestURI());
    }

    /**
     * The decoder reports an expired token as an invalid one whose description names the reason.
     * Inspecting the description is unpleasant but it is the only signal Spring surfaces, and the
     * alternative — treating every rejection as expiry — would make the client retry tokens that
     * will never work.
     */
    private boolean isExpired(AuthenticationException exception) {
        if (!(exception instanceof InvalidBearerTokenException invalid)) {
            return false;
        }
        if (!(invalid.getError() instanceof BearerTokenError error)) {
            return false;
        }
        String description = error.getDescription();
        return description != null && description.toLowerCase().contains("expired");
    }

    /**
     * No access token at all, but a refresh cookie still in hand.
     *
     * <p>Both halves are load-bearing. Requiring the access cookie to be <em>absent</em> keeps a
     * token that was presented and rejected for any reason other than expiry — forged, tampered
     * with, signed by a key we no longer use — on the {@code UNAUTHENTICATED} path, because
     * refreshing would hand the same rejection back. Requiring the refresh cookie to be present is
     * what separates "expired mid-session" from "signed out", and it is a claim about the caller
     * rather than about the resource: the refresh cookie is not evidence the session is alive, only
     * that asking is worth one round trip. {@code /auth/refresh} is still free to answer
     * {@code UNAUTHENTICATED} or {@code TOKEN_REUSED}, and the client sends the user to sign in when
     * it does.
     *
     * <p>A request to a path that does not exist is answered this way too, since this runs before
     * anything knows whether the path is real — that is the same deliberate blindness that makes an
     * unknown path a {@code 401} rather than a {@code 404} (docs/04-api-overview.md §3). The cost is
     * one wasted rotation on a client typo, and the alternative would be an oracle for the endpoint
     * map.
     */
    private boolean isRefreshable(HttpServletRequest request) {
        return cookies.read(request, AuthCookies.ACCESS_TOKEN).isEmpty()
                && cookies.read(request, AuthCookies.REFRESH_TOKEN).isPresent();
    }
}
