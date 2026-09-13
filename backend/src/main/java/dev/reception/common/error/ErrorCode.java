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
     * No access token was presented, but the caller still holds a refresh cookie — so the session
     * is very likely recoverable and the client should refresh and retry once, exactly as for
     * {@link #TOKEN_EXPIRED}.
     *
     * <p>This is what a browser produces at the fifteen-minute mark, and it is the ordinary case
     * rather than the exotic one: the access cookie's max age matches the token's lifetime, so the
     * browser deletes the cookie instead of presenting an expired token. Without a code of its own
     * that request is indistinguishable from a signed-out visitor, and the client is told never to
     * refresh those.
     */
    SESSION_REFRESHABLE(HttpStatus.UNAUTHORIZED, "Session refreshable"),

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

    /**
     * The exclusion constraint rejected the booking: someone else took the time between the Slot
     * being offered and this request being written.
     *
     * <p>A {@code 409} rather than a {@code 422}, because nothing about the request was wrong — the
     * world moved. The client's correct response is to re-read availability and offer the customer
     * what is left, which is what makes the distinction worth keeping (ADR-0002).
     */
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "Slot unavailable"),

    /**
     * Two people changed the same Appointment at once and this one lost. Distinct from
     * {@link #SLOT_UNAVAILABLE}: no time is contested, the record is — retrying after a re-read is
     * the answer, and the re-read may show the change the other person made was the one wanted.
     */
    VERSION_CONFLICT(HttpStatus.CONFLICT, "Appointment was modified"),

    /**
     * Not a legal move in the Appointment state machine — completing a cancelled appointment, or
     * moving one out of a terminal state. See {@code AppointmentStatus}.
     */
    INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "Not a legal status change"),

    /**
     * A Customer tried to cancel or reschedule inside the Cancellation Window. <strong>The Business
     * is never bound by it</strong> (CONTEXT.md), so this code can only be produced for a Customer.
     */
    CANCELLATION_WINDOW_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "Too late to change this appointment"),

    /** The requested start has already passed. */
    BOOKING_IN_PAST(HttpStatus.UNPROCESSABLE_ENTITY, "That time has passed"),

    /** Sooner than the Business's minimum lead time allows. */
    BELOW_MIN_LEAD_TIME(HttpStatus.UNPROCESSABLE_ENTITY, "Too soon to book"),

    /** Further ahead than the Business's booking horizon reaches. */
    BEYOND_MAX_ADVANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Too far ahead to book"),

    /**
     * The Service does not fit inside a stretch of the Business's opening hours at that time. Kept
     * distinct from {@link #OUTSIDE_WORKING_HOURS} because the two send a caller to different
     * remedies: this one means try another time, that one means try another person.
     */
    OUTSIDE_BUSINESS_HOURS(HttpStatus.UNPROCESSABLE_ENTITY, "Outside opening hours"),

    /** It fits the Business's hours but not this Employee's Working Schedule. */
    OUTSIDE_WORKING_HOURS(HttpStatus.UNPROCESSABLE_ENTITY, "Outside this employee's hours"),

    /**
     * A Customer's lookup did not prove anything: the Confirmation Code is wrong, the phone number
     * does not match the appointment that code names, or both.
     *
     * <p>One code for every one of those, for the reason {@link #INVALID_CREDENTIALS} gives: a
     * response that distinguished "no such code" from "right code, wrong number" would turn the
     * lookup endpoint into an oracle for which codes exist, and eight characters of base32 is a
     * space worth searching if the answers narrow it (docs/06-security.md §6).
     */
    INVALID_CONFIRMATION_CODE(HttpStatus.UNAUTHORIZED, "Invalid confirmation code"),

    /**
     * A Manage Link token authorises nothing — malformed, tampered, expired, or signed with another
     * server's secret.
     *
     * <p>Indistinguishable by design, exactly as {@code ManageTokenService.verify} returns them.
     * The token itself is never echoed back in the detail: it is a capability, and an error message
     * is one of the places a capability leaks.
     */
    MANAGE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Manage link is not valid"),

    /** A rate limit was exceeded; the response carries {@code Retry-After}. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),

    /**
     * A state-changing request arrived with a content type an HTML form can produce.
     *
     * <p>The second half of the CSRF defence in docs/06-security.md §13, and the only half that
     * applies to a request with no body — Spring's own converter negotiation rejects a form post at
     * an endpoint with a {@code @RequestBody} and has nothing to reject at one without.
     * {@link dev.reception.common.web.JsonOnlyWriteFilter} is what produces it.
     */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type"),

    /**
     * The Receptionist could not answer: the provider timed out, returned a 5xx, or no API key is
     * configured at all.
     *
     * <p>One code for all of those, because the client's response to every one is identical — show
     * the Classic Flow. The conversation is not closed and remains resumable, so a customer who
     * retries after a transient outage carries on where they were
     * (docs/05-ai-architecture.md §8).
     */
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Receptionist unavailable"),

    /**
     * A ceiling was reached rather than something going wrong: the conversation's message limit, or
     * the business's daily cost cap.
     *
     * <p>Distinct from {@link #AI_UNAVAILABLE} because it is not transient and retrying will not
     * help. The client says so and offers the Classic Flow, which can still do everything the
     * Receptionist could.
     */
    AI_LIMIT_REACHED(HttpStatus.CONFLICT, "Receptionist limit reached"),

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
