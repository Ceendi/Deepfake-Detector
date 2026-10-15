package com.deepfake.fileservice.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class UploadConcurrencyFilterTest {
    @TempDir Path tmp;

    private static void authenticate(String subject) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token").header("alg", "none").subject(subject).build(), java.util.List.of()));
    }

    @BeforeEach void login() { authenticate("alice"); }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void anonymousMultipartTakesAGlobalSlotButNoUserSlot() throws Exception {
        var filter = new UploadConcurrencyFilter(1, 1, tmp.toString());
        SecurityContextHolder.clearContext();
        filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> {
            assertNoUserSlots(filter);
            authenticate("alice");
            var rejected = new MockHttpServletResponse();
            filter.doFilter(upload(), rejected, (nestedRequest, nestedResponse) -> {
                throw new AssertionError("anonymous multipart must hold the replica slot");
            });
            assertThat(rejected.getStatus()).isEqualTo(503);
        });
        assertNoUserSlots(filter);
    }

    @Test void configuredUserLimitTakesPrecedenceOverGlobalLimit() throws Exception {
        var filter = new UploadConcurrencyFilter(1, 1, tmp.toString());
        filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> {
            var rejected = new MockHttpServletResponse();
            filter.doFilter(upload(), rejected, (nestedRequest, nestedResponse) -> {
                throw new AssertionError("must reject user before global admission");
            });
            assertThat(rejected.getStatus()).isEqualTo(429);
        });
        assertNoUserSlots(filter);
    }

    private static void assertNoUserSlots(UploadConcurrencyFilter filter) {
        assertThat((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
                .getField(filter, "slotsByUser")).isEmpty();
    }

    @Test void rejectsNonpositiveLimits() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new UploadConcurrencyFilter(0, 2, tmp.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new UploadConcurrencyFilter(4, 0, tmp.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void userLimitLeavesCapacityForAnotherOwner() throws Exception {
        var filter = new UploadConcurrencyFilter(4, tmp.toString());
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var uploads = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                uploads.add(pool.submit(() -> {
                    authenticate("alice");
                    try {
                        filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> {
                            entered.countDown();
                            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                            catch (InterruptedException error) { throw new java.io.IOException(error); }
                        });
                    } catch (Exception error) { throw new RuntimeException(error); }
                    finally { SecurityContextHolder.clearContext(); }
                }));
            }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var rejected = new MockHttpServletResponse();
                filter.doFilter(upload(), rejected, (request, response) -> {
                    throw new AssertionError("must reject before parsing multipart");
                });
                assertThat(rejected.getStatus()).isEqualTo(429);
                assertThat(rejected.getHeader("Retry-After")).isEqualTo("5");
                assertThat(rejected.getContentAsString()).contains("UPLOAD_USER_LIMIT");
                authenticate("bob");
                var passed = new AtomicBoolean();
                filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> passed.set(true));
                assertThat(passed).isTrue();
            } finally { release.countDown(); }
            for (var task : uploads) task.get(5, TimeUnit.SECONDS);
        }
        authenticate("alice");
        var passed = new AtomicBoolean();
        filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> passed.set(true));
        assertThat(passed).isTrue();
        assertNoUserSlots(filter);
    }

    @Test void userSlotReturnsAfterExceptionsAndInterruption() throws Exception {
        var filter = new UploadConcurrencyFilter(4, tmp.toString());
        for (int i = 0; i < 4; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(upload(),
                    new MockHttpServletResponse(), (request, response) -> {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("client disconnected");
                    })).isInstanceOf(java.io.IOException.class);
            assertThat(Thread.interrupted()).isTrue();
        }
        var passed = new AtomicBoolean();
        filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> passed.set(true));
        assertThat(passed).isTrue();
        assertNoUserSlots(filter);
    }

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
                authenticate("alice");
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
                } finally {
                    SecurityContextHolder.clearContext();
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
                assertThat(rejected.getContentAsString()).contains("SERVICE_UNAVAILABLE");
                var unknown = new MockHttpServletRequest("PUT", "/api/files/unknown");
                unknown.setServletPath("/api/files/unknown");
                unknown.setContentType("MuLtIpArT/form-data; boundary=test");
                var unknownResponse = new MockHttpServletResponse();
                filter.doFilter(unknown, unknownResponse, (request, response) -> {
                    throw new AssertionError("multipart on unknown routes must not parse when full");
                });
                assertThat(unknownResponse.getStatus()).isEqualTo(503);
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
        assertNoUserSlots(filter);
    }
}
