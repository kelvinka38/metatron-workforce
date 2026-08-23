package com.metatron.workforce.workers;

import java.time.Instant;

public record WorkerResult(
        String worker,
        String status,
        String evidence,
        Instant completedAt
){}