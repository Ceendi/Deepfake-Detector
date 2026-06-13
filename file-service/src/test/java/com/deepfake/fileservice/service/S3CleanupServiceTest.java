package com.deepfake.fileservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Sweep semantics with mocked S3 + repository: purge order (object before row), CAS-guarded
 * counting, per-item fail-open, and the orphan rules (age + unknown key only, paginated listing).
 */
@ExtendWith(MockitoExtension.class)
class S3CleanupServiceTest {

    private static final String BUCKET = "deepfake-uploads";

    @Mock
    FileMetadataRepository repository;
    @Mock
    S3Client s3;

    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private S3CleanupService service() {
        return new S3CleanupService(repository, s3, BUCKET, 72, 24, registry);
    }

    private FileMetadata expired(UUID id, String key) {
        return FileMetadata.builder().fileId(id).objectKey(key).userId("u")
                .mimetype("video/mp4").sizeBytes(1L)
                .deletedAt(Instant.now().minusSeconds(100L * 3600)).build();
    }

    private double counter(String name) {
        return registry.counter(name).count();
    }

    @Test
    void purgeDeletesObjectThenRowAndCounts() {
        UUID id = UUID.randomUUID();
        when(repository.findByDeletedAtBefore(any())).thenReturn(List.of(expired(id, "k1")));
        when(repository.hardDeleteIfSoftDeleted(id)).thenReturn(1);

        service().purgeSoftDeleted();

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(req.getValue().key()).isEqualTo("k1");
        verify(repository).hardDeleteIfSoftDeleted(id);
        assertThat(counter("files.purged")).isEqualTo(1.0);
    }

    @Test
    void purgeKeepsRowWhenS3DeleteFails() {
        UUID id = UUID.randomUUID();
        when(repository.findByDeletedAtBefore(any())).thenReturn(List.of(expired(id, "k1")));
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new RuntimeException("s3 down"));

        service().purgeSoftDeleted();

        verify(repository, never()).hardDeleteIfSoftDeleted(any());
        assertThat(counter("files.purged")).isZero();
    }

    @Test
    void purgeCasLoserDoesNotCount() {
        UUID id = UUID.randomUUID();
        when(repository.findByDeletedAtBefore(any())).thenReturn(List.of(expired(id, "k1")));
        when(repository.hardDeleteIfSoftDeleted(id)).thenReturn(0); // the other replica won

        service().purgeSoftDeleted();

        assertThat(counter("files.purged")).isZero();
    }

    @Test
    void purgeContinuesPastOneFailedItem() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(repository.findByDeletedAtBefore(any()))
                .thenReturn(List.of(expired(bad, "bad"), expired(good, "good")));
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(null);
        when(repository.hardDeleteIfSoftDeleted(good)).thenReturn(1);

        service().purgeSoftDeleted();

        verify(repository).hardDeleteIfSoftDeleted(good);
        verify(repository, never()).hardDeleteIfSoftDeleted(bad);
        assertThat(counter("files.purged")).isEqualTo(1.0);
    }

    private static S3Object obj(String key, Instant lastModified) {
        return S3Object.builder().key(key).lastModified(lastModified).build();
    }

    @Test
    void orphanSweepDeletesOnlyAgedUnknownObjects() {
        Instant old = Instant.now().minusSeconds(48L * 3600);
        Instant young = Instant.now().minusSeconds(60);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder()
                        .contents(obj("orphan-old", old), obj("known-old", old), obj("orphan-young", young))
                        .build());
        when(repository.findExistingObjectKeys(anyCollection())).thenReturn(Set.of("known-old"));

        service().reclaimOrphans();

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(req.capture());
        assertThat(req.getValue().key()).isEqualTo("orphan-old");
        assertThat(counter("files.orphans.reclaimed")).isEqualTo(1.0);
    }

    @Test
    void orphanSweepFollowsPagination() {
        Instant old = Instant.now().minusSeconds(48L * 3600);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder()
                        .contents(obj("page1-orphan", old)).nextContinuationToken("next").build(),
                ListObjectsV2Response.builder()
                        .contents(obj("page2-orphan", old)).build());
        when(repository.findExistingObjectKeys(anyCollection())).thenReturn(Set.of());

        service().reclaimOrphans();

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).deleteObject(req.capture());
        assertThat(req.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly("page1-orphan", "page2-orphan");
        assertThat(counter("files.orphans.reclaimed")).isEqualTo(2.0);
    }

    @Test
    void orphanSweepSkipsDbLookupWhenPageHasNoAgedObjects() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder()
                        .contents(obj("young", Instant.now().minusSeconds(60))).build());

        service().reclaimOrphans();

        verify(repository, never()).findExistingObjectKeys(anyCollection());
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void orphanSweepFailsOpenWhenListingFails() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(new RuntimeException("s3 down"));

        service().reclaimOrphans(); // must not throw

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
