package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import com.deepfake.orchestrator.config.ObjectStorageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

/** Production storage client warmed, then a live TCP listener stalls each command. No Docker needed. */
class ArtifactCleanupStorageBudgetTest {
    @Test void stalledDeleteAndListingAreBoundedAndRecoverUsingTheSameConfiguredClient() throws Exception {
        try (var handlers = Executors.newFixedThreadPool(8)) {
            var release = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(0));
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                try { release.get().await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    if (exchange.getRequestMethod().equals("GET")) {
                        byte[] xml = "<ListBucketResult><Name>analysis-artifacts</Name><IsTruncated>false</IsTruncated></ListBucketResult>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, xml.length); exchange.getResponseBody().write(xml);
                    } else exchange.sendResponseHeaders(204, -1);
                } catch (java.io.IOException ignored) { /* timed-out request already aborted by the client */ }
                finally { exchange.close(); }
            });
            server.start();
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                    "storage.endpoint", "http://127.0.0.1:" + server.getAddress().getPort(), "storage.region", "us-east-1",
                    "storage.access-key", "test", "storage.secret-key", "test")));
                context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
                context.register(ObjectStorageConfig.class); context.refresh();
                var s3 = context.getBean(S3Client.class);
                assertThat(s3.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(Duration.ofSeconds(5));
                assertThat(s3.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
                var store = mock(ArtifactCleanupStore.class);
                var worker = new ArtifactCleanupWorker(store, s3, "analysis-artifacts", 1, 100, Duration.ofHours(24));
                var work = new ArtifactCleanupStore.Work("legacy/audio/cam.png", UUID.randomUUID(), UUID.randomUUID(), 0);
                when(store.claim()).thenReturn(work);
                worker.clean(); verify(store).complete(work); clearInvocations(store); // warm actual client
                release.set(new CountDownLatch(1));
                try {
                    long start = System.nanoTime(); worker.clean();
                    assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(7));
                    verify(store).retry(work); verify(store, never()).complete(any());
                } finally { release.get().countDown(); }
                clearInvocations(store); worker.clean(); verify(store).complete(work);
                var scan = new ArtifactCleanupStore.Scan(UUID.randomUUID(), null);
                when(store.claimScan()).thenReturn(scan);
                worker.scan(); verify(store).finishScan(scan, java.util.List.of(), null); clearInvocations(store);
                release.set(new CountDownLatch(1));
                try {
                    long start = System.nanoTime(); worker.scan();
                    assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(7));
                    verify(store).releaseScan(scan); verify(store, never()).finishScan(any(), any(), any());
                } finally { release.get().countDown(); }
                clearInvocations(store); worker.scan(); verify(store).finishScan(scan, java.util.List.of(), null);
            } finally { release.get().countDown(); server.stop(0); }
        }
    }
}
