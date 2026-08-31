package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.List;
import java.util.Objects;

/**
 * One executable Workforce-owned capability adapter available to autonomous management.
 *
 * Production composition places a governed allocation/admission boundary in front of every
 * capability. The delegate receives the Worker, Assignment and Authorization selected before the
 * effect is allowed to run. Legacy four-argument requests remain available for deterministic unit
 * tests and are intentionally allocation-empty until they cross that boundary.
 */
public interface AutonomousExecutionCapability {
    String capabilityRef();

    default String capabilityDescription() { return capabilityRef(); }

    /** Canonical authority reference used when this capability is assigned. */
    default String authorityReference() { return ""; }

    /** Canonical authorization reference admitted before this capability may execute. */
    default String authorizationReference() { return ""; }

    /** Minimum attested capability level required for allocation. */
    default double minimumCapabilityLevel() { return 1.0; }

    /** Finite Workforce capacity reserved for one dispatch. */
    default double requiredCapacity() { return 1.0; }

    /** Optional runtime affinity. Generic capabilities accept any eligible Worker. */
    default boolean supportsWorker(String workerId) { return true; }

    CapabilityResult execute(CapabilityRequest request);

    record CapabilityRequest(
            String humanId,
            String organizationContextId,
            String objectiveId,
            ExecutionWorkSpec workSpec,
            String allocatedWorkerId,
            String assignmentReference,
            String authorizationReference) {
        public CapabilityRequest {
            Objects.requireNonNull(humanId, "humanId");
            Objects.requireNonNull(organizationContextId, "organizationContextId");
            Objects.requireNonNull(objectiveId, "objectiveId");
            Objects.requireNonNull(workSpec, "workSpec");
            allocatedWorkerId = allocatedWorkerId == null ? "" : allocatedWorkerId.trim();
            assignmentReference = assignmentReference == null ? "" : assignmentReference.trim();
            authorizationReference = authorizationReference == null ? "" : authorizationReference.trim();
        }

        /** Compatibility request; production delegates receive an allocated request. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec) {
            this(humanId, organizationContextId, objectiveId, workSpec, "", "", "");
        }

        public CapabilityRequest withAllocation(String workerId, String assignmentRef, String authorizationRef) {
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    workerId, assignmentRef, authorizationRef);
        }

        public boolean allocated() {
            return !allocatedWorkerId.isBlank() && !assignmentReference.isBlank() && !authorizationReference.isBlank();
        }

        /** Stable effect key adapters must use to make at-least-once dispatch safe. */
        public String idempotencyKey() {
            return objectiveId + ":work-step:" + workSpec.stepId();
        }
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
