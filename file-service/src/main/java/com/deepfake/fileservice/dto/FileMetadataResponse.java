package com.deepfake.fileservice.dto;

import com.deepfake.fileservice.entity.FileMetadata;

// Shape per docs/contracts/rest-api.md (GET /api/files/{id}/metadata).
public record FileMetadataResponse(String fileId, String name, long size, Double duration,
                                   String mimetype) {

    public static FileMetadataResponse from(FileMetadata m) {
        return new FileMetadataResponse(m.getFileId().toString(), m.getOriginalName(),
                m.getSizeBytes(), m.getDurationSeconds(), m.getMimetype());
    }
}
