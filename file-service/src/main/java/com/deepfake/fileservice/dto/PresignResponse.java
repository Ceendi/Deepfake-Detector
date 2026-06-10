package com.deepfake.fileservice.dto;

import java.time.Instant;

// Shape per docs/contracts/rest-api.md (GET /api/files/{id}/presign).
public record PresignResponse(String url, Instant expiresAt) {
}
