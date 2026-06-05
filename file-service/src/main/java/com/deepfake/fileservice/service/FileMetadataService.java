package com.deepfake.fileservice.service;

import com.deepfake.fileservice.dto.FileMetadataResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileMetadataService {

    private final FileMetadataRepository repository;

    @Transactional(readOnly = true)
    public FileMetadataResponse metadata(UUID fileId, String currentUserId) {
        FileMetadata m = repository.findByFileIdAndDeletedAtIsNull(fileId)
                // IDOR guard: 404 not 403, so a foreign (or soft-deleted) file looks like a missing one.
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return FileMetadataResponse.from(m);
    }
}
