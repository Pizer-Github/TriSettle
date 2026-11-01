package com.tcs.invoicefactoring.invoice.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    private DocumentStorageService storageService;
    private final UUID smeOrgId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storageService = new DocumentStorageService(tempDir.toString());
        storageService.init();
    }

    @Test
    void storeDocument_validPdf_storesFileAndComputesSha256() throws Exception {
        UUID docId = UUID.randomUUID();
        byte[] pdfContent = "%PDF-1.4 sample pdf content for testing".getBytes(StandardCharsets.UTF_8);

        StoredDocumentInfo info = storageService.storeDocument(
                smeOrgId,
                invoiceId,
                docId,
                new ByteArrayInputStream(pdfContent),
                "invoice.pdf"
        );

        assertThat(info.fileSizeBytes()).isEqualTo(pdfContent.length);
        assertThat(info.sha256Checksum()).isNotBlank().hasSize(64);
        assertThat(info.mimeType()).isEqualTo("application/pdf");
        assertThat(info.absolutePath()).exists();

        try (InputStream in = storageService.getDocumentStream(info.relativeStoragePath())) {
            byte[] readBytes = in.readAllBytes();
            assertThat(readBytes).isEqualTo(pdfContent);
        }
    }

    @Test
    void storeDocument_nonPdf_throwsInvalidDocumentException() {
        UUID docId = UUID.randomUUID();
        byte[] exeContent = "MZ invalid header not a pdf".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storageService.storeDocument(
                smeOrgId,
                invoiceId,
                docId,
                new ByteArrayInputStream(exeContent),
                "malicious.pdf"
        )).isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("must begin with %PDF-");
    }

    @Test
    void storeDocument_emptyFile_throwsInvalidDocumentException() {
        UUID docId = UUID.randomUUID();
        byte[] emptyContent = new byte[0];

        assertThatThrownBy(() -> storageService.storeDocument(
                smeOrgId,
                invoiceId,
                docId,
                new ByteArrayInputStream(emptyContent),
                "empty.pdf"
        )).isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    void getDocumentStream_pathTraversal_throwsSecurityException() {
        assertThatThrownBy(() -> storageService.getDocumentStream("../../etc/passwd"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal");
    }
}
