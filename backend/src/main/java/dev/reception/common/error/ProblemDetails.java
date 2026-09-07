package dev.reception.common.error;

import dev.reception.common.logging.RequestIdFilter;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds the one error body shape the application produces.
 *
 * <p>Extracted so that {@link GlobalExceptionHandler} and the security handlers — which run in the
 * filter chain, before any controller advice can see them — cannot drift apart. "One place writes
 * every error body" is a claim phase 01 made and this is what keeps it true now that
 * authentication can fail before the dispatcher.
 */
public final class ProblemDetails {

    private ProblemDetails() {}

    public static ProblemDetail of(ErrorCode code, String detail, List<FieldError> fieldErrors, String instance) {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setDetail(detail);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("errors", fieldErrors);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            // The key to the log entry that holds the detail the client is not told.
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
