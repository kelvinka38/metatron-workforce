package com.metatron.workforce.workers;

import java.time.Instant;

public record WorkerContext(
        String taskId,
        String objective,
        Instant createdAt
) {}