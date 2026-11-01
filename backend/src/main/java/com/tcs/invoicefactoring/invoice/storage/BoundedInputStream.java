package com.tcs.invoicefactoring.invoice.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * FilterInputStream that terminates ingestion immediately if bytes read exceed maxBytes,
 * protecting memory and disk from oversized uploads.
 */
public class BoundedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long bytesRead = 0;

    public BoundedInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        if (bytesRead >= maxBytes) {
            throw new PayloadTooLargeException("Uploaded file exceeds the maximum allowed limit of " + maxBytes + " bytes");
        }
        int b = super.read();
        if (b != -1) {
            bytesRead++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (bytesRead >= maxBytes) {
            throw new PayloadTooLargeException("Uploaded file exceeds the maximum allowed limit of " + maxBytes + " bytes");
        }
        long maxToRead = Math.min(len, maxBytes - bytesRead + 1);
        int read = super.read(b, off, (int) maxToRead);
        if (read != -1) {
            bytesRead += read;
            if (bytesRead > maxBytes) {
                throw new PayloadTooLargeException("Uploaded file exceeds the maximum allowed limit of " + maxBytes + " bytes");
            }
        }
        return read;
    }

    public long getBytesRead() {
        return bytesRead;
    }
}
