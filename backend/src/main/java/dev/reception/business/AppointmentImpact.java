package dev.reception.business;

import java.time.Instant;
import java.util.UUID;

/**
 * How many Appointments a proposed Business Closure would cover.
 *
 * <p>Creating a closure over existing appointments is allowed, and the system does <strong>not</strong>
 * auto-cancel them — it reports the count so the owner can cancel them deliberately
 * (docs/01-prd.md FR-2). Silently cancelling a customer's appointment because an owner blocked out
 * a week is the kind of helpfulness that loses a business its customers.
 *
 * <p>Appointments arrive in phase 06; this port is what lets the closure response carry its final
 * shape from phase 03. See {@link CatalogReadiness} for the same seam and the same reasoning.
 */
public interface AppointmentImpact {

    /**
     * @param startsAt inclusive
     * @param endsAt exclusive
     * @return the number of this business's appointments that overlap the range
     */
    long countWithin(UUID businessId, Instant startsAt, Instant endsAt);
}
