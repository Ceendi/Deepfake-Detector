package com.deepfake.fileservice.repository;

import com.deepfake.fileservice.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    // Soft-deleted rows are invisible to the API (metadata/presign/delete all 404 once deleted_at is set).
    Optional<FileMetadata> findByFileIdAndDeletedAtIsNull(UUID fileId);

    // Purge candidates: soft-deleted past the retention cutoff (NULL deleted_at never matches).
    List<FileMetadata> findByDeletedAtBefore(Instant cutoff);

    // Orphan check for one S3 listing page: which of these keys have a metadata row at all
    // (active OR soft-deleted — soft-deleted objects belong to the purge path, not the orphan path).
    @Query("SELECT m.objectKey FROM FileMetadata m WHERE m.objectKey IN :keys")
    Set<String> findExistingObjectKeys(@Param("keys") Collection<String> keys);

    // CAS for the cleanup sweep under D4 replication: two replicas may purge concurrently, only
    // the one that gets 1 row counts the purge. Guarded on deleted_at so an active row is never
    // hard-deleted by accident.
    @Modifying
    @Transactional
    @Query("DELETE FROM FileMetadata m WHERE m.fileId = :id AND m.deletedAt IS NOT NULL")
    int hardDeleteIfSoftDeleted(@Param("id") UUID id);
}
