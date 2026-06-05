package com.deepfake.fileservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_metadata")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class FileMetadata {

    @Id
    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "original_name")
    private String originalName;

    @Column(nullable = false)
    private String mimetype;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
