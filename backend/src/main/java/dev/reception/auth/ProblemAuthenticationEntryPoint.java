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
 * <p>The distinction it draws matters to the client: {@code TOKEN_EXPIRED} means "refresh and retry
 * once", anything else means "send the user to sign in". Getting that wrong produces either a
 * refresh loop or a user bounced to the login screen every fifteen minutes.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemJsonWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemJsonWriter writer) {
        this.writer = writer;
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
}
