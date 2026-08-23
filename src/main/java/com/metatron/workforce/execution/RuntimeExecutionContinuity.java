package com.metatron.workforce.execution;

/**
 * Implementation boundary marker for preserving execution continuity
 * when runtime instances change.
 *
 * Execution identity remains the continuity anchor.
 */
public record RuntimeExecutionContinuity(
        String executionId,
        String previousRuntimeId,
        String currentRuntimeId
) {}
