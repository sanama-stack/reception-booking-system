package dev.reception.customers.web;

import dev.reception.appointments.AppointmentQueryService;
import dev.reception.appointments.web.AppointmentResponses;
import dev.reception.business.BusinessService;
import dev.reception.customers.Customer;
import dev.reception.customers.CustomerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Customer surface (docs/04-api-overview.md §5) — who has booked, and what they booked.
 *
 * <p>There is no create and no delete. A Customer comes into existence by booking and cannot be
 * removed while an Appointment names them: the appointment history is the record a business is least
 * able to lose, and a customer row is part of it.
 *
 * <p>{@code OWNER} and {@code ADMIN} only, declared once for the class.
 */
@RestController
@RequestMapping("/customers")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class CustomerController {

    private final CustomerService customers;
    private final AppointmentQueryService appointments;
    private final BusinessService businesses;

    public CustomerController(
            CustomerService customers, AppointmentQueryService appointments, BusinessService businesses) {
        this.customers = customers;
        this.appointments = appointments;
        this.businesses = businesses;
    }

    /** @param q matches name, phone or email; blank or absent lists everyone */
    @GetMapping
    public CustomerResponses.CustomerPage list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Customer> found = customers.search(q, page, size);
        // One grouped query for the whole page, not a count per row.
        List<UUID> ids = found.getContent().stream().map(Customer::getId).toList();
        return CustomerResponses.CustomerPage.of(found, appointments.activityFor(ids));
    }

    @GetMapping("/{id}")
    public CustomerResponses.SingleCustomer read(@PathVariable UUID id) {
        Customer customer = customers.read(id);
        return CustomerResponses.SingleCustomer.of(
                customer, appointments.activityFor(List.of(id)).get(id));
    }

    @PatchMapping("/{id}")
    public CustomerResponses.SingleCustomer patch(
            @PathVariable UUID id, @Valid @RequestBody CustomerRequests.PatchCustomer request) {
        Customer patched = customers.patch(id, request.fullName(), request.email());
        return CustomerResponses.SingleCustomer.of(
                patched, appointments.activityFor(List.of(id)).get(id));
    }

    /** Newest first — the order a profile is read in, and the opposite of the appointment list. */
    @GetMapping("/{id}/appointments")
    public AppointmentResponses.AppointmentPage history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return AppointmentResponses.AppointmentPage.of(
                appointments.historyFor(id, page, size), businesses.read().timezone());
    }
}
