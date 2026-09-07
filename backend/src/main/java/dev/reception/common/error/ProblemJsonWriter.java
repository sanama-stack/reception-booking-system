package dev.reception.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Writes a problem+json body straight to the response.
 *
 * <p>Only the security handlers need this: authentication and authorization fail inside the filter
 * chain, before the dispatcher exists to route the exception to
 * {@link GlobalExceptionHandler}. The body is built by {@link ProblemDetails} either way, so the
 * two paths cannot produce different shapes.
 */
@Component
public class ProblemJsonWriter {

    private final ObjectMapper objectMapper;

    public ProblemJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail, String instance)
            throws IOException {
        ProblemDetail problem = ProblemDetails.of(code, detail, List.of(), instance);
        response.setStatus(code.status().value());
        // No explicit charset: the advice path does not set one either, and problem+json is UTF-8
        // by definition. Jackson writes UTF-8 bytes regardless. The point is that a client cannot
        // tell which of the two paths produced a body.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
