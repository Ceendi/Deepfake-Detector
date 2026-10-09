package com.deepfake.orchestrator.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedArtifactResponseTransformerTest {
    @Test
    void closesSuccessfulExactlyAtLimitBody() throws Exception {
        var input = new TrackedInput(new byte[16]);
        byte[] body = transformer().transform(response(16L), input.abortable());
        assertThat(body).hasSize(16);
        assertThat(input.closed).isTrue();
        assertThat(input.aborted).isFalse();
    }

    @Test
    void abortsOversizedHeaderBeforeReadingAndUnknownLengthBodyAtLimitPlusOne() {
        var declared = new TrackedInput(new byte[100]);
        assertThatThrownBy(() -> transformer().transform(response(100L), declared.abortable()))
                .isInstanceOf(SdkClientException.class);
        assertThat(declared.available()).isEqualTo(100);
        assertThat(declared.closed).isTrue();
        assertThat(declared.aborted).isTrue();
        var undeclared = new TrackedInput(new byte[100]);
        assertThatThrownBy(() -> transformer().transform(response(null), undeclared.abortable()))
                .isInstanceOf(SdkClientException.class);
        assertThat(undeclared.available()).isEqualTo(83); // consume at most limit+1, never drain
        assertThat(undeclared.closed).isTrue();
        assertThat(undeclared.aborted).isTrue();
    }

    @Test
    void abortsTruncatedAndFailedBodyReads() {
        var truncated = new TrackedInput(new byte[3]);
        assertThatThrownBy(() -> transformer().transform(response(16L), truncated.abortable()))
                .isInstanceOf(IOException.class);
        assertThat(truncated.aborted).isTrue();
        assertThat(truncated.closed).isTrue();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        var failed = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("read failed"); }
            @Override public void close() { closed.set(true); }
        };
        assertThatThrownBy(() -> transformer().transform(response(null), AbortableInputStream.create(failed, () -> aborted.set(true))))
                .isInstanceOf(IOException.class);
        assertThat(aborted).isTrue();
        assertThat(closed).isTrue();
    }

    private BoundedArtifactResponseTransformer transformer() { return new BoundedArtifactResponseTransformer(16); }
    private GetObjectResponse response(Long length) { return GetObjectResponse.builder().contentLength(length).build(); }
    private static class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        boolean aborted;
        TrackedInput(byte[] body) { super(body); }
        AbortableInputStream abortable() { return AbortableInputStream.create(this, () -> aborted = true); }
        @Override public void close() { closed = true; }
    }
}
