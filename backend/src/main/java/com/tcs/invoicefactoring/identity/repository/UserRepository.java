package com.tcs.invoicefactoring.identity.repository;

import com.tcs.invoicefactoring.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 * Includes controlled pre-authentication database function lookups.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Narrowly scoped authentication lookup function (SECURITY DEFINER).
     * Retrieves credentials and tenant membership for the specified email without
     * requiring general RLS bypass permissions.
     */
    @Query(value = "SELECT * FROM auth_lookup_user_by_email(:email)", nativeQuery = true)
    Optional<AuthUserProjection> findAuthUserByEmail(@Param("email") String email);

    /**
     * Controlled registration of an SME organisation, user, and membership.
     * Enforces organisation type SME and role SME at the database level.
     */
    @Query(value = "SELECT * FROM auth_register_sme(:email, :passwordHash, :fullName, :orgName)", nativeQuery = true)
    SmeRegistrationProjection registerSme(
            @Param("email") String email,
            @Param("passwordHash") String passwordHash,
            @Param("fullName") String fullName,
            @Param("orgName") String orgName
    );

    /**
     * Narrowly scoped user lookup by ID for token re-validation.
     */
    @Query(value = "SELECT user_id AS id, email, password_hash, full_name, is_active, is_email_verified, NOW() AS created_at, NOW() AS updated_at FROM auth_lookup_user_by_id(:userId)", nativeQuery = true)
    Optional<User> findActiveUserById(@Param("userId") UUID userId);
}
