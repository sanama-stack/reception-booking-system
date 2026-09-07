package dev.reception.catalog.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies for {@code /services/*}.
 *
 * <p>Bean Validation covers presence and length only. The numeric rules — the five-minute grid, the
 * buffer ceiling, the price scale — live in {@code ServiceValidation}, because they are consequences
 * of how slots are generated rather than arbitrary bounds, and because one validator has to serve
 * both a create and a partial update.
 *
 * <p>None of these carries a {@code businessId} or a {@code currency}: the tenant is derived from
 * the Membership, and the currency is the Business's.
 */
public final class ServiceRequests {

    private ServiceRequests() {}

    /** Buffers are optional and default to zero, which is what most services need. */
    public record CreateService(
            @NotBlank(message = "Enter a name for this service.")
                    @Size(max = 120, message = "Service names can be at most 120 characters.")
                    String name,
            @Size(max = 5000, message = "The description can be at most 5000 characters.") String description,
            @NotNull(message = "Enter how long this takes.") Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            @NotNull(message = "Enter a price. Use 0 if this service is free.") BigDecimal price) {}

    /**
     * A partial update. {@code null} means "leave this alone"; a blank description clears it.
     *
     * <p>{@code @Size(min = 1)} rather than {@code @NotBlank} on the name gives exactly the rule
     * wanted — absent is fine, blank is not — which {@code @NotBlank} would get wrong by rejecting
     * the absent case, the common one.
     */
    public record PatchService(
            @Size(min = 1, max = 120, message = "Service names can be at most 120 characters.") String name,
            @Size(max = 5000, message = "The description can be at most 5000 characters.") String description,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            BigDecimal price) {}

    /**
     * The whole eligible-employee set, replacing whatever is there.
     *
     * <p>An empty list is legitimate and means nobody can perform this service yet. It is not the
     * same as omitting the field, which is a malformed request — the same distinction
     * {@code ReplaceHours} draws.
     */
    public record ReplaceEmployees(
            @NotNull(message = "Send the whole list, even if it is empty.")
                    @Size(max = 500, message = "That is more employees than a business can hold.")
                    List<UUID> employeeIds) {}
}
