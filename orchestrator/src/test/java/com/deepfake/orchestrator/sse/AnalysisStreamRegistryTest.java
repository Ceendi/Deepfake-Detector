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
    @Test
    void replayClosesOnlyItsTargetAndLeavesOtherSubscriberRegistered() throws Exception {
        var first = registry.register(id);
        var late = registry.register(id);
        registry.sendResult(id, late, result());
        assertThatThrownBy(() -> late.send(SseEmitter.event().data("late")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> first.send(SseEmitter.event().data("still active"))).doesNotThrowAnyException();
        registry.sendResult(id, result());
        assertThatThrownBy(() -> first.send(SseEmitter.event().data("closed")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void subscriberJoiningDuringTerminalSendIsNotClosedWithoutReplay() {
        var newcomer = new java.util.concurrent.atomic.AtomicReference<SseEmitter>();
        var controlled = new AnalysisStreamRegistry() {
            boolean first = true;
            @Override protected SseEmitter createEmitter() {
                if (!first) return new SseEmitter();
                first = false;
                return new SseEmitter() {
                    @Override public void send(SseEventBuilder event) {
                        newcomer.set(register(id));
                    }
                };
            }
        };
        controlled.register(id);
        controlled.sendResult(id, result());
        assertThatCode(() -> newcomer.get().send(SseEmitter.event().data("still active")))
                .doesNotThrowAnyException();
        controlled.sendResult(id, newcomer.get(), result());
        assertThatThrownBy(() -> newcomer.get().send(SseEmitter.event().data("closed")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedDeliveryRemovesEmitterWithoutDroppingHealthySubscriber() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var controlled = new AnalysisStreamRegistry() {
            boolean first = true;
            @Override protected SseEmitter createEmitter() {
                if (!first) return new SseEmitter();
                first = false;
                return new SseEmitter() {
                    @Override public void send(SseEventBuilder event) throws java.io.IOException {
                        attempts.incrementAndGet();
                        throw new java.io.IOException("Client disconnected");
                    }
                };
            }
        };
        controlled.register(id);
        var healthy = controlled.register(id);
        controlled.heartbeat();
        controlled.heartbeat();
        assertThat(attempts).hasValue(1);
        controlled.sendResult(id, result());
        assertThatThrownBy(() -> healthy.send(SseEmitter.event().data("closed")))
                .isInstanceOf(IllegalStateException.class);
    }

    private AnalysisResultEvent result() {
        return new AnalysisResultEvent(id.toString(), "COMPLETED", "FAKE", new BigDecimal("0.8"));
    }

}
