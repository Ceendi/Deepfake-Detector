package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

class ArtifactCleanupDefaultsTest {
    @Test
    void defaultScanRequestsOneFullS3PageAndPreservesTheCursor() {
        var store = mock(ArtifactCleanupStore.class);
        var s3 = mock(S3Client.class);
        var scan = new ArtifactCleanupStore.Scan(UUID.randomUUID(), "page2");
        when(store.claimScan()).thenReturn(scan);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .isTruncated(true).nextContinuationToken("page3").build());
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ArtifactCleanupStore.class, () -> store)
                .withBean(S3Client.class, () -> s3)
                .withUserConfiguration(ArtifactCleanupWorker.class)
                .run(context -> {
                    context.getBean(ArtifactCleanupWorker.class).scan();
                    var request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
                    verify(s3).listObjectsV2(request.capture());
                    assertThat(request.getValue().maxKeys()).isEqualTo(1000);
                    assertThat(request.getValue().continuationToken()).isEqualTo("page2");
                    verify(store).finishScan(scan, java.util.List.of(), "page3");
                });
    }
}
