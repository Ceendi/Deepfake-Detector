package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.mockito.ArgumentCaptor;
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
        service = new StuckJobRecoveryService(repository, analysisService, 600, 120, 1800, 7200);
    }

    @Test
    void failsEveryStuckJob() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findExpiredIds(any(Instant.class), any(Instant.class), any(Instant.class), any(Instant.class))).thenReturn(List.of(a, b));

        service.reclaimStuck();

        verify(analysisService).failStuck(a, 600);
        verify(analysisService).failStuck(b, 600);
    }

    @Test
    void scanUsesConfiguredQueueAndAbsoluteDeadlines() {
        service = new StuckJobRecoveryService(repository, analysisService, 60, 20, 180, 360);
        when(repository.findExpiredIds(any(), any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();
        service.reclaimStuck();
        Instant after = Instant.now();
        var idle = ArgumentCaptor.forClass(Instant.class);
        var dispatch = ArgumentCaptor.forClass(Instant.class);
        var queued = ArgumentCaptor.forClass(Instant.class);
        var age = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findExpiredIds(idle.capture(), dispatch.capture(), queued.capture(), age.capture());
        assertThat(idle.getValue()).isBetween(before.minusSeconds(60), after.minusSeconds(60));
        assertThat(dispatch.getValue()).isBetween(before.minusSeconds(20), after.minusSeconds(20));
        assertThat(queued.getValue()).isBetween(before.minusSeconds(180), after.minusSeconds(180));
        assertThat(age.getValue()).isBetween(before.minusSeconds(360), after.minusSeconds(360));
    }

    @Test
    void noStuckJobsDoesNothing() {
        when(repository.findExpiredIds(any(Instant.class), any(Instant.class), any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.reclaimStuck();

        verifyNoInteractions(analysisService);
    }

    @Test
    void oneFailureDoesNotAbortTheRest() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findExpiredIds(any(Instant.class), any(Instant.class), any(Instant.class), any(Instant.class))).thenReturn(List.of(a, b));
        doThrow(new RuntimeException("db down")).when(analysisService).failStuck(eq(a), anyLong());

        service.reclaimStuck();

        verify(analysisService).failStuck(b, 600); // b still processed despite a throwing
    }
}
