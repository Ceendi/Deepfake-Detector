package com.deepfake.fileservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.deepfake.fileservice.dto.PresignResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;

/**
 * Presigning is offline (no network), so a real S3Presigner is used to prove the URL carries the
 * public host and a SigV4 signature, plus the IDOR guard (foreign/missing file -> 404).
 */
@ExtendWith(MockitoExtension.class)
class PresignServiceTest {

    @Mock
    FileMetadataRepository repository;

    S3Presigner presigner;
    PresignService service;
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create("http://localhost:8333"))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("k", "s")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        service = new PresignService(repository, presigner);
        ReflectionTestUtils.setField(service, "bucket", "deepfake-uploads");
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void ownerGetsPresignedUrlOnThePublicHost() {
        FileMetadata m = FileMetadata.builder().fileId(id).userId("alice")
                .objectKey(id + "_clip.mp4").mimetype("video/mp4").sizeBytes(1L).build();
        when(repository.findByFileIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(m));

        Instant before = Instant.now();
        PresignResponse r = service.presign(id, "alice");

        assertThat(r.url()).startsWith("http://localhost:8333/deepfake-uploads/" + id + "_clip.mp4");
        assertThat(r.url()).contains("X-Amz-Signature").doesNotContain("seaweedfs");
        assertThat(r.expiresAt()).isBetween(before.plus(59, ChronoUnit.MINUTES),
                before.plus(61, ChronoUnit.MINUTES));
    }

    @Test
    void rejectsCrossUserAccessAs404() {
        FileMetadata m = FileMetadata.builder().fileId(id).userId("alice").objectKey("k").build();
        when(repository.findByFileIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> service.presign(id, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void missingReturns404() {
        when(repository.findByFileIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.presign(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }
}
