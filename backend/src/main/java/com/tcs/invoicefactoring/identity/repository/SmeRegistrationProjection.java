package com.tcs.invoicefactoring.identity.repository;

import java.util.UUID;

/**
 * Interface-based projection for SME registration results returned by
 * the SECURITY DEFINER function auth_register_sme.
 */
public interface SmeRegistrationProjection {
    UUID getUserId();
    UUID getOrgId();
    String getRole();
}
