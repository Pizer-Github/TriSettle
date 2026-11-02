package com.tcs.invoicefactoring.invoice.confirmation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "confirmation_attempt_throttles")
public class ConfirmationAttemptThrottle {

    @Id
    @Column(name = "client_ip", nullable = false, length = 45)
    private String clientIp;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "throttled_until")
    private Instant throttledUntil;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    public ConfirmationAttemptThrottle() {
        this.failedAttempts = 0;
        this.lastAttemptAt = Instant.now();
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getThrottledUntil() {
        return throttledUntil;
    }

    public void setThrottledUntil(Instant throttledUntil) {
        this.throttledUntil = throttledUntil;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }
}
