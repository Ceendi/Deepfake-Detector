package com.deepfake.orchestrator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Uses the caller's verified JWT; no client identity headers or upload storage credentials. */
@Service
public class FileMetadataClient {
    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();

    public FileMetadataClient(@Value("${file-service.base-url:http://file-service:8081}") String baseUrl,
                              @Value("${file-service.lookup-timeout:2s}") Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("File metadata timeout must be positive");
        }
        this.baseUrl = URI.create(baseUrl);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String resolve(UUID fileId, String bearerToken) {
        var request = HttpRequest.newBuilder(baseUrl.resolve("/api/files/" + fileId + "/metadata"))
                .timeout(timeout).header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json").GET().build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            // Covers the complete response body as well as connection and headers; no retries.
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException ex) {
            pending.cancel(true);
            throw unavailable();
        }
        if (response.statusCode() == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        if (response.statusCode() != 200) {
            throw unavailable();
        }
        String key;
        try {
            var metadata = json.readTree(response.body());
            if (!fileId.toString().equals(metadata.path("fileId").asString())) {
                throw unavailable();
            }
            var keyNode = metadata.path("objectKey");
            key = keyNode.isString() ? keyNode.asString() : null;
        } catch (tools.jackson.core.JacksonException ex) {
            throw unavailable();
        }
        if (key == null || key.isBlank() || key.length() > 500) {
            // Older or malformed dependency responses fail closed instead of using the client's key.
            throw unavailable();
        }
        return key;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "File metadata service unavailable");
    }
}
