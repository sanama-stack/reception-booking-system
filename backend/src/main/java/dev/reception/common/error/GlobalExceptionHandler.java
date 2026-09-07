package dev.reception.common.error;

import dev.reception.common.logging.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place in the application that writes an error body (docs/06-security.md §11).
 *
 * <p>Every response is RFC 9457 {@code application/problem+json} extended with a machine-readable
 * {@code code}. Stack traces, SQL text, class names and framework internals never reach a client;
 * an unhandled exception becomes a generic 500 carrying only the request id, which is the key to
 * the log entry that holds the detail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        if (ex.code().status().is5xxServerError()) {
            log.error("Request failed with {}", ex.code(), ex);
        } else {
            log.debug("Request rejected with {}: {}", ex.code(), ex.getMessage());
        }
        return respond(problem(ex.code(), ex.getMessage(), ex.fieldErrors(), request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(problem(
                ErrorCode.FORBIDDEN,
                "You do not have permission to perform this action.",
                List.of(),
                request.getRequestURI()));
    }

    /**
     * The catch-all. Anything reaching here is a defect, so it is logged in full and the client is
     * told nothing beyond the request id.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return respond(problem(
                ErrorCode.INTERNAL_ERROR,
                "Something went wrong. Quote the request id if you contact support.",
                List.of(),
                request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return asObject(problem(
                ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                fieldErrors,
                path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return asObject(problem(
                ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", List.of(), path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // The parser's message names classes and offsets; it is logged, never returned.
        log.debug("Unreadable request body", ex);
        return asObject(problem(
                ErrorCode.VALIDATION_FAILED, "The request body could not be read as JSON.", List.of(), path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(problem(ErrorCode.NOT_FOUND, "No such endpoint.", List.of(), path(request)));
    }

    /**
     * The backend serves no static content, so an unmatched path reaches the resource handler and
     * would otherwise produce Spring's own {@code about:blank} body — the one error response in the
     * application not written here. It is mapped explicitly rather than left to the framework.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(problem(ErrorCode.NOT_FOUND, "No such endpoint.", List.of(), path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return asObject(problem(ErrorCode.NOT_FOUND, "No such endpoint.", List.of(), path(request)));
    }

    private ProblemDetail problem(ErrorCode code, String detail, List<FieldError> fieldErrors, String instance) {
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
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private ResponseEntity<Object> asObject(ProblemDetail problem) {
        return ResponseEntity.status(HttpStatus.valueOf(problem.getStatus())).body(problem);
    }

    private String path(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : null;
    }
}
