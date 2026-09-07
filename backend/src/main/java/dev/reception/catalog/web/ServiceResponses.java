package dev.reception.catalog.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.reception.catalog.Service;
import dev.reception.catalog.ServiceCatalogService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response bodies for {@code /services/*}, written by hand rather than mapped from entities — there
 * is no path from a column into one of these that does not pass through a named field.
 */
public final class ServiceResponses {

    private ServiceResponses() {}

    /**
     * Money as a decimal string with its currency, never a float
     * (docs/04-api-overview.md §2).
     *
     * <p>{@code Shape.STRING} matters: Jackson would otherwise emit {@code 60.00} as a JSON number,
     * and every JavaScript client that touched it would turn it into a binary float on the way in.
     * The currency travels with the amount because an amount without one is not a price.
     */
    public record Money(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency) {}

    /**
     * @param employeeIds who may perform this service. Included on the list as well as the detail so
     *     a management screen can show "nobody assigned" — the most common reason a configured
     *     service still cannot be booked — without a request per row.
     */
    public record ServiceDetail(
            UUID id,
            String name,
            String description,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            Money price,
            boolean active,
            List<UUID> employeeIds,
            Instant updatedAt) {

        public static ServiceDetail of(Service service, List<UUID> employeeIds) {
            return new ServiceDetail(
                    service.getId(),
                    service.name(),
                    service.description(),
                    service.durationMinutes(),
                    service.bufferBeforeMinutes(),
                    service.bufferAfterMinutes(),
                    new Money(service.priceAmount(), service.currency()),
                    service.active(),
                    employeeIds,
                    service.updatedAt());
        }
    }

    public record ServiceList(List<ServiceDetail> services) {

        public static ServiceList of(List<Service> services, Map<UUID, List<UUID>> employeesByService) {
            return new ServiceList(services.stream()
                    .map(service ->
                            ServiceDetail.of(service, employeesByService.getOrDefault(service.getId(), List.of())))
                    .toList());
        }
    }

    /**
     * The result of a toggle, carrying what a deactivation costs.
     *
     * <p>Nothing is auto-cancelled; the count is here so the owner can decide deliberately
     * (docs/04-api-overview.md §5). It is always {@code 0} for an activation, and — until phase 06
     * — for a deactivation too.
     */
    public record BookabilityChange(ServiceDetail service, long affectedFutureAppointments) {

        public static BookabilityChange of(ServiceCatalogService.BookabilityChange change, List<UUID> employeeIds) {
            return new BookabilityChange(
                    ServiceDetail.of(change.service(), employeeIds), change.affectedFutureAppointments());
        }
    }

    /** The set as it now stands, so a client never has to assume its own submission was taken whole. */
    public record AssignedEmployees(List<UUID> employeeIds) {}
}
