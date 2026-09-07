package dev.reception.business;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The truthful phase-03 answer: no appointment can be affected, because none can yet exist.
 *
 * <p>TODO(phase-06): delete this class, and count overlapping rows in {@code appointments} whose
 * status is not already cancelled. See {@link EmptyCatalogReadiness} for why the stub is deleted
 * rather than left as a fallback.
 */
@Service
public class EmptyAppointmentImpact implements AppointmentImpact {

    @Override
    public long countWithin(UUID businessId, Instant startsAt, Instant endsAt) {
        return 0;
    }
}
