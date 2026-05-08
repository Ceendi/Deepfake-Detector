package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AnalysisService {

    private static final BigDecimal W_VIDEO   = new BigDecimal("0.6");
    private static final BigDecimal W_AUDIO   = new BigDecimal("0.4");
    private static final BigDecimal THRESHOLD = new BigDecimal("0.5");
    private static final BigDecimal TWO       = new BigDecimal("2");

    private final AnalysisRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;

    public Analysis create(CreateAnalysisRequest req, String userId) {
        Analysis analysis = Analysis.builder()
                .userId(userId)
                .fileId(req.fileId())
                .fileKey(req.fileKey())
                .type(req.type())
                .build();

        analysis = repository.save(analysis);

        Map<String, Object> payload = Map.of(
                "analysis_id",   analysis.getId().toString(),
                "file_bucket",   "deepfake-uploads",
                "file_key",      req.fileKey(),
                "correlation_id", UUID.randomUUID().toString(),
                "timestamp",     Instant.now().toString()
        );

        if (req.type() == AnalysisType.VIDEO || req.type() == AnalysisType.FULL) {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_VIDEO, payload);
        }
        if (req.type() == AnalysisType.AUDIO || req.type() == AnalysisType.FULL) {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_AUDIO, payload);
        }

        return analysis;
    }

    @Transactional(readOnly = true)
    public Analysis get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public void handleResult(Map<String, Object> payload) {
        UUID id = UUID.fromString((String) payload.get("analysis_id"));
        String source = (String) payload.get("source"); // "video" | "audio"
        String status = (String) payload.get("status"); // "COMPLETED" | "FAILED"

        Analysis a = repository.findById(id).orElseThrow();

        // Idempotency — late or duplicate delivery on already-finalized analysis is ignored.
        if (a.getStatus() == AnalysisStatus.COMPLETED || a.getStatus() == AnalysisStatus.FAILED) {
            log.warn("Late/duplicate result for {} (source={}), ignoring", id, source);
            return;
        }

        if ("FAILED".equals(status)) {
            a.setStatus(AnalysisStatus.FAILED);
            a.setErrorMessage(extractError(payload));
            repository.save(a);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) payload.get("result");
        BigDecimal prob = new BigDecimal(result.get("prob_fake").toString());
        if ("video".equals(source)) a.setVideoProb(prob);
        if ("audio".equals(source)) a.setAudioProb(prob);

        boolean needV = a.getType() == AnalysisType.VIDEO || a.getType() == AnalysisType.FULL;
        boolean needA = a.getType() == AnalysisType.AUDIO || a.getType() == AnalysisType.FULL;
        boolean done = (!needV || a.getVideoProb() != null)
                    && (!needA || a.getAudioProb() != null);

        if (done) {
            BigDecimal finalProb = aggregate(a.getVideoProb(), a.getAudioProb()); // 0.6V + 0.4A
            a.setStatus(AnalysisStatus.COMPLETED);
            a.setVerdict(finalProb.compareTo(THRESHOLD) > 0 ? "FAKE" : "REAL");
            a.setConfidence(finalProb.subtract(THRESHOLD).abs().multiply(TWO));
        }
        repository.save(a);

        // 🔧 TODO (semester 1 week 5 — D6 race fix): replace findById + save with an atomic
        // UPDATE ... WHERE id = :id AND status IN ('PENDING','PROCESSING') RETURNING *.
        // With dummy ML (~2s sleep) the race is unlikely; with real ML both replies arrive
        // within ms and last-write-wins drops one of videoProb/audioProb. See Plan_Dnia §7.
    }

    public void handleProgress(Map<String, Object> payload) {
        String id = (String) payload.get("analysis_id");
        Integer progress = (Integer) payload.get("progress");
        redis.opsForValue().set("progress:" + id, progress.toString(), Duration.ofHours(1));
    }

    private BigDecimal aggregate(BigDecimal videoProb, BigDecimal audioProb) {
        if (videoProb == null) return audioProb;
        if (audioProb == null) return videoProb;
        return videoProb.multiply(W_VIDEO).add(audioProb.multiply(W_AUDIO));
    }

    private String extractError(Map<String, Object> payload) {
        Object err = payload.get("error");
        if (err instanceof Map<?, ?> m) {
            return String.format("[%s] %s", m.get("code"), m.get("message"));
        }
        return err == null ? "unknown" : err.toString();
    }
}
