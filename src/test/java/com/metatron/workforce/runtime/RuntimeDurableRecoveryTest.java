package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeDurableRecoveryTest {

    @Test
    void runtimeIdentityAndStateSurviveJvmBoundary(@TempDir Path persistenceRoot) {
        WorkforceRuntime firstProcess = new WorkforceRuntime(persistenceRoot);
        RuntimeInstance running = firstProcess.createWorkerRuntime("worker-001");
        String runtimeId = running.runtimeId();
        firstProcess.startRuntime(runtimeId);

        WorkforceRuntime replacementProcess = new WorkforceRuntime(persistenceRoot);
        RuntimeInstance recovered = replacementProcess.recoverRuntime(runtimeId);

        assertNotNull(recovered);
        assertEquals(runtimeId, recovered.runtimeId());
        assertEquals("worker-001", recovered.workerId());
        assertEquals(RuntimeState.RUNNING, recovered.state());
    }

    @Test
    void failedRuntimeRemainsFailedAfterProcessReplacement(@TempDir Path persistenceRoot) {
        WorkforceRuntime firstProcess = new WorkforceRuntime(persistenceRoot);
        RuntimeInstance failed = firstProcess.createWorkerRuntime("worker-002");
        String runtimeId = failed.runtimeId();
        firstProcess.failRuntime(runtimeId);

        WorkforceRuntime replacementProcess = new WorkforceRuntime(persistenceRoot);
        RuntimeInstance recovered = replacementProcess.recoverRuntime(runtimeId);

        assertEquals(runtimeId, recovered.runtimeId());
        assertEquals("worker-002", recovered.workerId());
        assertEquals(RuntimeState.FAILED, recovered.state());
    }
}
