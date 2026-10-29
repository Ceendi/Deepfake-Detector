package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisDeletionServiceTest {
    @Test void authorizedFileAndJwtReachReleaseWithinDeleteTransaction() {
        var writer = mock(AnalysisService.class);
        var files = mock(FileMetadataClient.class);
        var id = UUID.randomUUID();
        var fileId = UUID.randomUUID().toString();
        when(writer.get(id, "alice")).thenReturn(AnalysisResponse.from(Analysis.builder()
                .id(id).userId("alice").fileId(fileId).type(AnalysisType.VIDEO).build()));
        doAnswer(call -> { call.getArgument(2, Runnable.class).run(); return null; })
                .when(writer).deleteWithInput(eq(id), eq("alice"), any());
        new AnalysisDeletionService(writer, files, new InputFileLocks()).delete(id, "alice", "jwt");
        verify(files).release(fileId, "jwt");
    }

    @Test void missingOrForeignAnalysisNeverContactsFileService() {
        var writer = mock(AnalysisService.class);
        var files = mock(FileMetadataClient.class);
        var id = UUID.randomUUID();
        when(writer.get(id, "alice")).thenThrow(new IllegalStateException("404"));
        var service = new AnalysisDeletionService(writer, files, new InputFileLocks());
        assertThatThrownBy(() -> service.delete(id, "alice", "jwt")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(files);
    }
}
