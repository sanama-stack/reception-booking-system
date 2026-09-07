package dev.reception.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Employee is eligible to perform one Service.
 *
 * <p>This is the table the isolation backstop is built on. Both foreign keys are composite —
 * {@code (business_id, employee_id)} and {@code (business_id, service_id)} — so a row pairing one
 * Business's employee with another's service is not merely refused by application code, it is
 * unrepresentable (docs/03-data-model.md §1). {@code CrossTenantAssignmentTest} proves that by going
 * around the application entirely.
 *
 * <p>Mapped as an entity with a composite key rather than as a JPA {@code @ManyToMany}: a
 * {@code @JoinTable} writes only the two foreign-key columns, and {@code business_id} is
 * {@code NOT NULL} precisely so it cannot be omitted. The mapping that would be more convenient is
 * the one that cannot satisfy the constraint the table exists for.
 *
 * <p>No {@code updated_at}: an assignment has no mutable state. It exists or it does not, and the
 * whole set is replaced rather than edited.
 */
@Entity
@Table(name = "employee_services")
@IdClass(EmployeeServiceAssignment.Key.class)
public class EmployeeServiceAssignment {

    @Id
    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Id
    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmployeeServiceAssignment() {
        // JPA.
    }

    public EmployeeServiceAssignment(UUID businessId, UUID employeeId, UUID serviceId, Instant now) {
        this.businessId = Objects.requireNonNull(businessId, "businessId");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID businessId() {
        return businessId;
    }

    public UUID employeeId() {
        return employeeId;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /**
     * The composite identifier. {@code business_id} is deliberately <em>not</em> part of it: the key
     * is what makes a pair unique, and the tenant is a property of both parents rather than a third
     * dimension a pair could vary in.
     */
    public record Key(UUID employeeId, UUID serviceId) implements Serializable {

        /** JPA requires a no-argument constructor on an {@code @IdClass}. */
        public Key() {
            this(null, null);
        }
    }
}
