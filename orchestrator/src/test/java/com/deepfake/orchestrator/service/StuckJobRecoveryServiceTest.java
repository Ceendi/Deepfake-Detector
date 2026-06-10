package com.deepfake.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.deepfake.orchestrator.repository.AnalysisRepository;

/** The scheduled scan fails each stuck job in isolation: one failure must not abort the others. */
@ExtendWith(MockitoExtension.class)
class StuckJobRecoveryServiceTest {

    @Mock AnalysisRepository repository;
    @Mock AnalysisService analysisService;

    StuckJobRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new StuckJobRecoveryService(repository, analysisService, 600);
    }

    @Test
    void failsEveryStuckJob() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findStuckIds(any(Instant.class))).thenReturn(List.of(a, b));

        service.reclaimStuck();

        verify(analysisService).failStuck(a, 600);
        verify(analysisService).failStuck(b, 600);
    }

    @Test
    void noStuckJobsDoesNothing() {
        when(repository.findStuckIds(any(Instant.class))).thenReturn(List.of());

        service.reclaimStuck();

        verifyNoInteractions(analysisService);
    }

    @Test
    void oneFailureDoesNotAbortTheRest() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findStuckIds(any(Instant.class))).thenReturn(List.of(a, b));
        doThrow(new RuntimeException("db down")).when(analysisService).failStuck(eq(a), anyLong());

        service.reclaimStuck();

        verify(analysisService).failStuck(b, 600); // b still processed despite a throwing
    }
}
