package dev.reception.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The single definition of which database failures are answers, asserted where it lives.
 *
 * <p>No Spring and no database: what is under test is a decision about an exception, and the two
 * surfaces that consult it — {@code GlobalExceptionHandler} and {@code ToolRegistry} — are tested
 * separately for consulting it. {@code ErrorLeakageTest} already drives both violations through
 * HTTP with exceptions shaped exactly like these.
 *
 * <p><strong>The negative cases are the point.</strong> A translation that answered
 * "slot unavailable" to every {@link DataIntegrityViolationException} would pass every positive
 * assertion here and would tell a caller their time was taken when a column was null.
 */
class PersistenceRefusalTest {

    /**
     * The shape Hibernate actually produces: the constraint is named by the driver, several causes
     * down, and never by the exception Spring hands application code. A translation that read only
     * {@code getMessage()} passes the case above and fails in production.
     */
    @Test
    @DisplayName("the exclusion constraint is SLOT_UNAVAILABLE even when only a nested cause names it")
    void the_overlap_constraint_is_a_lost_race() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "could not execute statement [insert into appointments (id,blocked_from) values (?,?)]",
                new RuntimeException(
                        "JDBC exception executing SQL",
                        new SQLException("ERROR: conflicting key value violates exclusion constraint"
                                + " \"appointments_no_overlap\"")));

        Optional<ApiException> refusal = PersistenceRefusal.of(failure);

        assertThat(refusal).isPresent();
        assertThat(refusal.get().code()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE);
        // The sentence a person reads, whether they are looking at a screen or listening to the
        // Receptionist paraphrase it. It names no constraint, no table and no column: the schema is
        // not a fact a customer is entitled to (docs/06-security.md §11).
        assertThat(refusal.get().getMessage()).isEqualTo("That time was booked while you were deciding. Choose another.");
        assertThat(refusal.get().getMessage()).doesNotContain("appointments_no_overlap");
    }

    /**
     * <strong>The counterfactual the whole class exists for.</strong> A null in a column nobody
     * noticed is a defect, and answering it as a lost race would make it invisible — the response
     * would look like an ordinary busy Tuesday.
     */
    @Test
    @DisplayName("any other integrity violation is not a refusal and stays a defect")
    void an_unmapped_constraint_is_not_a_race() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "could not execute statement [ERROR: null value in column \"name\"]",
                new SQLException("ERROR: null value in column \"name\" of relation customers"));

        assertThat(PersistenceRefusal.of(failure)).isEmpty();
    }

    @Test
    @DisplayName("a lost @Version check is VERSION_CONFLICT")
    void a_lost_version_check_is_a_concurrent_edit() {
        Optional<ApiException> refusal =
                PersistenceRefusal.of(new OptimisticLockingFailureException("Row was updated or deleted by another"
                        + " transaction [dev.reception.appointments.Appointment#...]"));

        assertThat(refusal).isPresent();
        assertThat(refusal.get().code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        // Written for both surfaces. "Reload the page" is what this said while only a browser could
        // read it, and the Receptionist says it to somebody holding a telephone.
        assertThat(refusal.get().getMessage()).doesNotContainIgnoringCase("reload");
        assertThat(refusal.get().getMessage()).doesNotContainIgnoringCase("page");
    }

    /**
     * A deadlock is neither. Postgres aborted a victim that may well have been entitled to the
     * time, and {@code DeadlockRetry} is what answers it — recording that here so a future
     * widening of this class has to argue with a test rather than with a comment (issue #7).
     */
    @Test
    @DisplayName("a deadlock victim is not a refusal: it is retried, not explained")
    void a_deadlock_is_not_a_refusal() {
        assertThat(PersistenceRefusal.of(new CannotAcquireLockException("deadlock detected")))
                .isEmpty();
    }

    @Test
    @DisplayName("an ordinary defect is not a refusal")
    void anything_else_is_a_defect() {
        assertThat(PersistenceRefusal.of(new IllegalStateException("appointments_no_overlap")))
                .isEmpty();
    }
}
