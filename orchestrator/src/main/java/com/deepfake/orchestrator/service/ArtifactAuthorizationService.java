package com.deepfake.orchestrator.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolve only recorded, owned keys in a completed transaction before contacting storage. */
@Service
@RequiredArgsConstructor
public class ArtifactAuthorizationService {
    private final AnalysisRepository repository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public String resolveOwnedKey(UUID analysisId, String source, String name, String currentUserId) {
        Analysis analysis = repository.findById(analysisId)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(ArtifactAuthorizationService::notFound);
        Map<String, Object> details = switch (source) {
            case "video" -> analysis.getVideoDetails();
            case "audio" -> analysis.getAudioDetails();
            default -> null;
        };
        if (details == null || !(details.get("gradcamKeys") instanceof Collection<?> keys)) throw notFound();
        return keys.stream().map(Object::toString)
                .filter(key -> name.equals(key.substring(key.lastIndexOf('/') + 1)))
                .findFirst().orElseThrow(ArtifactAuthorizationService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
