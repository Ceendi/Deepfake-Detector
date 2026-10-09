package com.deepfake.orchestrator.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Consume inside the SDK timeout/retry scope; abort incomplete bodies instead of draining them. */
final class BoundedArtifactResponseTransformer implements ResponseTransformer<GetObjectResponse, byte[]> {
    private final int maxBytes;

    BoundedArtifactResponseTransformer(int maxBytes) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Artifact byte limit must be positive and below Integer.MAX_VALUE");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public byte[] transform(GetObjectResponse response, AbortableInputStream stream) throws Exception {
        try {
            Long length = response.contentLength();
            if (length != null && length > maxBytes) throw oversized();
            var output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[Math.min(maxBytes + 1, 8192)];
            int total = 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Artifact download interrupted");
                int count = stream.read(buffer, 0, Math.min(buffer.length, maxBytes - total + 1));
                if (count == -1) break;
                if (count > maxBytes - total) throw oversized();
                output.write(buffer, 0, count);
                total += count;
            }
            if (length != null && length >= 0 && length != total) throw new IOException("Incomplete artifact body");
            return output.toByteArray();
        } catch (Exception ex) {
            stream.abort(); // close alone may drain an arbitrarily large/slow remaining body
            throw ex;
        } finally {
            stream.close();
        }
    }

    private static SdkClientException oversized() {
        return SdkClientException.create("Artifact exceeds configured byte limit");
    }
}
