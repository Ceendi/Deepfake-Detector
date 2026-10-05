package com.deepfake.orchestrator.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "analysis_task_outbox")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class AnalysisTaskOutbox {
    @Id private UUID id;
    @Column(name = "analysis_id", nullable = false) private UUID analysisId;
    @Column(nullable = false) private String source;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) private Map<String, Object> payload;
    @Builder.Default @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Builder.Default @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt = Instant.now();
    @Column(nullable = false) private int attempts;
    @Column(name = "sent_at") private Instant sentAt;
}
