package com.tcs.invoicefactoring.invoice.confirmation.repository;

import com.tcs.invoicefactoring.invoice.confirmation.domain.ConfirmationAttemptThrottle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConfirmationAttemptThrottleRepository extends JpaRepository<ConfirmationAttemptThrottle, String> {

    Optional<ConfirmationAttemptThrottle> findByClientIp(String clientIp);
}
