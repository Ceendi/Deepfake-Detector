package com.deepfake.orchestrator.sse;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.deepfake.orchestrator.dto.sse.*;
import com.deepfake.orchestrator.exception.TooManyStreamsException;

class AnalysisStreamRegistryTest {
    final UUID id=UUID.randomUUID();
    final RecordingRegistry registry=new RecordingRegistry(SseLimits.defaults());
    @AfterEach void close() {
        registry.shutdown(); registry.emitters.forEach(e -> RegistryTestHarness.completed(registry,e));
    }
    @Test void terminalBeforeMvcReadyStaysInFixedMailboxAndWinsOverProgress() {
        var emitter=(RecordingEmitter)registry.register(id,"alice");
        for(int i=0;i<10000;i++) registry.sendProgress(id,progress(i));
        var session=registry.session(emitter);
        synchronized(session) { assertThat(session.video.progress()).isEqualTo(9999); }
        registry.sendResult(id,result()); registry.heartbeat();
        assertThat(emitter.events).isEmpty(); assertThat(session.writerStarted).isFalse();
        RegistryTestHarness.ready(registry,emitter);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(emitter.events).containsExactly(result()));
    }
    @Test void mailboxCoalescesEachSourceAndHeartbeatWithoutSpringEarlySendAttempts() {
        var emitter=(RecordingEmitter)registry.register(id,"alice"); var session=registry.session(emitter);
        for(int i=0;i<10000;i++) {
            registry.sendProgress(id,progress(i));
            registry.sendProgress(id,new AnalysisProgressEvent(id.toString(),"audio",i,"stage","PROCESSING"));
            registry.heartbeat();
        }
        assertThat(emitter.events).isEmpty();
        synchronized(session) { assertThat(session.video.progress()).isEqualTo(9999); assertThat(session.audio.progress()).isEqualTo(9999); assertThat(session.heartbeat).isTrue(); }
        RegistryTestHarness.ready(registry,emitter);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(emitter.events).hasSize(3));
    }
    @Test void replayTargetsOnlyItsSubscriberAndTerminalDetachPreservesNewSubscriber() {
        var first=(RecordingEmitter)registry.register(id,"alice");
        var replay=(RecordingEmitter)registry.register(id,"alice");
        registry.sendResult(id,replay,result());
        RegistryTestHarness.ready(registry,first); RegistryTestHarness.ready(registry,replay);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(replay.events).containsExactly(result()));
        assertThat(first.events).doesNotContain(result());
        registry.sendResult(id,result());
        var newcomer=(RecordingEmitter)registry.register(id,"alice"); RegistryTestHarness.ready(registry,newcomer);
        registry.sendProgress(id,progress(7));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(newcomer.events).contains(progress(7)));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(first.events).contains(result()));
    }
    @Test void quotasAreDistinctAndAdmissionDoesNotFollowRoutingDetach() {
        var limited=new RecordingRegistry(new SseLimits(3,2,1,Duration.ofMinutes(10),Duration.ofSeconds(15),Duration.ofSeconds(5)));
        var a=limited.register(id,"alice");
        assertThatThrownBy(() -> limited.register(id,"bob")).isInstanceOf(TooManyStreamsException.class);
        limited.register(UUID.randomUUID(),"alice");
        assertThatThrownBy(() -> limited.register(UUID.randomUUID(),"alice")).isInstanceOf(TooManyStreamsException.class);
        limited.register(UUID.randomUUID(),"bob");
        assertThatThrownBy(() -> limited.register(UUID.randomUUID(),"carol")).isInstanceOf(TooManyStreamsException.class);
        limited.sendResult(id,result());
        assertThatThrownBy(() -> limited.register(id,"alice")).isInstanceOf(TooManyStreamsException.class);
        RegistryTestHarness.completed(limited,a);
        assertThatCode(() -> limited.register(id,"alice")).doesNotThrowAnyException();
        limited.shutdown();
    }
    @Test void concurrentAdmissionReservesExactlyOneRemainingSlot() throws Exception {
        var limited=new RecordingRegistry(new SseLimits(1,1,1,Duration.ofMinutes(10),Duration.ofSeconds(15),Duration.ofSeconds(5)));
        var barrier=new CyclicBarrier(8);var accepted=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++)tasks.add(pool.submit(() -> {
                try {barrier.await(2,TimeUnit.SECONDS);limited.register(id,"alice");accepted.incrementAndGet();}
                catch(TooManyStreamsException expected){}catch(Exception failure){throw new IllegalStateException(failure);}
            }));
            for(var task:tasks)task.get(3,TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(1);assertThat(limited.admitted()).isEqualTo(1);limited.shutdown();
    }
    @Test void physicalCompletionCannotRecycleCapacityUntilBlockedWriterExits() throws Exception {
        var entered=new CountDownLatch(1); var unblock=new CountDownLatch(1);
        var blocking=new RecordingRegistry(new SseLimits(1,1,1,Duration.ofMinutes(10),Duration.ofSeconds(15),Duration.ofMillis(1))) {
            @Override protected SseEmitter createEmitter() {
                return new SseEmitter() { @Override public void send(SseEventBuilder event) throws IOException {
                    entered.countDown(); try { unblock.await(5,TimeUnit.SECONDS); } catch(InterruptedException ex) { throw new IOException(ex); }
                }};
            }
        };
        var emitter=blocking.register(id,"alice"); RegistryTestHarness.ready(blocking,emitter); blocking.heartbeat();
        assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        blocking.observe(); RegistryTestHarness.completed(blocking,emitter);
        assertThat(blocking.admitted()).isEqualTo(1);
        assertThatThrownBy(() -> blocking.register(id,"alice")).isInstanceOf(TooManyStreamsException.class);
        unblock.countDown(); await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(blocking.admitted()).isZero());
        blocking.shutdown();
    }
    @Test void writerExitStillWaitsForPhysicalCompletionAndReleaseIsIdempotent() {
        var emitter=(RecordingEmitter)registry.register(id,"alice"); var session=registry.session(emitter);
        registry.sendResult(id,result()); RegistryTestHarness.ready(registry,emitter);
        await().atMost(Duration.ofSeconds(2)).until(() -> session.writerExited);
        assertThat(registry.admitted()).isEqualTo(1);
        session.physicalCompletion(); session.physicalCompletion(); assertThat(registry.admitted()).isZero();
    }
    @Test void initializationFailureAndCompletionBeforeGateNeverStartOrphanWriter() {
        var emitter=registry.register(id,"alice"); var session=registry.session(emitter);
        session.bind(); session.initializationFailed(); session.initialized(); session.ready();
        assertThat(session.writerStarted).isFalse(); assertThat(registry.admitted()).isEqualTo(1);
        session.physicalCompletion(); session.ready(); assertThat(registry.admitted()).isZero();
    }
    @Test void failureOfOneWriterDoesNotDropHealthySubscriber() {
        var failed=(RecordingEmitter)registry.register(id,"alice"); failed.fail=true;
        var healthy=(RecordingEmitter)registry.register(id,"alice");
        RegistryTestHarness.ready(registry,failed); RegistryTestHarness.ready(registry,healthy);
        registry.heartbeat();
        await().atMost(Duration.ofSeconds(2)).until(() -> registry.session(failed).writerExited);
        registry.sendResult(id,result());
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(healthy.events).contains(result()));
        assertThat(failed.events).isEmpty();
    }
    @Test void failedFreshSnapshotDiscardsUnreturnedSessionWithoutNetworkCall() {
        var emitter=(RecordingEmitter)registry.register(id,"alice");
        registry.discard(id,emitter);
        assertThat(registry.admitted()).isZero(); assertThat(emitter.events).isEmpty();
    }
    @Test void tenMinuteDefaultAndOversizedAdvisoryRetentionCap() {
        var emitter=registry.register(id,"alice"); assertThat(emitter.getTimeout()).isEqualTo(600000);
        registry.sendProgress(id,new AnalysisProgressEvent(id.toString(),"video",1,"x".repeat(513),"PROCESSING"));
        assertThat(registry.session(emitter).video).isNull();
    }
    AnalysisProgressEvent progress(int value) { return new AnalysisProgressEvent(id.toString(),"video",value,"stage","PROCESSING"); }
    AnalysisResultEvent result() { return new AnalysisResultEvent(id.toString(),"COMPLETED","FAKE",BigDecimal.ONE); }
    static class RecordingRegistry extends AnalysisStreamRegistry {
        final List<RecordingEmitter> emitters=new CopyOnWriteArrayList<>();
        RecordingRegistry(SseLimits limits) { super(limits); }
        @Override protected SseEmitter createEmitter() { var emitter=new RecordingEmitter(); emitters.add(emitter); return emitter; }
    }
    static class RecordingEmitter extends SseEmitter {
        final List<Object> events=new CopyOnWriteArrayList<>(); boolean fail;
        RecordingEmitter() { super(600000L); }
        @Override public void send(SseEventBuilder event) throws IOException {
            if(fail) throw new IOException("peer closed");
            event.build().forEach(item -> { if(item.getData() instanceof AnalysisProgressEvent || item.getData() instanceof AnalysisResultEvent) events.add(item.getData()); });
            if(event.build().stream().noneMatch(item -> item.getData() instanceof AnalysisProgressEvent || item.getData() instanceof AnalysisResultEvent)) events.add("hb");
        }
    }
}
