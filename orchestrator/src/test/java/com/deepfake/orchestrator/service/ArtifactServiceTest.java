package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Membership-based authorization for artifact serving: only filenames recorded in the persisted
 * gradcamKeys are servable, every miss (foreign owner, wrong source, unknown name) is the same 404,
 * and the S3 key always comes from the stored value — never from request input.
 */
@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    @Mock AnalysisRepository repository;
    @Mock S3Client s3;

    ArtifactService service;

    private final UUID id = UUID.randomUUID();
    private final byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

    @BeforeEach
    void setUp() {
        service = new ArtifactService(repository, s3, "analysis-artifacts");
    }

    @Test
    void servesRecordedArtifactToOwnerUsingStoredKey() {
        givenAnalysis(Map.of("gradcamKeys", List.of(id + "/audio/gradcam.png")));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), png));

        byte[] got = service.download(id, "audio", "gradcam.png", "alice");

        assertThat(got).isEqualTo(png);
        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo("analysis-artifacts");
        assertThat(req.getValue().key()).isEqualTo(id + "/audio/gradcam.png");
    }

    @Test
    void legacyKeyWithoutSourceSegmentIsMatchedByFilename() {
        givenAnalysis(Map.of("gradcamKeys", List.of(id + "/gradcam.png")));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), png));

        service.download(id, "audio", "gradcam.png", "alice");

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(req.capture());
        assertThat(req.getValue().key()).isEqualTo(id + "/gradcam.png");
    }

    @Test
    void foreignOwnerGets404WithoutTouchingStorage() {
        givenAnalysis(Map.of("gradcamKeys", List.of(id + "/audio/gradcam.png")));

        assertThatThrownBy(() -> service.download(id, "audio", "gradcam.png", "mallory"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void unknownAnalysisUnknownSourceAndUnrecordedNameAllGet404() {
        when(repository.findById(id)).thenReturn(Optional.empty());
        assert404(() -> service.download(id, "audio", "gradcam.png", "alice"));

        givenAnalysis(Map.of("gradcamKeys", List.of(id + "/audio/gradcam.png")));
        assert404(() -> service.download(id, "lidar", "gradcam.png", "alice"));   // not video|audio
        assert404(() -> service.download(id, "video", "gradcam.png", "alice"));   // no video details
        assert404(() -> service.download(id, "audio", "other.png", "alice"));     // name not recorded
        verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void missingObjectIs404AndStorageOutageIs503() {
        givenAnalysis(Map.of("gradcamKeys", List.of(id + "/audio/gradcam.png")));

        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        assert404(() -> service.download(id, "audio", "gradcam.png", "alice"));

        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));
        assertThatThrownBy(() -> service.download(id, "audio", "gradcam.png", "alice"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private void givenAnalysis(Map<String, Object> audioDetails) {
        when(repository.findById(id)).thenReturn(Optional.of(
                Analysis.builder().id(id).userId("alice").fileId("f").fileKey("k")
                        .type(AnalysisType.AUDIO).audioDetails(audioDetails).build()));
    }

    private void assert404(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
