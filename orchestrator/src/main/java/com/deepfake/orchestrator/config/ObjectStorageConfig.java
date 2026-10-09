package com.deepfake.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

/**
 * S3 client for serving Grad-CAM artifacts. The orchestrator identity is scoped to
 * analysis-artifacts (docs/contracts/object-storage.md) — deliberately unable to touch user
 * uploads. Construction is offline, so the context starts without SeaweedFS.
 */
@Configuration
public class ObjectStorageConfig {
    @Value("${storage.endpoint}") String endpoint;
    @Value("${storage.region}") String region;
    @Value("${storage.access-key}") String accessKey;
    @Value("${storage.secret-key}") String secretKey;

    @Value("${storage.artifact-call-timeout:5s}") Duration callTimeout;
    @Value("${storage.artifact-attempt-timeout:2s}") Duration attemptTimeout;
    @Value("${storage.artifact-connect-timeout:500ms}") Duration connectTimeout;

    @Bean
    public S3Client s3Client() {
        if (callTimeout.isZero() || callTimeout.isNegative() || attemptTimeout.isZero()
                || attemptTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Artifact storage budgets must be positive");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(connectTimeout)
                        .connectionAcquisitionTimeout(connectTimeout)
                        .socketTimeout(attemptTimeout))
                .overrideConfiguration(config -> config.apiCallTimeout(callTimeout)
                        .apiCallAttemptTimeout(attemptTimeout))
                .forcePathStyle(true)
                .build();
    }
}
