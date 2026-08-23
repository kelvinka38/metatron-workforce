package com.metatron.workforce.runtime;

import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;

import java.time.Instant;

public class WorkerRuntime {

    public WorkerResult execute(
            Worker worker,
            String taskId,
            String objective
    ) {

        WorkerContext context =
                new WorkerContext(
                        taskId,
                        objective,
                        Instant.now()
                );

        return worker.execute(context);
    }
}