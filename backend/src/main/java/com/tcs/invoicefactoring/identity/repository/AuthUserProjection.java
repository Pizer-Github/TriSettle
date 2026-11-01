package com.tcs.invoicefactoring.identity.repository;

import java.util.UUID;

/**
 * Interface-based projection for auth lookup results returned by
 * the SECURITY DEFINER function auth_lookup_user_by_email.
 */
public interface AuthUserProjection {
    UUID getUserId();
    String getEmail();
    String getPasswordHash();
    String getFullName();
    Boolean getIsActive();
    Boolean getIsEmailVerified();
    UUID getOrgId();
    String getOrgName();
    String getOrgType();
    String getRole();
}
