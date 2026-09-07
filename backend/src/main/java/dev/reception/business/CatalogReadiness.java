package dev.reception.business;

import java.util.UUID;

/**
 * How much of a Business's catalog is configured — the part of the onboarding checklist that lives
 * outside this package.
 *
 * <p>Services, Employees and Working Schedules arrive in phase 04, but the checklist is a phase-03
 * deliverable and its published shape includes them (docs/04-api-overview.md §5). This port is how
 * the two meet: {@link OnboardingService} derives the checklist from the answer rather than from
 * tables it would otherwise have to reach across a module boundary to read, and phase 04 supplies
 * the implementation that actually counts rows.
 *
 * <p>The alternative — omitting the fields now and adding them later — would change a published
 * response shape twice and force the dashboard checklist to be rebuilt. One interface is cheaper
 * than that, and it keeps the derivation testable across every combination today.
 */
public interface CatalogReadiness {

    /**
     * A snapshot, rather than four separate questions, so an implementation is free to answer them
     * in one query and a caller cannot see an inconsistent mixture of two.
     *
     * @param hasActiveService an active Service exists
     * @param hasActiveEmployee an active Employee exists
     * @param hasEmployeeSchedule at least one active Employee has a Working Schedule
     * @param hasBookableService an active Service has at least one active assigned Employee who has
     *     a schedule — the condition that actually determines whether anyone can book
     */
    record Snapshot(
            boolean hasActiveService,
            boolean hasActiveEmployee,
            boolean hasEmployeeSchedule,
            boolean hasBookableService) {

        public static final Snapshot NOTHING_CONFIGURED = new Snapshot(false, false, false, false);
    }

    Snapshot of(UUID businessId);
}
