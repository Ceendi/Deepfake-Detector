package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.validation.FileValidator;
import com.deepfake.fileservice.entity.FileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "upload.max-concurrent=1"})
@Testcontainers
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
class UploadSpoolIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @TempDir static Path spool;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.servlet.multipart.location", () -> spool.toString());
        registry.add("storage.access-key", () -> "test");
        registry.add("storage.secret-key", () -> "test");
    }
    @Value("${local.server.port}") int port;
    @MockitoBean JwtDecoder jwt;
    @MockitoBean FileValidator validator;
    @MockitoBean S3Client s3;
    @Autowired com.deepfake.fileservice.repository.FileMetadataRepository repository;
    @Autowired com.deepfake.fileservice.service.FileMetadataService metadata;
    @Autowired com.deepfake.fileservice.service.S3CleanupService cleanup;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach void setup() {
        repository.deleteAll();
        when(jwt.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg", "none")
                .subject("alice").claim("realm_access", Map.of("roles", List.of("USER")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    private HttpRequest upload() {
        String part = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.mp4\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n" + "x".repeat(65536) + "\r\n--boundary--\r\n";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files/upload"))
                .header("Authorization", "Bearer test-token")
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(part.getBytes(StandardCharsets.UTF_8))).build();
    }

    @Test void realTomcatKeepsOneMediaCopyAndRejectsBeforeSpoolingWhenFull() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var validationFile = new AtomicReference<Path>();
        when(validator.validate(any())).thenAnswer(call -> {
            validationFile.set(call.getArgument(0));
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new FileValidator.Result("video/mp4", 1.0);
        });
        try (var http = HttpClient.newHttpClient()) {
            var first = http.sendAsync(upload(), HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                try (var files = Files.list(spool)) {
                    assertThat(files.toList()).containsExactly(validationFile.get());
                }
                assertThat(Files.size(validationFile.get())).isEqualTo(65536);
                var rejected = http.send(upload(), HttpResponse.BodyHandlers.ofString());
                assertThat(rejected.statusCode()).isEqualTo(503);
                assertThat(rejected.headers().firstValue("Retry-After")).contains("5");
                assertThat(rejected.body()).contains("SERVICE_UNAVAILABLE");
                var anonymous = HttpRequest.newBuilder(upload(), (name, value) -> !name.equalsIgnoreCase("Authorization"))
                        .build();
                assertThat(http.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
                for (String method : List.of("POST", "PUT")) {
                    var unknown = HttpRequest.newBuilder(upload(), (name, value) -> true)
                            .uri(URI.create("http://localhost:" + port + "/api/files/unknown"))
                            .setHeader("Content-Type", "MuLtIpArT/form-data; boundary=boundary")
                            .method(method, upload().bodyPublisher().orElseThrow()).build();
                    var rejectedUnknown = http.send(unknown, HttpResponse.BodyHandlers.ofString());
                    assertThat(rejectedUnknown.statusCode()).isEqualTo(503);
                    assertThat(rejectedUnknown.headers().firstValue("Retry-After")).contains("5");
                }
                try (var files = Files.list(spool)) {
                    assertThat(files.toList()).containsExactly(validationFile.get());
                }
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            var recovered = http.send(upload(), HttpResponse.BodyHandlers.ofString());
            assertThat(recovered.statusCode()).isEqualTo(200);
        }
        try (var files = Files.list(spool)) { assertThat(files.toList()).isEmpty(); }
        assertThat(repository.findAll().stream().mapToLong(FileMetadata::getSizeBytes).sum()).isEqualTo(2 * 65536);
    }

    @Test void rejectedLargeUploadGetsItsStatusInsteadOfAConnectionReset() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(validator.validate(any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return new FileValidator.Result("video/mp4", 1.0);
        });
        int size = 501 << 20;
        try (var http = HttpClient.newHttpClient()) {
            var first = http.sendAsync(upload(), HttpResponse.BodyHandlers.ofString());
            try (var socket = new java.net.Socket("localhost", port)) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                socket.setSoTimeout(30_000);
                var out = socket.getOutputStream();
                out.write(("POST /api/files/upload HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer test-token\r\n"
                        + "Content-Type: multipart/form-data; boundary=boundary\r\nContent-Length: " + size
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                var chunk = new byte[1 << 20];
                for (int sent = 0; sent < size; sent += chunk.length) out.write(chunk);
                out.flush();
                var status = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                assertThat(status).startsWith("HTTP/1.1 503");
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test void fullOwnerQuotaReturns409WithoutWritingAnotherObject() throws Exception {
        repository.save(FileMetadata.builder().fileId(java.util.UUID.randomUUID()).userId("alice")
                .objectKey("existing.mp4").mimetype("video/mp4").sizeBytes(5368709120L).build());
        when(validator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 1.0));
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(upload(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).contains("STORAGE_QUOTA_EXCEEDED");
        }
        org.mockito.Mockito.verifyNoInteractions(s3);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void exact500MiBFileFitsMultipartButOneExtraByteIsRejected() throws Exception {
        Path large = Files.createTempFile("upload-boundary", ".media");
        long limit = 500L * 1024 * 1024;
        when(validator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 1.0));
        try (var http = HttpClient.newHttpClient()) {
            for (long size : new long[] {limit, limit + 1}) {
                try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(size); }
                var body = HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofString("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.mp4\"\r\n\r\n"),
                        HttpRequest.BodyPublishers.ofFile(large),
                        HttpRequest.BodyPublishers.ofString("\r\n--boundary--\r\n"));
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files/upload"))
                        .header("Authorization", "Bearer test-token")
                        .header("Content-Type", "multipart/form-data; boundary=boundary")
                        .timeout(java.time.Duration.ofMinutes(2)).POST(body).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as("file bytes %s", size).isEqualTo(size == limit ? 200 : 413);
                if (size == limit) assertThat(response.body()).contains("\"size\":" + limit);
            }
        } finally { Files.deleteIfExists(large); }
        assertThat(repository.count()).isEqualTo(1);
        try (var files = Files.list(spool)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void internalDiagnosticsKeepScrapeButRequireAuthenticationForApiDocs() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var base = "http://localhost:" + port;
            var health = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.body()).doesNotContain("components", "details", "jdbc", "diskSpace");
            for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html", "/swagger-ui.html")) {
                var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(401);
            }
            assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    @Test void claimEndpointReturnsMetadataOnlyForOwnerAndIsIdempotent() throws Exception {
        var file = repository.save(FileMetadata.builder().fileId(java.util.UUID.randomUUID()).userId("alice")
                .objectKey("legacy/clip.mp4").mimetype("video/mp4").sizeBytes(1).build());
        try (var http = HttpClient.newHttpClient()) {
            var claim = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files/"
                    + file.getFileId() + "/claim")).header("Authorization", "Bearer test-token")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            var first = http.send(claim, HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(first.body()).contains("legacy/clip.mp4");
            assertThat(http.send(claim, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(first.body());
            jdbc.update("UPDATE file_metadata SET user_id = 'bob' WHERE file_id = ?", file.getFileId());
            assertThat(http.send(claim, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
    }

    @Test void cleanupSoftDeletesSevenDayOldUnusedUploadWithoutPurgingImmediately() {
        var file = repository.save(FileMetadata.builder().fileId(java.util.UUID.randomUUID()).userId("alice")
                .objectKey("old.mp4").mimetype("video/mp4").sizeBytes(1).build());
        jdbc.update("UPDATE file_metadata SET created_at = ? WHERE file_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(8 * 86400L)), file.getFileId());
        when(s3.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder().build());
        cleanup.sweep();
        assertThat(repository.findByFileIdAndDeletedAtIsNull(file.getFileId())).isEmpty();
        assertThat(repository.findById(file.getFileId())).isPresent();
        org.mockito.Mockito.verify(s3, org.mockito.Mockito.never()).deleteObject(
                any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
}
