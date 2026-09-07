package dev.reception.auth;

/**
 * The role a {@link Membership} carries.
 *
 * <p>MVP ships {@code OWNER} as the only working login. {@code ADMIN} is treated as {@code OWNER};
 * {@code STAFF} exists in the model but has no login, so adding staff sign-in later is a feature
 * rather than a migration (ADR-0006, docs/07-mvp-scope.md).
 */
public enum Role {
    OWNER,
    ADMIN,
    STAFF;

    /** Spring Security's convention: {@code hasRole('OWNER')} matches the authority {@code ROLE_OWNER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
