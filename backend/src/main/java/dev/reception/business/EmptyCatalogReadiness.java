package dev.reception.business;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The truthful phase-03 answer: nothing is configured, because the tables that would hold it do not
 * exist yet.
 *
 * <p>TODO(phase-04): delete this class. Phase 04 adds the implementation that reads {@code services},
 * {@code employees}, {@code employee_services} and {@code working_schedules}. Leaving this one in
 * place alongside it makes the context fail to start with two candidate beans — which is the failure
 * we want, because the alternative is a checklist that silently keeps answering "no" after the
 * catalog exists.
 */
@Service
public class EmptyCatalogReadiness implements CatalogReadiness {

    @Override
    public Snapshot of(UUID businessId) {
        return Snapshot.NOTHING_CONFIGURED;
    }
}
