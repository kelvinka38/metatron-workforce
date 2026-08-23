package com.metatron.workforce.execution;

import java.time.Instant;

public final class ExecutionAuditService {

    public ExecutionAuditRecord record(
            String executionId,
            String event
    ) {
        return new ExecutionAuditRecord(
                executionId,
                event,
                Instant.now()
        );
    }
}
