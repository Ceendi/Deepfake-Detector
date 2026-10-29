package com.deepfake.orchestrator.service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Bounded locks for the deliberately single orchestrator, covering remote claim through DB commit. */
@Component
public class InputFileLocks {
    private final ReentrantLock[] stripes = new ReentrantLock[256];

    public InputFileLocks() {
        java.util.Arrays.setAll(stripes, ignored -> new ReentrantLock());
    }

    public <T> T withFile(String userId, String fileId, Supplier<T> work) {
        ReentrantLock lock = stripes[Math.floorMod(java.util.Objects.hash(userId, fileId), stripes.length)];
        lock.lock();
        try { return work.get(); }
        finally { lock.unlock(); }
    }
}
