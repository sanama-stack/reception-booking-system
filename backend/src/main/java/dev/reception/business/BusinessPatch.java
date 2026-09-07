package dev.reception.business;

/**
 * A partial update to a Business, with every field nullable because every field is optional.
 *
 * <p>Separate from the request DTO in {@code business.web} so the application service is not
 * written against a shape Jackson chose. The mapping between them is one constructor call, and the
 * cost of that indirection buys a domain that does not change when the wire format does.
 *
 * <p>Semantics are documented on {@link Business#apply}: {@code null} leaves a field alone, a blank
 * string clears an optional one, and anything else sets it. There is deliberately no
 * {@code businessId} here — the tenant is derived, never patched.
 */
public record BusinessPatch(
        String name,
        String slug,
        String timezone,
        String currency,
        String description,
        String addressLine,
        String city,
        String country,
        String phone,
        String email,
        String website,
        Integer slotIntervalMinutes,
        Integer minLeadTimeMinutes,
        Integer maxAdvanceDays,
        Integer cancellationWindowHours,
        String cancellationPolicy,
        Boolean aiEnabled,
        String aiAdditionalInfo,
        Integer aiDailyCostCapCents) {}
