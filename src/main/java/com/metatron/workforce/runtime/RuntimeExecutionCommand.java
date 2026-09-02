package com.metatron.workforce.runtime;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.Objects;

/**
 * Runtime execution envelope.
 *
 * <p>Attribution identifies the execution, worker and replaceable runtime embodiment; {@code workSpec}
 * carries the actual executable Workforce work. The optional governance binding identifies the already
 * admitted Human/Organization/Objective/Assignment/Authorization/Dispatch context that a remote runtime
 * must re-check before it is allowed to create an effect. Runtime transport never mints those identities.</p>
 */
public record RuntimeExecutionCommand(
        String executionId,
        String workerId,
        String runtimeId,
        ExecutionWorkSpec workSpec,
        String humanId,
        String organizationContextId,
        String objectiveId,
        String assignmentReference,
        String authorizationReference,
        String dispatchReference,
        int dispatchAttempt) {

    public RuntimeExecutionCommand {
        executionId = requireText(executionId, "executionId");
        workerId = requireText(workerId, "workerId");
        runtimeId = requireText(runtimeId, "runtimeId");
        Objects.requireNonNull(workSpec, "workSpec");
        humanId = clean(humanId);
        organizationContextId = clean(organizationContextId);
        objectiveId = clean(objectiveId);
        assignmentReference = clean(assignmentReference);
        authorizationReference = clean(authorizationReference);
        dispatchReference = clean(dispatchReference);
        if (dispatchAttempt < 0) throw new IllegalArgumentException("dispatchAttempt must not be negative");
    }

    /**
     * Compatibility envelope for transport-only callers. It may be serialized, but the actual-effect
     * consumer will reject it until the upstream governed binding has been supplied.
     */
    public RuntimeExecutionCommand(String executionId, String workerId, String runtimeId, ExecutionWorkSpec workSpec) {
        this(executionId, workerId, runtimeId, workSpec, "", "", "", "", "", "", 0);
    }

    public boolean governedBound() {
        return !humanId.isBlank()
                && !organizationContextId.isBlank()
                && !objectiveId.isBlank()
                && !assignmentReference.isBlank()
                && !authorizationReference.isBlank()
                && !dispatchReference.isBlank()
                && dispatchAttempt > 0;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
