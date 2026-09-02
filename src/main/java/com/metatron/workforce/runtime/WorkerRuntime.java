package com.metatron.workforce.runtime;

import com.metatron.workforce.evidence.EvidenceWriter;
import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

public class WorkerRuntime {
    private final EvidenceWriter evidenceWriter = new EvidenceWriter();

    public WorkerResult execute(Worker worker, String taskId, String objective) throws IOException {
        WorkerContext context = new WorkerContext(taskId, objective, Instant.now());
        WorkerResult result = worker.execute(context);
        return record(taskId, result);
    }

    /**
     * Persist a result produced by a governed runtime that does not use the legacy one-call Worker.execute shape.
     * This keeps runtime-evidence compatibility while allowing Cognitive Workers to execute effects through
     * Action Fabric instead of hiding all work behind one Worker.execute invocation.
     */
    public WorkerResult record(String taskId, WorkerResult result) throws IOException {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId required");
        Objects.requireNonNull(result, "result");
        evidenceWriter.write(taskId.trim(), result);
        return result;
    }
}
