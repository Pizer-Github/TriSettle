package com.tcs.invoicefactoring.invoice.confirmation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "buyer_confirmation_requests")
public class BuyerConfirmationRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "sme_org_id", nullable = false, updatable = false)
    private UUID smeOrgId;

    @Column(name = "buyer_org_id")
    private UUID buyerOrgId;

    @Column(name = "buyer_gstin", nullable = false, length = 15)
    private String buyerGstin;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(name = "secure_token_hash", nullable = false, length = 64, unique = true)
    private String secureTokenHash;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "exchange_attempts", nullable = false)
    private int exchangeAttempts;

    @Column(name = "throttled_until")
    private Instant throttledUntil;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BuyerConfirmationRequest() {
        this.id = UUID.randomUUID();
        this.status = "PENDING";
        this.exchangeAttempts = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(UUID invoiceId) {
        this.invoiceId = invoiceId;
    }

    public UUID getSmeOrgId() {
        return smeOrgId;
    }

    public void setSmeOrgId(UUID smeOrgId) {
        this.smeOrgId = smeOrgId;
    }

    public UUID getBuyerOrgId() {
        return buyerOrgId;
    }

    public void setBuyerOrgId(UUID buyerOrgId) {
        this.buyerOrgId = buyerOrgId;
    }

    public String getBuyerGstin() {
        return buyerGstin;
    }

    public void setBuyerGstin(String buyerGstin) {
        this.buyerGstin = buyerGstin;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    public String getSecureTokenHash() {
        return secureTokenHash;
    }

    public void setSecureTokenHash(String secureTokenHash) {
        this.secureTokenHash = secureTokenHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getExchangeAttempts() {
        return exchangeAttempts;
    }

    public void setExchangeAttempts(int exchangeAttempts) {
        this.exchangeAttempts = exchangeAttempts;
    }

    public Instant getThrottledUntil() {
        return throttledUntil;
    }

    public void setThrottledUntil(Instant throttledUntil) {
        this.throttledUntil = throttledUntil;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
