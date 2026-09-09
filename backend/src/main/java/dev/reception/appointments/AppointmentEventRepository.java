package dev.reception.appointments;

import dev.reception.tenancy.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The audit trail. Written by {@link AppointmentEventRecorder} and read by the detail screen.
 *
 * <p>No update or delete method, and none may be added: an event that could be edited would not be
 * an audit trail. The table is append-only in the schema too — nothing grants a way to change a row
 * once written.
 */
@TenantScoped
public interface AppointmentEventRepository extends JpaRepository<AppointmentEvent, UUID> {

    List<AppointmentEvent> findByBusinessIdAndAppointmentIdOrderByCreatedAtAsc(UUID businessId, UUID appointmentId);
}
