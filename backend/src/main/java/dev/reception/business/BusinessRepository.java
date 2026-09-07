package dev.reception.business;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The tenant root itself, so this is the one repository whose {@code findById} is legitimate: it is
 * the lookup that <em>establishes</em> a tenant rather than one performed inside one.
 */
public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Optional<Business> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
