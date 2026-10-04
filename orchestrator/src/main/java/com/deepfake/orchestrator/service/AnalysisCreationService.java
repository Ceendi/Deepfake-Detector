package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Resolves the authorized input before the separate writer opens its admission transaction. */
@Service
@RequiredArgsConstructor
public class AnalysisCreationService {
    private final FileMetadataClient files;
    private final AnalysisService writer;

    @Transactional(propagation = Propagation.NEVER)
    public AnalysisResponse create(CreateAnalysisRequest request, String userId, String bearerToken) {
        if (request == null || request.type() == null || userId == null || userId.isBlank()
                || userId.length() > 255 || request.fileId() == null
                || request.fileId().length() > 255
                || (request.fileKey() != null && request.fileKey().length() > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analysis request");
        }
        UUID fileId;
        try {
            fileId = UUID.fromString(request.fileId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file identifier");
        }
        String objectKey = files.resolve(fileId, bearerToken);
        // The legacy key is deliberately ignored, including mismatches. Only metadata is authoritative.
        var resolved = new CreateAnalysisRequest(fileId.toString(), objectKey, request.type(), request.mode());
        return writer.createResolved(resolved, userId);
    }
}
