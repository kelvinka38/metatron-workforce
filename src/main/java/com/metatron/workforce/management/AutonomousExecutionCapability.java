package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.List;
import java.util.Objects;

/**
 * One executable Workforce-owned capability adapter available to autonomous management.
 *
 * Production composition places a governed allocation/admission boundary in front of every
 * capability. The delegate receives the Worker, Assignment and Authorization selected before the
 * effect is allowed to run. Dispatch identity is carried separately so Execution can own durable
 * attempts, leases and fencing without making Management the execution truth.
 */
public interface AutonomousExecutionCapability {
    String capabilityRef();

    default String capabilityDescription() { return capabilityRef(); }
    default String authorityReference() { return ""; }
    default String authorizationReference() { return ""; }
    default double minimumCapabilityLevel() { return 1.0; }
    default double requiredCapacity() { return 1.0; }
    default boolean supportsWorker(String workerId) { return true; }

    CapabilityResult execute(CapabilityRequest request);

    record CapabilityRequest(
            String humanId,
            String organizationContextId,
            String objectiveId,
            ExecutionWorkSpec workSpec,
            String allocatedWorkerId,
            String assignmentReference,
            String authorizationReference,
            String dispatchReference,
            int dispatchAttempt) {
        public CapabilityRequest {
            Objects.requireNonNull(humanId, "humanId");
            Objects.requireNonNull(organizationContextId, "organizationContextId");
            Objects.requireNonNull(objectiveId, "objectiveId");
            Objects.requireNonNull(workSpec, "workSpec");
            allocatedWorkerId = clean(allocatedWorkerId);
            assignmentReference = clean(assignmentReference);
            authorizationReference = clean(authorizationReference);
            dispatchReference = clean(dispatchReference);
            if (dispatchAttempt < 0) throw new IllegalArgumentException("dispatchAttempt must not be negative");
        }

        /** Compatibility request; production delegates receive allocation and dispatch bindings. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec) {
            this(humanId, organizationContextId, objectiveId, workSpec, "", "", "", "", 0);
        }

        /** Compatibility constructor for callers that only bind allocation. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec, String allocatedWorkerId,
                                 String assignmentReference, String authorizationReference) {
            this(humanId, organizationContextId, objectiveId, workSpec, allocatedWorkerId,
                    assignmentReference, authorizationReference, "", 0);
        }

        public CapabilityRequest withAllocation(String workerId, String assignmentRef, String authorizationRef) {
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    workerId, assignmentRef, authorizationRef, dispatchReference, dispatchAttempt);
        }

        public CapabilityRequest withDispatch(String dispatchRef, int attempt) {
            if (dispatchRef == null || dispatchRef.isBlank()) throw new IllegalArgumentException("dispatchRef required");
            if (attempt < 1) throw new IllegalArgumentException("dispatch attempt must be positive");
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    allocatedWorkerId, assignmentReference, authorizationReference, dispatchRef, attempt);
        }

        public boolean allocated() {
            return !allocatedWorkerId.isBlank() && !assignmentReference.isBlank() && !authorizationReference.isBlank();
        }

        public boolean dispatchBound() {
            return !dispatchReference.isBlank() && dispatchAttempt > 0;
        }

        /** Stable effect key adapters must use to make at-least-once dispatch safe. */
        public String idempotencyKey() {
            return objectiveId + ":work-step:" + workSpec.stepId();
        }

        private static String clean(String value) { return value == null ? "" : value.trim(); }
    }

    record CapabilityResult(
            boolean success,
            String workerId,
            String assignmentReference,
            String workReference,
            List<String> evidenceReferences,
            String summary) {
        public CapabilityResult {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(assignmentReference, "assignmentReference");
            Objects.requireNonNull(workReference, "workReference");
            Objects.requireNonNull(evidenceReferences, "evidenceReferences");
            Objects.requireNonNull(summary, "summary");
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }
}
