package com.deepfake.orchestrator.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisDeletionService {
    private final AnalysisService writer;
    private final FileMetadataClient files;
    private final InputFileLocks inputLocks;

    @Transactional(propagation = Propagation.NEVER)
    public void delete(UUID id, String userId, String bearerToken) {
        String fileId = writer.get(id, userId).fileId();
        inputLocks.withFile(userId, fileId, () -> {
            writer.deleteWithInput(id, userId, () -> files.release(fileId, bearerToken));
            return null;
        });
    }
}
