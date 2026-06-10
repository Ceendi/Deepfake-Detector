package com.deepfake.orchestrator.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;

class AnalysisStreamRegistryTest {

    private final AnalysisStreamRegistry registry = new AnalysisStreamRegistry();
    private final UUID id = UUID.randomUUID();

    @Test
    void registerUsesTenMinuteTimeout() {
        SseEmitter emitter = registry.register(id);
        assertThat(emitter.getTimeout()).isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    @Test
    void sendToOpenStreamBuffersWithoutError() {
        registry.register(id);
        assertThatCode(() -> registry.sendResult(id,
                new AnalysisResultEvent(id.toString(), "COMPLETED", "FAKE", new BigDecimal("0.8"))))
                .doesNotThrowAnyException();
    }

    @Test
    void completeClosesEmitterSoFurtherSendsFail() {
        SseEmitter emitter = registry.register(id);
        registry.complete(id);
        assertThatThrownBy(() -> emitter.send(SseEmitter.event().data("late")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeAfterCloseIsNoOp() {
        registry.register(id);
        registry.complete(id);
        assertThatCode(() -> registry.complete(id)).doesNotThrowAnyException();
    }

    @Test
    void sendToUnknownAnalysisIsNoOp() {
        assertThatCode(() -> registry.sendProgress(UUID.randomUUID(),
                new AnalysisProgressEvent(id.toString(), "video", 50, "INFERENCE", "PROCESSING")))
                .doesNotThrowAnyException();
    }
}
