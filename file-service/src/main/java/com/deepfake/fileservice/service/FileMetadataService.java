package com.deepfake.fileservice.service;

import com.deepfake.fileservice.dto.FileMetadataResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileMetadataService {

    private final FileMetadataRepository repository;

    @Transactional(readOnly = true)
    public FileMetadataResponse metadata(UUID fileId, String currentUserId) {
        return FileMetadataResponse.from(loadOwned(fileId, currentUserId));
    }

    @Transactional
    public void softDelete(UUID fileId, String currentUserId) {
        FileMetadata m = loadOwned(fileId, currentUserId);
        m.setDeletedAt(Instant.now());
        repository.save(m);
        // S3 object is kept; a TTL job reclaims deleted/orphaned objects later.
        // TODO(week 5+): S3 cleanup job for deleted_at older than the retention window.
    }

    // IDOR guard: 404 not 403, so a foreign (or already soft-deleted) file looks like a missing one.
    private FileMetadata loadOwned(UUID fileId, String currentUserId) {
        return repository.findByFileIdAndDeletedAtIsNull(fileId)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
