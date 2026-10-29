package com.deepfake.orchestrator.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class InputFileLocksTest {
    @Test void sameFileCannotBeClaimedDuringReleaseAndExceptionsReleaseTheLock() throws Exception {
        var locks = new InputFileLocks();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> locks.withFile("alice", "file", () -> {
                entered.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return "released";
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread()); waiting.countDown();
                return locks.withFile("alice", "file", () -> "claimed");
            });
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    second.isDone() || secondThread.get().getState() == Thread.State.WAITING);
            assertThat(second.isDone()).isFalse();
            assertThat(locks.withFile("bob", "different", () -> "other")).isEqualTo("other");
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("released");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("claimed");
        } finally { release.countDown(); }
        assertThatThrownBy(() -> locks.withFile("alice", "file", () -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(locks.withFile("alice", "file", () -> 1)).isEqualTo(1);
    }
}
