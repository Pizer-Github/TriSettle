package com.tcs.invoicefactoring.identity.repository;

import com.tcs.invoicefactoring.identity.domain.OrganizationTaxProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationTaxProfileRepository extends JpaRepository<OrganizationTaxProfile, UUID> {

    Optional<OrganizationTaxProfile> findByGstin(String gstin);

    @Query("""
            SELECT otp FROM OrganizationTaxProfile otp
            WHERE otp.gstin = :gstin
              AND otp.verified = true
              AND otp.status = 'ACTIVE'
            """)
    Optional<OrganizationTaxProfile> findActiveVerifiedByGstin(@Param("gstin") String gstin);

    @Query("""
            SELECT otp FROM OrganizationTaxProfile otp
            WHERE otp.orgId = :orgId
              AND otp.gstin = :gstin
              AND otp.verified = true
              AND otp.status = 'ACTIVE'
            """)
    Optional<OrganizationTaxProfile> findActiveVerifiedByOrgIdAndGstin(@Param("orgId") UUID orgId, @Param("gstin") String gstin);
}
