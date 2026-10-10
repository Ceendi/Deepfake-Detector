package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@ExtendWith(MockitoExtension.class)
class ArtifactCleanupWorkerTest {
    @Mock ArtifactCleanupStore store;
    @Mock S3Client s3;
    ArtifactCleanupWorker worker;
    UUID id = UUID.randomUUID();
    String key = id + "/audio/gradcam_" + "a".repeat(32) + ".png";

    @BeforeEach void setUp() { worker = new ArtifactCleanupWorker(store, s3, "analysis-artifacts", 2, 10, Duration.ofHours(24)); }

    @Test void partialFailureRetriesOneKeyAndContinuesButBatchIsBounded() {
        var first = new ArtifactCleanupStore.Work(key, id, UUID.randomUUID(), 0);
        var second = new ArtifactCleanupStore.Work("legacy/video/cam.png", id, UUID.randomUUID(), 3);
        when(store.claim()).thenReturn(first, second);
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("down")).thenReturn(DeleteObjectResponse.builder().build());
        worker.clean();
        verify(store).retry(first);
        verify(store).complete(second);
        verify(store, times(2)).claim();
        var requests = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3, times(2)).deleteObject(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(r -> assertThat(r.bucket()).isEqualTo("analysis-artifacts"));
    }

    @Test void protectedWorkDoesNotTouchStorage() {
        when(store.claim()).thenReturn(new ArtifactCleanupStore.Work(key, id, null, 0)).thenReturn(null);
        worker.clean();
        verifyNoInteractions(s3);
    }

    @Test void scanRecognizesOnlyOldCurrentAttemptNamesAndResumesTheCursor() {
        var scan = new ArtifactCleanupStore.Scan(UUID.randomUUID(), "page2");
        when(store.claimScan()).thenReturn(scan);
        Instant old = Instant.now().minus(Duration.ofHours(25));
        String video = id + "/video/gradcam_frame_123_" + "b".repeat(32) + ".png";
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
            .isTruncated(true).nextContinuationToken("page3").contents(
                object(key, old), object(video, old), object(id + "/video/cam.png", old),
                object("uploads/file.mp4", old), object(key.replace("a.png", "c.png"), Instant.now()),
                S3Object.builder().key(key).build()).build());
        worker.scan();
        verify(store).finishScan(scan, List.of(new ArtifactCleanupStore.Candidate(key, id), new ArtifactCleanupStore.Candidate(video, id)), "page3");
        var request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(request.capture());
        assertThat(request.getValue().maxKeys()).isEqualTo(10);
        assertThat(request.getValue().continuationToken()).isEqualTo("page2");
        assertThat(request.getValue().bucket()).isEqualTo("analysis-artifacts");
    }

    @Test void invalidTruncatedPagePreservesCursorRatherThanStartingOver() {
        var scan = new ArtifactCleanupStore.Scan(UUID.randomUUID(), "same");
        when(store.claimScan()).thenReturn(scan);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("same").build());
        worker.scan();
        verify(store).releaseScan(scan);
        verify(store, never()).finishScan(any(), any(), any());
    }

    @Test void listingOutageReleasesScanLeaseWithoutLosingCursor() {
        var scan = new ArtifactCleanupStore.Scan(UUID.randomUUID(), "page2");
        when(store.claimScan()).thenReturn(scan);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(SdkClientException.create("down"));
        worker.scan();
        verify(store).releaseScan(scan);
        verify(store, never()).finishScan(any(), any(), any());
    }

    @Test void refusesSourceBucketAndUnboundedConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactCleanupWorker(store, s3, "deepfake-uploads", 2, 10, Duration.ofHours(24)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactCleanupWorker(store, s3, "analysis-artifacts", 101, 10, Duration.ofHours(24)));
    }
    private S3Object object(String name, Instant age) { return S3Object.builder().key(name).lastModified(age).build(); }
}
