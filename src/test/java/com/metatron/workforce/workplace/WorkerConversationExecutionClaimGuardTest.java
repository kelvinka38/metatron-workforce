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
                "Execution note: no durable receipt/evidence proves the suppressed action claim. "
                        + "That action remains proposed until it passes authority/authorization, Execution, and Observation/evidence.",
                guarded);
    }

    @Test
    void preservesStrategicPlanWhenOnlyOneSentenceMakesAnUnsupportedExecutionClaim() {
        String original = "I would run Gateway as an accountable 24/7 service owner. "
                + "I am currently deploying three replicas. "
                + "The operating plan covers demand, staffing, budget, SLOs, incident response and KPIs.";

        String guarded = WorkerConversationExecutionClaimGuard.enforce(original, List.of());

        assertNotEquals(original, guarded);
        assertEquals(true, guarded.contains("accountable 24/7 service owner"));
        assertEquals(true, guarded.contains("demand, staffing, budget, SLOs, incident response and KPIs"));
        assertEquals(false, guarded.contains("currently deploying three replicas"));
        assertEquals(true, guarded.contains("Execution note:"));
    }

    @Test
    void doesNotTreatPlanningDiscourseAsExecution() {
        String original = "I am starting with demand and capacity planning. I will create a 24/7 operating model next.";
        assertEquals(original, WorkerConversationExecutionClaimGuard.enforce(original, List.of()));
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
