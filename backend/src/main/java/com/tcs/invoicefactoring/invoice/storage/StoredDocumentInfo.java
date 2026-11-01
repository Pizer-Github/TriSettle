package com.tcs.invoicefactoring.invoice.storage;

import java.nio.file.Path;

public record StoredDocumentInfo(
        Path absolutePath,
        String relativeStoragePath,
        long fileSizeBytes,
        String sha256Checksum,
        String mimeType
) {}
