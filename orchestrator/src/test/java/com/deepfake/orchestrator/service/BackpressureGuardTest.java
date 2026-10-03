package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.deepfake.orchestrator.repository.AnalysisRepository;

class BackpressureGuardTest {

    @Test
    void invalidLimitsAreRejectedAtStartup() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = mock(AnalysisRepository.class);
        assertThatThrownBy(() -> new BackpressureGuard(jdbc, repository, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackpressureGuard(jdbc, repository, 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
