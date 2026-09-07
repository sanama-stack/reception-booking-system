package dev.reception.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for {@code /auth/*}.
 *
 * <p>Bean Validation covers shape only — length, format, presence. Anything that needs to consult
 * the database or a business rule belongs in the application service (docs/06-security.md §7).
 */
public final class AuthRequests {

    private AuthRequests() {}

    public record Register(
            @NotBlank @Email @Size(max = 254) String email,
            /*
             * Minimum ten characters and no composition rules. Requiring a symbol and a digit
             * reduces entropy in practice — it pushes people to "Password1!" — so length is the
             * only rule (docs/01-prd.md FR-1).
             */
            @NotBlank @Size(min = 10, max = 200) String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 120) String businessName) {}

    /**
     * Login validates presence and nothing else. Applying the registration rules here would tell an
     * attacker which passwords are impossible, and would lock out any account whose password
     * predates a rule change.
     */
    public record Login(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {}
}
