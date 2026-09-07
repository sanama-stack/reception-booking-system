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
            @NotBlank(message = "Enter your email address.")
                    @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email,
            /*
             * Minimum ten characters and no composition rules. Requiring a symbol and a digit
             * reduces entropy in practice — it pushes people to "Password1!" — so length is the
             * only rule (docs/01-prd.md FR-1).
             */
            @NotBlank(message = "Choose a password.")
                    @Size(min = 10, max = 200, message = "Use at least 10 characters.")
                    String password,
            @NotBlank(message = "Enter your name.")
                    @Size(max = 120, message = "Names can be at most 120 characters.")
                    String fullName,
            @NotBlank(message = "Enter your business name.")
                    @Size(max = 120, message = "Business names can be at most 120 characters.")
                    String businessName) {}

    /**
     * Login validates presence and nothing else. Applying the registration rules here would tell an
     * attacker which passwords are impossible, and would lock out any account whose password
     * predates a rule change.
     */
    public record Login(
            @NotBlank(message = "Enter your email address.") @Size(max = 254, message = "That email address is too long.")
                    String email,
            @NotBlank(message = "Enter your password.") @Size(max = 200, message = "That password is too long.")
                    String password) {}
}
