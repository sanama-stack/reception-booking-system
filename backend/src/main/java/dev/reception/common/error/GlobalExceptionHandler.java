package dev.reception.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    /** The exclusion constraint from {@code V5__customers_and_appointments.sql}. */
    private static final String APPOINTMENT_OVERLAP_CONSTRAINT = "appointments_no_overlap";

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
     * A constraint the database refused, mapped by <strong>constraint name</strong>.
     *
     * <p>{@code appointments_no_overlap} is the exclusion constraint, and reaching it means a
     * booking lost a race it could not have been protected from any other way (ADR-0002). It is a
     * {@code 409} rather than a {@code 500} because nothing went wrong — the world moved between the
     * Slot being offered and the row being written, and the client's correct response is to re-read
     * availability.
     *
     * <p><strong>Keyed on the name, never on the exception type alone.</strong> Catching
     * {@code DataIntegrityViolationException} broadly and answering "slot unavailable" would tell a
     * caller their time was taken when the real cause was a null in a column nobody noticed — and
     * the defect would then be invisible, because the response looked like an ordinary race. Any
     * other constraint falls through to a logged 500, which is what an unexpected integrity failure
     * is.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        if (namesConstraint(ex, APPOINTMENT_OVERLAP_CONSTRAINT)) {
            log.debug("Booking lost the exclusion-constraint race");
            return respond(problem(
                    ErrorCode.SLOT_UNAVAILABLE,
                    "That time was booked while you were deciding. Choose another.",
                    List.of(),
                    request.getRequestURI()));
        }
        log.error("Unmapped data integrity violation", ex);
        return respond(problem(
                ErrorCode.INTERNAL_ERROR,
                "Something went wrong. Quote the request id if you contact support.",
                List.of(),
                request.getRequestURI()));
    }

    /**
     * Two writers changed the same row and this one lost its {@code @Version} check.
     *
     * <p>Handled here as well as flushed early inside the services, because the failure can also be
     * raised at commit — after the service method has returned and there is nobody left to catch it.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.debug("Optimistic lock lost", ex);
        return respond(problem(
                ErrorCode.VERSION_CONFLICT,
                "Someone else changed this appointment while you were editing it. Reload and try again.",
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
        // Shared with the security handlers, which run before the dispatcher and so can never
        // reach this advice (ProblemDetails).
        return ProblemDetails.of(code, detail, fieldErrors, instance);
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private ResponseEntity<Object> asObject(ProblemDetail problem) {
        return ResponseEntity.status(HttpStatus.valueOf(problem.getStatus())).body(problem);
    }

    /**
     * Whether this violation is the named constraint.
     *
     * <p>The name reaches us through the driver's message rather than through a typed field:
     * Spring's {@code DataIntegrityViolationException} does not carry one, and Hibernate's
     * {@code ConstraintViolationException} only sometimes parses it out. Searching the whole cause
     * chain's messages is the reliable version, and it is why the constraint name is a constant
     * here — renaming it in the migration without changing this constant would silently turn every
     * lost race into a 500.
     */
    private static boolean namesConstraint(Throwable error, String constraintName) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private String path(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : null;
    }
}
