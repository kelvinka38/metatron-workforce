package com.metatron.workforce.workplace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorkerConversationExecutionClaimGuardTest {

    @Test
    void suppressesFabricatedExecutionClaimWithoutReceipt() {
        String original = "I have audited the BIOS repository and I am now generating a signed manifest.";
        String guarded = WorkerConversationExecutionClaimGuard.enforce(
                original,
                List.of("institutional-source:kelvinka38/metatron-institution@main:06_GATEWAY/SOT.md:blob=abc"));

        assertNotEquals(original, guarded);
        assertEquals(
                "Không có execution receipt / Observation evidence cho hành động đó trong context của Meeting này. "
                        + "Vì vậy Worker không được claim là đã hoặc đang thực hiện. Trạng thái đúng hiện tại: "
                        + "chỉ có thể phân tích/đề xuất; execution phải đi qua authority check → Execution/action → receipt → Observation/evidence.",
                guarded);
    }

    @Test
    void allowsProposalWithoutPretendingExecution() {
        String original = "I propose to audit the Gateway boundary against the canonical SoT.";
        assertEquals(original, WorkerConversationExecutionClaimGuard.enforce(original, List.of()));
    }

    @Test
    void allowsExecutionClaimOnlyWithSuccessfulExecutionEvidence() {
        String original = "I have executed the deployment.";
        assertEquals(original, WorkerConversationExecutionClaimGuard.enforce(
                original,
                List.of("action-fabric:action=github.workflow.production-deploy.dispatch:worker=WORKER-GATEWAY-DIRECTOR:"
                        + "assignment=a:authorization=auth:consequence=MUTATING:success=true")));
    }

    @Test
    void executionIdentityWithoutCompletedStateDoesNotAuthorizeExecutionClaim() {
        String original = "I have executed the Gateway audit.";
        String guarded = WorkerConversationExecutionClaimGuard.enforce(
                original,
                List.of("execution:EXEC-123", "gateway:audit:FAILED", "observation-report:OBS-123"));
        assertNotEquals(original, guarded);
    }

    @Test
    void executionIdentityWithCompletedStateAuthorizesExecutionClaim() {
        String original = "I have executed the Gateway audit.";
        assertEquals(original, WorkerConversationExecutionClaimGuard.enforce(
                original,
                List.of("execution:EXEC-123", "gateway:audit:COMPLETED")));
    }

    @Test
    void doesNotSuppressExplicitStatementThatExecutionDidNotOccur() {
        String original = "I have not executed the audit because there is no execution receipt.";
        assertEquals(original, WorkerConversationExecutionClaimGuard.enforce(original, List.of()));
    }
}
