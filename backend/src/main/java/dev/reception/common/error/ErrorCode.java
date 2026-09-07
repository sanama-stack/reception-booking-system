package dev.reception.common.error;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable {@code code} extension on every problem+json body
 * (docs/04-api-overview.md §3).
 *
 * <p>Seeded in phase 01 with the codes the scaffolding itself can produce. Later phases add their
 * own; the enum is the single published list.
 */
public enum ErrorCode {

    /** Request shape or field rules violated. */
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed"),

    /**
     * Does not exist <em>or</em> belongs to another tenant — indistinguishable by design
     * (docs/06-security.md §3).
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

    /** Authenticated, but the role is insufficient. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),

    /** A rate limit was exceeded; the response carries {@code Retry-After}. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),

    /**
     * The fallback. Carries the request id and nothing else — no stack trace, no SQL, no class
     * names (docs/06-security.md §11).
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The {@code type} URI for this code, e.g. {@code https://reception.dev/errors/not-found}. */
    public String typeUri() {
        return "https://reception.dev/errors/" + name().toLowerCase().replace('_', '-');
    }
}
