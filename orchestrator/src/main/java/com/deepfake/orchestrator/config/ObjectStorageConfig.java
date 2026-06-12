package com.deepfake.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * S3 client for serving Grad-CAM artifacts. The orchestrator identity is read-only on
 * analysis-artifacts (docs/contracts/object-storage.md) — deliberately unable to touch user
 * uploads. Construction is offline, so the context starts without SeaweedFS.
 */
@Configuration
public class ObjectStorageConfig {
    @Value("${storage.endpoint}") String endpoint;
    @Value("${storage.region}") String region;
    @Value("${storage.access-key}") String accessKey;
    @Value("${storage.secret-key}") String secretKey;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .forcePathStyle(true)
                .build();
    }
}
