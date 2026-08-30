package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.List;
import java.util.Objects;

/**
 * One executable Workforce-owned capability adapter available to autonomous management.
 * Implementations preserve their own authorization/admission boundary and return attributable evidence.
 */
public interface AutonomousExecutionCapability {
    String capabilityRef();

    default String capabilityDescription() { return capabilityRef(); }

    CapabilityResult execute(CapabilityRequest request);

    record CapabilityRequest(
            String humanId,
            String organizationContextId,
            String objectiveId,
            ExecutionWorkSpec workSpec) {
        public CapabilityRequest {
            Objects.requireNonNull(humanId, "humanId");
            Objects.requireNonNull(organizationContextId, "organizationContextId");
            Objects.requireNonNull(objectiveId, "objectiveId");
            Objects.requireNonNull(workSpec, "workSpec");
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
