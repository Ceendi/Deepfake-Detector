package com.deepfake.orchestrator.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysis")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "file_id", nullable = false)
    private String fileId;

    @Column(name = "file_key", nullable = false)
    private String fileKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisStatus status;

    @Column
    private String verdict;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "video_prob", precision = 5, scale = 4)
    private BigDecimal videoProb;

    @Column(name = "audio_prob", precision = 5, scale = 4)
    private BigDecimal audioProb;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        status = AnalysisStatus.PENDING;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
