package dev.reception.customers.web;

import dev.reception.appointments.AppointmentQueryService;
import dev.reception.customers.Customer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * Response bodies for {@code /customers/*}.
 *
 * <p>Contact details are present because this is the dashboard: the business took the booking and
 * needs to reach the person. No public response reuses these records, and none may — a Customer's
 * phone number is the thing a public endpoint must never hand out (docs/06-security.md).
 */
public final class CustomerResponses {

    private CustomerResponses() {}

    /**
     * @param totalAppointments every booking in any status, and {@code lastAppointmentAt} the most
     *     recent of them in either direction. Both come from one grouped query over the page rather
     *     than a count per row
     */
    public record CustomerDetail(
            UUID id,
            String fullName,
            String phone,
            String email,
            long totalAppointments,
            Instant lastAppointmentAt,
            Instant createdAt) {

        static CustomerDetail of(Customer customer, AppointmentQueryService.CustomerActivity activity) {
            return new CustomerDetail(
                    customer.getId(),
                    customer.fullName(),
                    customer.phone(),
                    customer.email(),
                    activity == null ? 0 : activity.totalAppointments(),
                    activity == null ? null : activity.lastAppointmentAt(),
                    customer.createdAt());
        }
    }

    /** A page of Customers. Pagination fields are spelled out — see {@code AppointmentResponses}. */
    public record CustomerPage(
            List<CustomerDetail> content, int page, int size, long totalElements, int totalPages) {

        public static CustomerPage of(
                Page<Customer> found, Map<UUID, AppointmentQueryService.CustomerActivity> activity) {
            return new CustomerPage(
                    found.getContent().stream()
                            .map(customer -> CustomerDetail.of(customer, activity.get(customer.getId())))
                            .toList(),
                    found.getNumber(),
                    found.getSize(),
                    found.getTotalElements(),
                    found.getTotalPages());
        }
    }

    public record SingleCustomer(CustomerDetail customer) {

        public static SingleCustomer of(Customer customer, AppointmentQueryService.CustomerActivity activity) {
            return new SingleCustomer(CustomerDetail.of(customer, activity));
        }
    }
}
