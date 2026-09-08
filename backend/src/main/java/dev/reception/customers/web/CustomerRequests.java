package dev.reception.customers.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for {@code /customers/*}.
 *
 * <p>There is no create request. A Customer comes into existence by booking, never by being entered
 * into a list — which is what keeps {@code (business_id, phone)} an identity rather than a field
 * somebody can fill in twice.
 */
public final class CustomerRequests {

    private CustomerRequests() {}

    /**
     * Correcting a name or an email.
     *
     * <p><strong>No phone.</strong> It is half the identity key: changing it would either collide
     * with another Customer or silently move one person's history onto a number that belongs to
     * somebody else. Booking under the right number creates the right Customer, which is the
     * supported fix and the one that leaves both histories intact.
     *
     * <p>{@code @Size(min = 1)} rather than {@code @NotBlank} gives exactly the rule wanted — absent
     * is fine, blank is not — which {@code @NotBlank} would get wrong by rejecting the common case.
     */
    public record PatchCustomer(
            @Size(min = 1, max = 120, message = "Names can be at most 120 characters.") String fullName,
            @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email) {}
}
