package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Channel-neutral boundary from semantic Human execution intent into Workforce-owned management.
 *
 * This handoff admits a request/objective for institutional coordination only. It never converts
 * Human intent into execution authority or authorization.
 */
public interface ExecutionObjectiveHandoff {
    /** Real executable capabilities currently exposed to autonomous management. */
    default List<String> capabilityCatalog() { return List.of(); }

    HandoffReceipt submit(
            String humanId,
            String organizationContextId,
            String caseId,
            String conversationId,
            String externalMessageReference,
            String channel,
            NormalizedRequest request);

    static ExecutionObjectiveHandoff unavailable() {
        return (humanId, organizationContextId, caseId, conversationId, externalMessageReference, channel, request) ->
                HandoffReceipt.blocked("EXECUTION_OBJECTIVE_HANDOFF_UNAVAILABLE");
    }

    record HandoffReceipt(
            boolean accepted,
            String objectiveId,
            String ownerWorkerId,
            String queueItemId,
            String objectiveStatus,
            String executionAdmissionState,
            String reason) {
        public HandoffReceipt {
            Objects.requireNonNull(objectiveId, "objectiveId");
            Objects.requireNonNull(ownerWorkerId, "ownerWorkerId");
            Objects.requireNonNull(queueItemId, "queueItemId");
            Objects.requireNonNull(objectiveStatus, "objectiveStatus");
            Objects.requireNonNull(executionAdmissionState, "executionAdmissionState");
            Objects.requireNonNull(reason, "reason");
        }

        public static HandoffReceipt blocked(String reason) {
            return new HandoffReceipt(false, "", "", "", "BLOCKED", "NOT_ADMITTED", reason);
        }
    }
}
