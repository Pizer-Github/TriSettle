package com.tcs.invoicefactoring.identity.repository;

import com.tcs.invoicefactoring.identity.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for {@link Organization} entities.
 * Only the identity module accesses this repository directly.
 */
@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
