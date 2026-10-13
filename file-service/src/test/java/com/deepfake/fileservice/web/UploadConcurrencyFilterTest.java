package com.deepfake.fileservice.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class UploadConcurrencyFilterTest {
    @TempDir Path tmp;

    private MockHttpServletRequest upload() {
        var request = new MockHttpServletRequest("POST", "/api/files/upload");
        request.setServletPath("/api/files/upload");
        return request;
    }

    @Test void saturatedReplicaRejectsBeforeParsingAndReleasesSlotAfterFailure() throws Exception {
        var filter = new UploadConcurrencyFilter(1, tmp.resolve("spool").toString());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> {
                try {
                    filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> {
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        throw new java.io.IOException("failed upload");
                    });
                } catch (java.io.IOException expected) {
                    return;
                } catch (jakarta.servlet.ServletException error) {
                    throw new RuntimeException(error);
                }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var rejected = new MockHttpServletResponse();
                filter.doFilter(upload(), rejected, (request, response) -> {
                    throw new AssertionError("must not parse multipart");
                });
                assertThat(rejected.getStatus()).isEqualTo(503);
                assertThat(rejected.getHeader("Retry-After")).isEqualTo("5");
                var read = new MockHttpServletRequest("GET", "/api/files/id/metadata");
                var passed = new AtomicBoolean();
                filter.doFilter(read, new MockHttpServletResponse(), (request, response) -> passed.set(true));
                assertThat(passed).isTrue();
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        var passed = new AtomicBoolean();
        filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> passed.set(true));
        assertThat(passed).isTrue();
    }
}
