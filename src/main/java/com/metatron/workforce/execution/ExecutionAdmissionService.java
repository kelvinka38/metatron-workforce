package com.metatron.workforce.execution;

import java.util.HashMap;
import java.util.Map;

/**
 * Fail-closed execution admission.
 *
 * Required guidance is a precondition, not authority:
 * GUIDANCE != AUTHORIZATION and PUBLICATION != SOT.
 */
public final class ExecutionAdmissionService {

    public ExecutionState admit(ExecutionRequest request) {
        if (request.assignment() == null) {
            throw new IllegalStateException("assignment missing");
        }

        if (request.authorization() == null) {
            throw new IllegalStateException("authorization missing");
        }
        if (request.authorization().authorizationId() == null || request.authorization().authorizationId().isBlank()) {
            throw new IllegalStateException("authorization identifier missing");
        }
        if (request.authorization().workerId() == null || request.authorization().workerId().isBlank()) {
            throw new IllegalStateException("authorization worker missing");
        }
        if (!request.assignment().workerId().equals(request.authorization().workerId())) {
            throw new IllegalStateException("authorization worker mismatch");
        }

        verifyRequiredGuidance(request);
        return ExecutionState.ADMITTED;
    }

    private static void verifyRequiredGuidance(ExecutionRequest request) {
        if (request.assignment().requiredGuidance().isEmpty()) return;

        Map<String, GuidanceReceipt> receipts = new HashMap<>();
        for (GuidanceReceipt receipt : request.guidanceReceipts()) {
            GuidanceReceipt duplicate = receipts.put(receipt.guidanceId(), receipt);
            if (duplicate != null) {
                throw new IllegalStateException("duplicate_guidance_receipt:" + receipt.guidanceId());
            }
        }

        for (GuidanceRequirement requirement : request.assignment().requiredGuidance()) {
            GuidanceReceipt receipt = receipts.get(requirement.guidanceId());
            if (receipt == null) {
                throw new IllegalStateException("required_guidance_not_read:" + requirement.guidanceId());
            }
            if (!requirement.publicResourcePath().equals(receipt.publicResourcePath())) {
                throw new IllegalStateException("guidance_publication_mismatch:" + requirement.guidanceId());
            }
            if (!requirement.sourceRef().equals(receipt.sourceRef())) {
                throw new IllegalStateException("guidance_provenance_mismatch:" + requirement.guidanceId());
            }
            if (receipt.contentSha256() == null || receipt.contentSha256().isBlank()) {
                throw new IllegalStateException("guidance_evidence_missing:" + requirement.guidanceId());
            }
        }
    }
}
