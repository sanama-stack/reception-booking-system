package dev.reception.common.error;

import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The database refusing a write, turned into the domain's own refusal.
 *
 * <p>Two failures reach application code as a {@code RuntimeException} and are nevertheless
 * <strong>answers rather than defects</strong>: the exclusion constraint deciding a race (ADR-0002)
 * and the {@code @Version} check deciding a concurrent edit. Every other integrity failure is a
 * defect and must stay one.
 *
 * <p><strong>Why this is a class and not two lines in a handler.</strong> It was two lines in
 * {@code GlobalExceptionHandler}, which is the HTTP edge — so the Receptionist, which reaches the
 * same services through {@code ToolRegistry} and never through a controller, got neither
 * translation. A Customer who lost a race was told <em>"Something went wrong on my end"</em>, which
 * is untrue in the specific way that matters: nothing went wrong, the time went. Two edges asking
 * the same question need one place that answers it, or the second edge answers it differently and
 * nobody notices for eleven phases.
 *
 * <p><strong>Keyed on the constraint name, never on the exception type alone.</strong> Answering
 * "slot unavailable" to any {@link DataIntegrityViolationException} would tell a caller their time
 * was taken when the real cause was a null in a column nobody noticed — and the defect would then
 * be invisible, because the response looked like an ordinary race. An unrecognised violation
 * returns {@link Optional#empty()} so that each edge can fail the way it fails: a logged 500 over
 * HTTP, a logged {@code TOOL_ERROR} to the model.
 */
public final class PersistenceRefusal {

    /** The exclusion constraint from {@code V5__customers_and_appointments.sql}. */
    private static final String APPOINTMENT_OVERLAP_CONSTRAINT = "appointments_no_overlap";

    private PersistenceRefusal() {}

    /**
     * The domain refusal this failure means, or empty if it does not mean one.
     *
     * <p>The messages are the ones a person reads. They are written to be true on both surfaces,
     * because both surfaces now use them — a sentence naming a screen ("reload the page") is a
     * sentence the Receptionist would say to somebody holding a telephone.
     *
     * @param failure the exception as application code caught it, cause chain intact
     */
    public static Optional<ApiException> of(Throwable failure) {
        if (failure instanceof DataIntegrityViolationException
                && namesConstraint(failure, APPOINTMENT_OVERLAP_CONSTRAINT)) {
            return Optional.of(new ApiException(
                    ErrorCode.SLOT_UNAVAILABLE, "That time was booked while you were deciding. Choose another."));
        }
        if (failure instanceof OptimisticLockingFailureException) {
            return Optional.of(new ApiException(
                    ErrorCode.VERSION_CONFLICT,
                    "Someone else changed this appointment a moment ago. Check it again and try once more."));
        }
        return Optional.empty();
    }

    /**
     * Whether this violation is the named constraint.
     *
     * <p>The name reaches us through the driver's message rather than through a typed field:
     * Spring's {@code DataIntegrityViolationException} does not carry one, and Hibernate's
     * {@code ConstraintViolationException} only sometimes parses it out. Searching the whole cause
     * chain's messages is the reliable version, and it is why the constraint name is a constant
     * here — renaming it in the migration without changing this constant would silently turn every
     * lost race into a 500 over HTTP and into "something went wrong" on the phone.
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
}
