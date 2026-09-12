package com.metatron.workforce.management;

import com.metatron.workforce.execution.governance.GovernanceExecutionContext;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.List;
import java.util.Objects;

/**
 * One executable Workforce-owned capability adapter available to autonomous management.
 *
 * Production composition places governed allocation/admission before every capability. Upstream
 * scheduling identity remains explicit so the infrastructure resource scheduler can prove it only
 * decides WHEN/WHERE for work already selected by Management.
 */
public interface AutonomousExecutionCapability {
    String capabilityRef();

    default String capabilityDescription() { return capabilityRef(); }
    default String authorityReference() { return ""; }
    default String authorizationReference() { return ""; }
    default double minimumCapabilityLevel() { return 1.0; }
    default double requiredCapacity() { return 1.0; }
    default boolean supportsWorker(String workerId) { return true; }

    default boolean supportsWorker(String workerId, ExecutionWorkSpec workSpec) {
        Objects.requireNonNull(workSpec, "workSpec");
        return supportsWorker(workerId);
    }

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
            int dispatchAttempt,
            String schedulingDecisionReference,
            String executionAttemptId,
            long executionAttemptFencingToken,
            GovernanceExecutionContext governanceContext) {
        public CapabilityRequest {
            Objects.requireNonNull(humanId, "humanId");
            Objects.requireNonNull(organizationContextId, "organizationContextId");
            Objects.requireNonNull(objectiveId, "objectiveId");
            Objects.requireNonNull(workSpec, "workSpec");
            allocatedWorkerId = clean(allocatedWorkerId);
            assignmentReference = clean(assignmentReference);
            authorizationReference = clean(authorizationReference);
            dispatchReference = clean(dispatchReference);
            schedulingDecisionReference = clean(schedulingDecisionReference);
            executionAttemptId = clean(executionAttemptId);
            if (dispatchAttempt < 0) throw new IllegalArgumentException("dispatchAttempt must not be negative");
            if (executionAttemptFencingToken < 0) throw new IllegalArgumentException("executionAttemptFencingToken must not be negative");
            if (executionAttemptId.isBlank() != (executionAttemptFencingToken == 0)) {
                throw new IllegalArgumentException("execution attempt identity and fence must be configured together");
            }
        }

        /** Source-compatible old full-shape constructor. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec, String allocatedWorkerId,
                                 String assignmentReference, String authorizationReference,
                                 String dispatchReference, int dispatchAttempt,
                                 GovernanceExecutionContext governanceContext) {
            this(humanId, organizationContextId, objectiveId, workSpec, allocatedWorkerId,
                    assignmentReference, authorizationReference, dispatchReference, dispatchAttempt,
                    "", "", 0, governanceContext);
        }

        /** Source-compatible full shape with explicit execution attempt from the previous contract. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec, String allocatedWorkerId,
                                 String assignmentReference, String authorizationReference,
                                 String dispatchReference, int dispatchAttempt,
                                 String executionAttemptId, long executionAttemptFencingToken,
                                 GovernanceExecutionContext governanceContext) {
            this(humanId, organizationContextId, objectiveId, workSpec, allocatedWorkerId,
                    assignmentReference, authorizationReference, dispatchReference, dispatchAttempt,
                    "", executionAttemptId, executionAttemptFencingToken, governanceContext);
        }

        /** Source-compatible primary-shape constructor used by existing callers. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec, String allocatedWorkerId,
                                 String assignmentReference, String authorizationReference,
                                 String dispatchReference, int dispatchAttempt) {
            this(humanId, organizationContextId, objectiveId, workSpec, allocatedWorkerId,
                    assignmentReference, authorizationReference, dispatchReference, dispatchAttempt,
                    "", "", 0, null);
        }

        /** Compatibility request; production delegates receive allocation and dispatch bindings. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec) {
            this(humanId, organizationContextId, objectiveId, workSpec,
                    "", "", "", "", 0, "", "", 0, null);
        }

        /** Compatibility constructor for callers that only bind allocation. */
        public CapabilityRequest(String humanId, String organizationContextId, String objectiveId,
                                 ExecutionWorkSpec workSpec, String allocatedWorkerId,
                                 String assignmentReference, String authorizationReference) {
            this(humanId, organizationContextId, objectiveId, workSpec, allocatedWorkerId,
                    assignmentReference, authorizationReference, "", 0, "", "", 0, null);
        }

        public CapabilityRequest withAllocation(String workerId, String assignmentRef, String authorizationRef) {
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    workerId, assignmentRef, authorizationRef, dispatchReference, dispatchAttempt,
                    schedulingDecisionReference, executionAttemptId, executionAttemptFencingToken, governanceContext);
        }

        public CapabilityRequest withDispatch(String dispatchRef, int attempt) {
            if (dispatchRef == null || dispatchRef.isBlank()) throw new IllegalArgumentException("dispatchRef required");
            if (attempt < 1) throw new IllegalArgumentException("dispatch attempt must be positive");
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    allocatedWorkerId, assignmentReference, authorizationReference, dispatchRef, attempt,
                    schedulingDecisionReference, executionAttemptId, executionAttemptFencingToken, governanceContext);
        }

        public CapabilityRequest withSchedulingDecision(String decisionRef) {
            if (decisionRef == null || decisionRef.isBlank()) throw new IllegalArgumentException("decisionRef required");
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    allocatedWorkerId, assignmentReference, authorizationReference, dispatchReference, dispatchAttempt,
                    decisionRef, executionAttemptId, executionAttemptFencingToken, governanceContext);
        }

        public CapabilityRequest withExecutionAttempt(String attemptId, long fencingToken) {
            if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("attemptId required");
            if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    allocatedWorkerId, assignmentReference, authorizationReference, dispatchReference, dispatchAttempt,
                    schedulingDecisionReference, attemptId, fencingToken, governanceContext);
        }

        public CapabilityRequest withGovernance(GovernanceExecutionContext context) {
            return new CapabilityRequest(humanId, organizationContextId, objectiveId, workSpec,
                    allocatedWorkerId, assignmentReference, authorizationReference, dispatchReference, dispatchAttempt,
                    schedulingDecisionReference, executionAttemptId, executionAttemptFencingToken,
                    Objects.requireNonNull(context, "context"));
        }

        public boolean allocated() {
            return !allocatedWorkerId.isBlank() && !assignmentReference.isBlank() && !authorizationReference.isBlank();
        }
        public boolean dispatchBound() { return !dispatchReference.isBlank() && dispatchAttempt > 0; }
        public boolean schedulingBound() { return !schedulingDecisionReference.isBlank(); }
        public boolean executionAttemptBound() { return !executionAttemptId.isBlank() && executionAttemptFencingToken > 0; }
        public boolean governanceBound() { return governanceContext != null; }

        /** Stable effect key adapters must use to make at-least-once dispatch safe. */
        public String idempotencyKey() { return objectiveId + ":work-step:" + workSpec.stepId(); }

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
