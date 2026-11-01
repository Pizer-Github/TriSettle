package com.tcs.invoicefactoring.invoice.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Service managing persistent document storage, size enforcement, checksums, and atomic file movements.
 */
@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);
    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final byte[] PDF_MAGIC_BYTES = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-

    private final Path rootDir;
    private final Path stagingDir;
    private final Path storageDir;

    public DocumentStorageService(@Value("${app.storage.root:/var/app/documents}") String storageRootPath) {
        this.rootDir = Paths.get(storageRootPath).toAbsolutePath().normalize();
        this.stagingDir = this.rootDir.resolve("staging");
        this.storageDir = this.rootDir.resolve("storage");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(stagingDir);
            Files.createDirectories(storageDir);
            log.info("Document storage initialized at [{}] (staging: [{}], storage: [{}])", rootDir, stagingDir, storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize document storage directories at " + rootDir, e);
        }
    }

    /**
     * Ingests a document stream: bounds size, computes SHA-256, verifies PDF magic bytes,
     * atomically moves to permanent storage within the same volume, and registers transaction synchronization.
     */
    public StoredDocumentInfo storeDocument(UUID smeOrgId, UUID invoiceId, UUID documentUuid, InputStream inputStream, String originalFilename) {
        Path stagingFile = stagingDir.resolve(UUID.randomUUID() + ".tmp");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }

        long fileSizeBytes;
        try (BoundedInputStream boundedStream = new BoundedInputStream(inputStream, MAX_FILE_SIZE_BYTES);
             DigestInputStream digestStream = new DigestInputStream(boundedStream, md);
             OutputStream out = Files.newOutputStream(stagingFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            byte[] buffer = new byte[8192];
            int read;
            while ((read = digestStream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            fileSizeBytes = boundedStream.getBytesRead();
        } catch (PayloadTooLargeException e) {
            deleteQuietly(stagingFile);
            throw e;
        } catch (IOException e) {
            deleteQuietly(stagingFile);
            throw new IllegalStateException("Failed to stream upload to staging file", e);
        }

        if (fileSizeBytes == 0) {
            deleteQuietly(stagingFile);
            throw new InvalidDocumentException("Uploaded file cannot be empty");
        }

        // Verify PDF magic bytes (%PDF-)
        verifyPdfMagicBytes(stagingFile);

        String sha256 = HexFormat.of().formatHex(md.digest());

        // Resolve destination on the exact same volume
        Path targetDir = storageDir.resolve(smeOrgId.toString()).resolve(invoiceId.toString());
        Path targetFile = targetDir.resolve(documentUuid.toString() + ".pdf");
        String relativeStoragePath = "storage/" + smeOrgId + "/" + invoiceId + "/" + documentUuid + ".pdf";

        try {
            Files.createDirectories(targetDir);
            Files.move(stagingFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(stagingFile);
            throw new IllegalStateException("Failed to move document to permanent storage", e);
        }

        // Register transaction synchronization for reliable post-commit/rollback cleanup
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        log.warn("Transaction rolled back: deleting permanent file [{}]", targetFile);
                        deleteQuietly(targetFile);
                    }
                    deleteQuietly(stagingFile);
                }
            });
        }

        return new StoredDocumentInfo(targetFile, relativeStoragePath, fileSizeBytes, sha256, "application/pdf");
    }

    /**
     * Opens an input stream for downloading an authorized document.
     */
    public InputStream getDocumentStream(String relativeStoragePath) {
        Path targetFile = rootDir.resolve(relativeStoragePath).normalize();
        if (!targetFile.startsWith(rootDir)) {
            throw new SecurityException("Path traversal attempt detected: " + relativeStoragePath);
        }
        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            throw new IllegalStateException("Document file not found on storage volume: " + relativeStoragePath);
        }
        try {
            return Files.newInputStream(targetFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open document file stream", e);
        }
    }

    /**
     * Verifies that the file begins with %PDF- (0x25, 0x50, 0x44, 0x46, 0x2D).
     */
    private void verifyPdfMagicBytes(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = new byte[PDF_MAGIC_BYTES.length];
            int read = in.read(header);
            if (read < PDF_MAGIC_BYTES.length) {
                deleteQuietly(path);
                throw new InvalidDocumentException("File is too small to be a valid PDF document");
            }
            for (int i = 0; i < PDF_MAGIC_BYTES.length; i++) {
                if (header[i] != PDF_MAGIC_BYTES[i]) {
                    deleteQuietly(path);
                    throw new InvalidDocumentException("File is not a valid PDF. Header must begin with %PDF-");
                }
            }
        } catch (IOException e) {
            deleteQuietly(path);
            throw new IllegalStateException("Failed to inspect file magic bytes", e);
        }
    }

    /**
     * Scheduled cleaner for orphaned staging files older than 1 hour.
     */
    @Scheduled(fixedDelay = 3600000)
    public void cleanupStagingFiles() {
        if (!Files.exists(stagingDir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        try (var stream = Files.list(stagingDir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        deleteQuietly(file);
                        log.info("Purged stale staging file [{}]", file.getFileName());
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Failed to list staging directory during cleanup", e);
        }
    }

    public void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete file [{}]: {}", path, e.getMessage());
        }
    }
}
