package dev.reception.catalog;

import dev.reception.business.CatalogReadiness;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real answer, replacing the phase-03 stub that could only say "nothing configured".
 *
 * <p>{@code EmptyCatalogReadiness} was deleted in the same commit that added this, which is what its
 * TODO asked for. Two beans implementing one interface make the context refuse to start — a loud
 * failure — while a stub left as a fallback fails quietly, by going on answering "no" after the
 * catalog exists.
 *
 * <p><strong>One statement, not four.</strong> The port hands back a {@link Snapshot} rather than
 * four separate questions precisely so an implementation can answer them from a single view of the
 * data. Four {@code exists} calls under {@code READ COMMITTED} would each see their own snapshot,
 * and the checklist could show a state that was never true — an active service and no active
 * employee, at an instant when both existed.
 */
@org.springframework.stereotype.Service
public class DatabaseCatalogReadiness implements CatalogReadiness {

    /**
     * Native rather than JPQL because it is four correlated {@code EXISTS} subqueries and no
     * entities are being loaded — mapping them would be work done to throw away.
     *
     * <p>Each clause restates one sentence from {@link Snapshot}'s documentation:
     *
     * <ul>
     *   <li>an active Service exists
     *   <li>an active Employee exists
     *   <li>at least one <em>active</em> Employee has a Working Schedule — a schedule belonging to a
     *       deactivated employee is not readiness
     *   <li>an active Service has at least one active assigned Employee who has a schedule. This is
     *       the one that actually decides whether a Customer can book, and it is the conjunction the
     *       other three only approximate.
     * </ul>
     */
    private static final String READINESS_SQL =
            """
            select
              exists (select 1 from services s
                       where s.business_id = :businessId and s.active)                as has_active_service,
              exists (select 1 from employees e
                       where e.business_id = :businessId and e.active)                as has_active_employee,
              exists (select 1 from employee_schedules sch
                         join employees e on e.id = sch.employee_id
                                         and e.business_id = sch.business_id
                        where sch.business_id = :businessId and e.active)             as has_employee_schedule,
              exists (select 1 from services s
                         join employee_services es on es.service_id = s.id
                                                  and es.business_id = s.business_id
                         join employees e on e.id = es.employee_id
                                         and e.business_id = es.business_id
                        where s.business_id = :businessId
                          and s.active
                          and e.active
                          and exists (select 1 from employee_schedules sch
                                       where sch.business_id = e.business_id
                                         and sch.employee_id = e.id))                 as has_bookable_service
            """;

    private final EntityManager entityManager;

    public DatabaseCatalogReadiness(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot of(UUID businessId) {
        Object[] row = (Object[]) entityManager
                .createNativeQuery(READINESS_SQL)
                .setParameter("businessId", businessId)
                .getSingleResult();

        return new Snapshot((Boolean) row[0], (Boolean) row[1], (Boolean) row[2], (Boolean) row[3]);
    }
}
