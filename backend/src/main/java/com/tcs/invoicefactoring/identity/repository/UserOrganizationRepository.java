package com.tcs.invoicefactoring.identity.repository;

import com.tcs.invoicefactoring.identity.domain.UserOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link UserOrganization} (user-to-org membership and role).
 */
@Repository
public interface UserOrganizationRepository extends JpaRepository<UserOrganization, UUID> {

    /**
     * Fetch the active membership record for a specific user+org pair.
     * Used by AuthService to verify org membership during login —
     * this is the TRUSTED source for tenant_id and role, not the JWT.
     */
    @Query("""
            SELECT uo FROM UserOrganization uo
            WHERE uo.user.id = :userId
              AND uo.organization.id = :orgId
              AND uo.active = true
            """)
    Optional<UserOrganization> findActiveMembership(
            @Param("userId") UUID userId,
            @Param("orgId") UUID orgId
    );

    /**
     * Find first active membership for a user (used when user belongs to exactly one org).
     */
    @Query("""
            SELECT uo FROM UserOrganization uo
            WHERE uo.user.id = :userId
              AND uo.active = true
            ORDER BY uo.createdAt ASC
            """)
    Optional<UserOrganization> findFirstActiveMembership(@Param("userId") UUID userId);
}
