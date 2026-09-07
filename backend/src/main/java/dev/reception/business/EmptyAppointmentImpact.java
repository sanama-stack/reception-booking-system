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

    @Override
    public long countFutureForService(UUID businessId, UUID serviceId) {
        return 0;
    }

    @Override
    public long countFutureForEmployee(UUID businessId, UUID employeeId) {
        return 0;
    }

    /**
     * No Service has ever been booked, so no delete is ever refused. Phase 04 writes the
     * {@code 409 SERVICE_IN_USE} test against this answer and asserts the path that is reachable
     * today — the guard itself cannot fire until appointments exist.
     */
    @Override
    public boolean everBooked(UUID businessId, UUID serviceId) {
        return false;
    }
}
