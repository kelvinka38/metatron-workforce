package com.metatron.workforce.correlation;

/**
 * Correlation context preserving institutional and implementation references.
 */
public record ExecutionCorrelation(
        String workerId,
        String assignmentId,
        String authorizationId,
        String executionId,
        String runtimeId
) {}
