package dev.reception.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Memberships are scoped by user, not by tenant — they are what <em>establishes</em> the tenant, so
 * they cannot be looked up by one.
 */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByUserId(UUID userId);

    Optional<Membership> findByUserIdAndBusinessId(UUID userId, UUID businessId);
}
