package com.tcs.invoicefactoring.invoice.confirmation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "buyer_confirmation_responses")
public class BuyerConfirmationResponse {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "request_id", nullable = false, updatable = false, unique = true)
    private UUID requestId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "sme_org_id", nullable = false, updatable = false)
    private UUID smeOrgId;

    @Column(name = "action", nullable = false, updatable = false, length = 30)
    private String action;

    @Column(name = "dispute_category", updatable = false, length = 50)
    private String disputeCategory;

    @Column(name = "notes", updatable = false, columnDefinition = "TEXT")
    private String notes;

    @Column(name = "auth_channel", nullable = false, updatable = false, length = 30)
    private String authChannel;

    @Column(name = "portal_user_id", updatable = false)
    private UUID portalUserId;

    @Column(name = "buyer_org_id", updatable = false)
    private UUID buyerOrgId;

    @Column(name = "claimed_email", updatable = false)
    private String claimedEmail;

    @Column(name = "identity_assurance", nullable = false, updatable = false, length = 50)
    private String identityAssurance;

    @Column(name = "client_ip", nullable = false, updatable = false, length = 45)
    private String clientIp;

    @Column(name = "user_agent_hash", nullable = false, updatable = false, length = 64)
    private String userAgentHash;

    @Column(name = "evidence_schema", nullable = false, updatable = false, length = 30)
    private String evidenceSchema;

    @Column(name = "canonical_evidence_text", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String canonicalEvidenceText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String evidencePayload;

    @Column(name = "evidence_sha256", nullable = false, updatable = false, length = 64)
    private String evidenceSha256;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    public BuyerConfirmationResponse() {
        this.id = UUID.randomUUID();
        this.evidenceSchema = "BUYER_CONFIRMATION_V1";
        this.confirmedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDisputeCategory() {
        return disputeCategory;
    }

    public void setDisputeCategory(String disputeCategory) {
        this.disputeCategory = disputeCategory;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getAuthChannel() {
        return authChannel;
    }

    public void setAuthChannel(String authChannel) {
        this.authChannel = authChannel;
    }

    public UUID getPortalUserId() {
        return portalUserId;
    }

    public void setPortalUserId(UUID portalUserId) {
        this.portalUserId = portalUserId;
    }

    public UUID getBuyerOrgId() {
        return buyerOrgId;
    }

    public void setBuyerOrgId(UUID buyerOrgId) {
        this.buyerOrgId = buyerOrgId;
    }

    public String getClaimedEmail() {
        return claimedEmail;
    }

    public void setClaimedEmail(String claimedEmail) {
        this.claimedEmail = claimedEmail;
    }

    public String getIdentityAssurance() {
        return identityAssurance;
    }

    public void setIdentityAssurance(String identityAssurance) {
        this.identityAssurance = identityAssurance;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgentHash() {
        return userAgentHash;
    }

    public void setUserAgentHash(String userAgentHash) {
        this.userAgentHash = userAgentHash;
    }

    public String getEvidenceSchema() {
        return evidenceSchema;
    }

    public void setEvidenceSchema(String evidenceSchema) {
        this.evidenceSchema = evidenceSchema;
    }

    public String getCanonicalEvidenceText() {
        return canonicalEvidenceText;
    }

    public void setCanonicalEvidenceText(String canonicalEvidenceText) {
        this.canonicalEvidenceText = canonicalEvidenceText;
    }

    public String getEvidencePayload() {
        return evidencePayload;
    }

    public void setEvidencePayload(String evidencePayload) {
        this.evidencePayload = evidencePayload;
    }

    public String getEvidenceSha256() {
        return evidenceSha256;
    }

    public void setEvidenceSha256(String evidenceSha256) {
        this.evidenceSha256 = evidenceSha256;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
