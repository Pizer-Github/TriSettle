package com.tcs.invoicefactoring.common.outbox.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEnvelopeEncryptionServiceTest {

    private NotificationEnvelopeEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new NotificationEnvelopeEncryptionService("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @Test
    void encryptAndDecrypt_successWithMatchingAad() {
        UUID invoiceId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String payload = "{\"recipient\":\"buyer@example.com\",\"amount\":\"50000.00\"}";

        String ciphertext = service.encrypt(payload, "INVOICE", invoiceId, tenantId, 1);
        assertThat(ciphertext).isNotBlank().isNotEqualTo(payload);

        String decrypted = service.decrypt(ciphertext, "INVOICE", invoiceId, tenantId, 1);
        assertThat(decrypted).isEqualTo(payload);
    }

    @Test
    void decrypt_failsWhenAadTampered() {
        UUID invoiceId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String payload = "secret-token-payload";

        String ciphertext = service.encrypt(payload, "INVOICE", invoiceId, tenantId, 1);

        // Attempt decrypt with different tenant ID (AAD mismatch)
        UUID otherTenantId = UUID.randomUUID();
        assertThatThrownBy(() -> service.decrypt(ciphertext, "INVOICE", invoiceId, otherTenantId, 1))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void keyRotation_supportsMultipleKeyVersions() throws NoSuchAlgorithmException {
        UUID invoiceId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String payload = "key-rotation-test-payload";

        // Encrypt with Key V1
        String ciphertextV1 = service.encrypt(payload, "INVOICE", invoiceId, tenantId, 1);

        // Generate and register Key V2
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey keyV2 = keyGen.generateKey();
        service.registerKey(2, keyV2);
        service.setActiveKeyVersion(2);

        // Encrypt with Key V2
        String ciphertextV2 = service.encrypt(payload, "INVOICE", invoiceId, tenantId, 2);

        // Verify both can be decrypted with their respective key versions
        assertThat(service.decrypt(ciphertextV1, "INVOICE", invoiceId, tenantId, 1)).isEqualTo(payload);
        assertThat(service.decrypt(ciphertextV2, "INVOICE", invoiceId, tenantId, 2)).isEqualTo(payload);
    }
}
