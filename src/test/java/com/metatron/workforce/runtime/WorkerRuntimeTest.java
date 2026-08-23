package com.metatron.workforce.runtime;

import com.metatron.workforce.workers.WorkerResult;
import com.metatron.workforce.workers.audit.RepositoryAuditWorker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class WorkerRuntimeTest {


    @Test
    void should_execute_worker_through_runtime() {


        WorkerRuntime runtime =
                new WorkerRuntime();


        WorkerResult result =
                runtime.execute(
                        new RepositoryAuditWorker(),
                        "TASK-001",
                        "Audit workforce repository after G12 activation"
                );


        assertEquals(
                "PASS",
                result.status()
        );


        assertEquals(
                "RepositoryAuditWorker",
                result.worker()
        );
    }
}