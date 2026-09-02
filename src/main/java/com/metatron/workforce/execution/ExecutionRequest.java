package com.metatron.workforce.execution;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.List;

/**
 * Canonical execution-admission request.
 *
 * <p>Execution identity, Assignment and Authorization are not sufficient to execute. The request
 * must also carry the actual Work that will cross the Runtime boundary. Legacy constructors remain
 * source-compatible only so old callers fail closed in {@link ExecutionAdmissionService} until they
 * supply real Work.</p>
 */
public record ExecutionRequest(
        String executionId,
        Assignment assignment,
        Authorization authorization,
        ExecutionWorkSpec workSpec,
        List<GuidanceReceipt> guidanceReceipts,
        Instant createdAt
) {
    public ExecutionRequest {
        guidanceReceipts = guidanceReceipts == null ? List.of() : List.copyOf(guidanceReceipts);
    }

    public ExecutionRequest(
            String executionId,
            Assignment assignment,
            Authorization authorization,
            ExecutionWorkSpec workSpec,
            Instant createdAt
    ) {
        this(executionId, assignment, authorization, workSpec, List.of(), createdAt);
    }

    /** @deprecated canonical execution must provide actual Work. */
    @Deprecated
    public ExecutionRequest(
            String executionId,
            Assignment assignment,
            Authorization authorization,
            List<GuidanceReceipt> guidanceReceipts,
            Instant createdAt
    ) {
        this(executionId, assignment, authorization, null, guidanceReceipts, createdAt);
    }

    /** @deprecated canonical execution must provide actual Work. */
    @Deprecated
    public ExecutionRequest(
            String executionId,
            Assignment assignment,
            Authorization authorization,
            Instant createdAt
    ) {
        this(executionId, assignment, authorization, null, List.of(), createdAt);
    }
}
