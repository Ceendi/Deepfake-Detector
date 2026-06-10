package com.deepfake.fileservice.repository;

import com.deepfake.fileservice.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    // Soft-deleted rows are invisible to the API (metadata/presign/delete all 404 once deleted_at is set).
    Optional<FileMetadata> findByFileIdAndDeletedAtIsNull(UUID fileId);
}
