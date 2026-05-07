package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AnalysisService {

    private final AnalysisRepository repository;
    private final RabbitTemplate rabbitTemplate;

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
}
