package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class Phase6IntegrationTest {
    private static final Instant T0 = Instant.parse("2026-08-20T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-08-20T11:00:00Z");

    @Test
    void humanToHeadToWorkerToRuntimeExecutionRemainsAttributable() {
        Proposal proposal = new Proposal(
                "proposal-100", "human-001", "org-A", "EXECUTE_WORK", "farm-A",
                Proposal.State.DRAFT, T0, null, null, "message-001");

        ProposalService proposals = new ProposalService();
        proposal = proposals.startReview(proposals.submit(proposal));
        proposal = proposals.approve(proposal, T1, "head approved");

        AuthorizationRequest request = new AuthorizationRequest(
                "req-100", "worker-001", "farm-head", "EXECUTE_WORK", "farm-A",
                "org-A", "authority-head-001", "delegation-001", "policy-001",
                T1, T1, T3, "proposal-100");

        AuthorizationDecision authorization = new AuthorizationService().resolve(
                request, T1, "head-001", ignored -> true);
        assertTrue(authorization.usableAt(T1));

        ExecutionService executions = new ExecutionService();
        Execution execution = Execution.requested(
                "exec-100", request.requestId(), "worker-001", "assignment-100",
                authorization.authorizationId(), T1, "proposal-100");
        execution = executions.validating(execution);
        execution = executions.admit(execution, authorization, T1);
        execution = executions.start(execution, T2, ignored -> true);
        execution = executions.complete(execution, T3, "farm work completed");

        AttributionRecord attribution = new AttributionRecord(
                "attr-100", proposal.proposerId(), "head-001", "head-001",
                execution.workerId(), "runtime-100", execution.authorizationId(),
                execution.assignmentId(), execution.executionId(), "EXECUTE_WORK",
                T3, T3, "execution-evidence-100", proposal.evidenceReference());

        assertEquals(Proposal.State.APPROVED, proposal.state());
        assertEquals(Execution.State.COMPLETED, execution.state());
        assertEquals("human-001", attribution.originatingActorId());
        assertEquals("head-001", attribution.decisionActorId());
        assertEquals("worker-001", attribution.responsibleWorkerId());
        assertEquals("runtime-100", attribution.runtimeInstanceId());
        assertEquals("exec-100", attribution.executionId());
    }

    @Test
    void authorizationDoesNotOverrideInsufficientExecutionCapacity() {
        AuthorizationRequest request = new AuthorizationRequest(
                "req-101", "worker-001", "farm-head", "EXECUTE_WORK", "farm-A",
                "org-A", "authority-head-001", null, "policy-001",
                T1, T1, T3, "evidence-101");
        AuthorizationDecision authorization = new AuthorizationService().resolve(
                request, T1, "head-001", ignored -> true);

        ExecutionService executions = new ExecutionService();
        Execution execution = executions.validating(Execution.requested(
                "exec-101", request.requestId(), "worker-001", "assignment-101",
                authorization.authorizationId(), T1, "evidence-101"));
        execution = executions.admit(execution, authorization, T1);
        execution = executions.start(execution, T2, ignored -> false);

        assertEquals(AuthorizationDecision.Outcome.ALLOW, authorization.outcome());
        assertEquals(Execution.State.BLOCKED, execution.state());
        assertFalse(execution.terminal());
    }
}
