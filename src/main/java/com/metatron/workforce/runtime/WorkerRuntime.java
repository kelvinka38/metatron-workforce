package com.metatron.workforce.runtime;

import com.metatron.workforce.evidence.EvidenceWriter;
import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;

import java.io.IOException;
import java.time.Instant;

public class WorkerRuntime {


    private final EvidenceWriter evidenceWriter =
            new EvidenceWriter();


    public WorkerResult execute(
            Worker worker,
            String taskId,
            String objective
    ) throws IOException {


        WorkerContext context =
                new WorkerContext(
                        taskId,
                        objective,
                        Instant.now()
                );


        WorkerResult result =
                worker.execute(context);


        evidenceWriter.write(
                taskId,
                result
        );


        return result;
    }
}