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

    /** Registration on an address that already has an account. */
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered"),

    /** The derived or requested slug is in use. */
    SLUG_TAKEN(HttpStatus.CONFLICT, "Slug already taken"),

    /**
     * Login failed. Deliberately does not distinguish "no such user" from "wrong password" — one
     * message for both is what keeps the endpoint from confirming which addresses have accounts.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),

    /**
     * No credential was presented, or the one presented is not a token this server issued. The
     * client should send the user to sign in rather than attempt a refresh.
     */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Not authenticated"),

    /** The access token expired; the client should refresh and retry once. */
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),

    /**
     * A refresh token was presented twice. The value was captured, so the whole token family is
     * revoked and every session descended from that login ends (docs/06-security.md §2).
     */
    TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Token reused"),

    /**
     * Does not exist <em>or</em> belongs to another tenant — indistinguishable by design
     * (docs/06-security.md §3).
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

    /** Authenticated, but the role is insufficient. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),

    /**
     * A hard delete was refused because Appointments reference the row. Deactivation is the
     * supported path and the message says so (docs/03-data-model.md §1).
     */
    SERVICE_IN_USE(HttpStatus.CONFLICT, "Service is in use"),

    /**
     * The Service is not bookable. Availability refuses rather than returning nothing, because "no
     * slots" and "this is switched off" are different facts and only one of them is fixed in
     * Settings.
     */
    SERVICE_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Service is not bookable"),

    /** The Employee is not bookable, for the same reason. */
    EMPLOYEE_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Employee is not bookable"),

    /**
     * No assignment exists between the requested Employee and the requested Service. A refusal
     * rather than an empty list: asking for an Employee who cannot perform the Service is a mistaken
     * question, and answering "no availability" would send the caller looking for a free time that
     * does not exist for anyone (docs/01-prd.md FR-5).
     */
    EMPLOYEE_CANNOT_PERFORM_SERVICE(HttpStatus.UNPROCESSABLE_ENTITY, "Employee cannot perform this service"),

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
