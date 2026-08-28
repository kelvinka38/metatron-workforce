package com.metatron.workforce.execution;

import java.time.Instant;
import java.util.List;

public record ExecutionRequest(
        String executionId,
        Assignment assignment,
        Authorization authorization,
        List<GuidanceReceipt> guidanceReceipts,
        Instant createdAt
) {
    public ExecutionRequest {
        guidanceReceipts = guidanceReceipts == null ? List.of() : List.copyOf(guidanceReceipts);
    }

    /** Backward-compatible request for assignments with no required guidance. */
    public ExecutionRequest(
            String executionId,
            Assignment assignment,
            Authorization authorization,
            Instant createdAt
    ) {
        this(executionId, assignment, authorization, List.of(), createdAt);
    }
}
