package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.deepfake.orchestrator.repository.AnalysisRepository;

@ExtendWith(MockitoExtension.class)
class ResultPayloadValidationTest {
    @Mock AnalysisRepository repository;
    @Mock IdempotencyGuard idempotency;
    @InjectMocks AnalysisService service;

    static Stream<Object> invalidIds() { return Stream.of(null, "invalid", 42, "1-1-1-1-1"); }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void rejectsMalformedAnalysisIdBeforeDedupOrDatabase(Object id) {
        var payload = new HashMap<String, Object>();
        payload.put("analysis_id", id);
        payload.put("source", "video");
        payload.put("status", "COMPLETED");
        payload.put("result", Map.of("prob_fake", 0.8));
        assertThatThrownBy(() -> service.handleResult(payload)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(idempotency, repository);
    }

    static Stream<Map<String, Object>> invalidResults() {
        var base = Map.<String, Object>of("analysis_id", UUID.randomUUID().toString(),
                "source", "video", "status", "COMPLETED");
        return Stream.of(null, "wrong-shape", Map.of(), Map.of("prob_fake", "NaN"),
                Map.of("prob_fake", "wrong"), Map.of("prob_fake", 1.1), Map.of("prob_fake", -0.1))
                .map(result -> { var payload = new HashMap<>(base); payload.put("result", result); return payload; });
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void rejectsMalformedCompletedPayloadBeforeDedupOrDatabase(Map<String, Object> payload) {
        assertThatThrownBy(() -> service.handleResult(payload)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(idempotency, repository);
    }
}
