package dev.reception.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users are not tenant-owned — a user exists before any membership — so this repository is exempt
 * from the {@code findByBusinessId} shape the tenancy rule imposes elsewhere.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** The column is {@code citext}, so this lookup is case-insensitive in the database. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
