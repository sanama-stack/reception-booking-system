package dev.reception.appointments;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The public surface's way in, and the second query in this application that is deliberately not
 * tenant-scoped.
 *
 * <p><strong>Because a Customer has no tenant until they have proved something.</strong>
 * {@code /public/appointments/*} carries no slug: a Manage Link names an Appointment, and a lookup
 * names a Confirmation Code. Which Business the request concerns is the <em>answer</em>, not a
 * premise, so it cannot be a filter on the query that finds it. Everything after this point runs
 * inside the tenant that was found — see {@code TenantAdoption}.
 *
 * <p>Separate from {@link AppointmentRepository} rather than a method on it, for the reason
 * {@code NotificationClaimRepository} is separate from {@code NotificationRepository}: a
 * {@code @TenantScoped} repository carrying one cross-tenant method teaches every future reader that
 * the rule has exceptions, and the next exception will be an accident. Here the exception is the
 * whole purpose of the type, stated in its name, and {@code TenantRepositoryShapeTest} has nothing
 * to say about it.
 *
 * <p><strong>Nothing here is authorisation.</strong> These are lookups. The proof that a caller may
 * act on what comes back is {@code PublicAppointmentAuthority}'s, and it is the only class that
 * calls this one.
 */
public interface AppointmentDirectory extends JpaRepository<Appointment, UUID> {

    /**
     * Every Appointment holding this Confirmation Code, in any Business, with the two other values
     * needed to judge the claim.
     *
     * <p>A list rather than an Optional, because the code is unique <em>within</em> a Business and
     * this query spans all of them. Two businesses issuing the same eight characters is a
     * one-in-10^12 coincidence per pair rather than an impossibility, and a query shaped as
     * {@code Optional} would throw where it should choose.
     *
     * <p>The phone is not compared here. It is stored normalised, and normalising what the caller
     * typed needs the Business's country — which is not known until this query has answered, and
     * differs per row when more than one comes back. So the country travels with each candidate and
     * the comparison happens above, once per row (docs/06-security.md §6).
     *
     * <p>A cross join rather than a JPQL association: Appointment references its Customer and its
     * Business by id, not by a mapped relation, which is what keeps the aggregate loadable without
     * dragging a Business into every read of it.
     */
    @Query(
            """
            select a.id as appointmentId,
                   a.businessId as businessId,
                   c.phone as customerPhone,
                   b.country as businessCountry
              from Appointment a, Customer c, Business b
             where a.confirmationCode = :code
               and c.id = a.customerId
               and b.id = a.businessId
             order by a.id desc
            """)
    List<ConfirmationCodeMatch> findByConfirmationCodeAcrossTenants(@Param("code") String code);

    /** One candidate for a Confirmation Code, and what is needed to test the phone against it. */
    interface ConfirmationCodeMatch {
        UUID getAppointmentId();

        UUID getBusinessId();

        String getCustomerPhone();

        String getBusinessCountry();
    }

    /**
     * Which Business an Appointment belongs to, for a caller that has proved a Manage Link.
     *
     * <p>A projection rather than the entity: the tenant has to be adopted before the appointment is
     * read properly, and loading the aggregate here would be loading it outside the tenant it
     * belongs to — exactly the read this whole design exists to prevent anyone writing by accident.
     */
    @Query("select a.businessId from Appointment a where a.id = :id")
    java.util.Optional<UUID> findBusinessIdAcrossTenants(@Param("id") UUID id);
}
