package dev.reception.auth;

import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.ProblemJsonWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Authenticated, but the role is insufficient.
 *
 * <p>Note that this is <em>not</em> the cross-tenant case. A request for another tenant's resource
 * returns {@code 404}, because a {@code 403} would confirm the resource exists
 * (docs/06-security.md §3). This handler only ever fires on a genuine role check.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemJsonWriter writer;

    public ProblemAccessDeniedHandler(ProblemJsonWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(
                response,
                ErrorCode.FORBIDDEN,
                "You do not have permission to perform this action.",
                request.getRequestURI());
    }
}
