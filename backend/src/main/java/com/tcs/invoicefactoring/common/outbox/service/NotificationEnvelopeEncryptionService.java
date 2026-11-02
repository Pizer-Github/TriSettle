package com.tcs.invoicefactoring.common.outbox.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-security envelope encryption service utilizing AES-256-GCM with Additional Authenticated Data (AAD).
 * Protects PII and single-use bearer tokens in the durable outbox against storage breaches.
 */
@Service
public class NotificationEnvelopeEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Integer, SecretKey> keyStore = new ConcurrentHashMap<>();
    private volatile int activeKeyVersion = 1;

    public NotificationEnvelopeEncryptionService(
            @Value("${app.outbox.encryption-key-v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef}") String defaultHexKey
    ) {
        byte[] keyBytes = HexFormat.of().parseHex(defaultHexKey);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        keyStore.put(1, key);
    }

    public synchronized void registerKey(int version, SecretKey key) {
        keyStore.put(version, key);
    }

    public synchronized void setActiveKeyVersion(int version) {
        if (!keyStore.containsKey(version)) {
            throw new IllegalArgumentException("Cannot set active key version: key version [" + version + "] is not registered");
        }
        this.activeKeyVersion = version;
    }

    public int getActiveKeyVersion() {
        return activeKeyVersion;
    }

    /**
     * Encrypts plaintext payload using AES-256-GCM with AAD bound to the aggregate and tenant.
     */
    public String encrypt(String plaintext, String aggregateType, UUID aggregateId, UUID tenantId, int keyVersion) {
        SecretKey key = keyStore.get(keyVersion);
        if (key == null) {
            throw new IllegalStateException("Encryption key version [" + keyVersion + "] not found in keystore");
        }

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            byte[] aad = buildAad(aggregateType, aggregateId, tenantId, keyVersion);
            cipher.updateAAD(aad);

            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertextWithTag.length);
            buffer.put(iv);
            buffer.put(ciphertextWithTag);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Envelope encryption failure", e);
        }
    }

    /**
     * Decrypts ciphertext payload, authenticating against AAD.
     */
    public String decrypt(String ciphertextBase64, String aggregateType, UUID aggregateId, UUID tenantId, int keyVersion) {
        SecretKey key = keyStore.get(keyVersion);
        if (key == null) {
            throw new IllegalStateException("Decryption key version [" + keyVersion + "] not found in keystore");
        }

        byte[] combined = Base64.getDecoder().decode(ciphertextBase64);
        if (combined.length < GCM_IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Invalid ciphertext: payload shorter than IV length");
        }

        ByteBuffer buffer = ByteBuffer.wrap(combined);
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        buffer.get(iv);

        byte[] ciphertextWithTag = new byte[buffer.remaining()];
        buffer.get(ciphertextWithTag);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            byte[] aad = buildAad(aggregateType, aggregateId, tenantId, keyVersion);
            cipher.updateAAD(aad);

            byte[] decrypted = cipher.doFinal(ciphertextWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Envelope decryption or tag authentication failure", e);
        }
    }

    private byte[] buildAad(String aggregateType, UUID aggregateId, UUID tenantId, int keyVersion) {
        String aadString = aggregateType + ":" + aggregateId + ":" + tenantId + ":" + keyVersion;
        return aadString.getBytes(StandardCharsets.UTF_8);
    }
}
