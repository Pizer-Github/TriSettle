package com.tcs.invoicefactoring.invoice.confirmation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tcs.invoicefactoring.invoice.confirmation.dto.EvidenceRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Authoritative server-side evidence serialization service conforming to RFC 8785 (JSON Canonicalization Scheme).
 * Constructs canonical JSON representation from locked database records and trusted authentication context.
 */
@Service
public class EvidenceSerializationService {

    private final ObjectMapper canonicalMapper;

    public EvidenceSerializationService() {
        this.canonicalMapper = new ObjectMapper();
        this.canonicalMapper.findAndRegisterModules();
        this.canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * Builds the deterministic canonical RFC 8785 JSON representation of the confirmation evidence.
     */
    public String buildCanonicalEvidence(
            String action,
            String authChannel,
            String buyerGstin,
            UUID buyerOrgId,
            String claimedEmail,
            Instant confirmationTimestamp,
            String disputeCategory,
            String documentSha256,
            LocalDate dueDate,
            String identityAssurance,
            LocalDate invoiceDate,
            UUID invoiceId,
            String invoiceNumber,
            String notes,
            UUID portalUserId,
            UUID requestId,
            UUID smeOrgId,
            BigDecimal taxAmount,
            BigDecimal taxableAmount,
            BigDecimal totalAmount
    ) {
        // TreeMap guarantees Unicode code point lexicographical ordering of keys
        Map<String, Object> map = new TreeMap<>();
        map.put("action", action);
        map.put("auth_channel", authChannel);
        map.put("buyer_gstin", buyerGstin);
        map.put("buyer_org_id", buyerOrgId != null ? buyerOrgId.toString() : null);
        map.put("claimed_email", claimedEmail);
        map.put("confirmation_timestamp", confirmationTimestamp.toString());
        map.put("dispute_category", disputeCategory);
        map.put("document_sha256", documentSha256);
        map.put("due_date", dueDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        map.put("identity_assurance", identityAssurance);
        map.put("invoice_date", invoiceDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        map.put("invoice_id", invoiceId.toString());
        map.put("invoice_number", invoiceNumber);
        map.put("notes", notes);
        map.put("portal_user_id", portalUserId != null ? portalUserId.toString() : null);
        map.put("request_id", requestId.toString());
        map.put("sme_org_id", smeOrgId.toString());
        map.put("tax_amount", taxAmount.setScale(2).toPlainString());
        map.put("taxable_amount", taxableAmount.setScale(2).toPlainString());
        map.put("total_amount", totalAmount.setScale(2).toPlainString());

        try {
            return canonicalMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize canonical evidence", e);
        }
    }

    /**
     * Computes the SHA-256 digest of the canonical evidence UTF-8 bytes.
     */
    public String computeSha256(String canonicalText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
