package com.deepfake.fileservice.dto;

// Shape per docs/contracts/rest-api.md (POST /api/files/upload).
public record FileUploadResponse(String fileId, String fileKey, long size, String mimetype) {
}
