package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionAuditRecord(
        String executionId,
        String event,
        Instant timestamp
) {}
