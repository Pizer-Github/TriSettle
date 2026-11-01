package com.tcs.invoicefactoring.identity.repository;

import com.tcs.invoicefactoring.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 * Only the identity module accesses this repository directly.
 * Other modules must go through IdentityService (module boundary).
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
