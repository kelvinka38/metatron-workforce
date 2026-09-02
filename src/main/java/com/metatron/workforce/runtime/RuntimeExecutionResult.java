package com.metatron.workforce.runtime;

import java.util.List;

/** Attributable result of consuming one governed runtime execution command and creating its effect. */
public record RuntimeExecutionResult(
        String executionId,
        String runtimeId,
        String objectiveId,
        String workerId,
        String assignmentReference,
        String authorizationReference,
        String authorityReference,
        String capabilityRef,
        boolean success,
        String workReference,
        List<String> evidenceReferences,
        String summary) {

    public RuntimeExecutionResult {
        require(executionId, "executionId");
        require(runtimeId, "runtimeId");
        require(objectiveId, "objectiveId");
        require(workerId, "workerId");
        require(assignmentReference, "assignmentReference");
        require(authorizationReference, "authorizationReference");
        require(authorityReference, "authorityReference");
        require(capabilityRef, "capabilityRef");
        require(workReference, "workReference");
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        summary = summary == null ? "" : summary;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
