package com.metatron.workforce.phase6;

import com.metatron.workforce.phase5.ExecutionFeasibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase6IntegrationTest {
    private static final Instant REQUESTED = Instant.parse("2026-08-20T08:00:00Z");
    private static final Instant APPROVED = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-20T11:00:00Z");

    private WorkProposal proposal() {
        return new WorkProposal("proposal-100", "worker-001", "work-100", "EXECUTE_WORK", "farm-A", "org-A", REQUESTED);
    }

    private ApprovalDecision approval() {
        return new ApprovalDecision("decision-100", "proposal-100", "head-001", true, APPROVED,
                "authority-head-001", "head approved");
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("worker-001", "worker", "authority-head-001", "farm-A", "EXECUTE_WORK",
                "org-A", STARTED, "resource-100");
    }

    private AuthorizationService authorization(boolean allowed) {
        return new AuthorizationService(ignored -> allowed
                ? Phase6AuthorizationPolicy.Decision.allowed("auth-100")
                : Phase6AuthorizationPolicy.Decision.denied("policy-100", "forbidden"));
    }

    @Test
    void proposalApprovalAuthorizationExecutionAndAttributionRemainConnected() {
        Proposal proposal = new Proposal(
                "proposal-100", "human-001", "org-A", "EXECUTE_WORK", "farm-A",
                Proposal.State.DRAFT, REQUESTED, null, null, "evidence-100");
        ProposalService proposals = new ProposalService();
        proposal = proposals.approve(proposals.startReview(proposals.submit(proposal)), APPROVED, "head approved");

        ExecutionRecord execution = new ExecutionService().execute(
                proposal(), approval(), request(), authorization(true),
                new ExecutionFeasibility(ExecutionFeasibility.Status.FEASIBLE, 0, 0, List.of()),
                p -> ExecutionService.ExecutionResult.success(List.of("instruction"), List.of("completed-work")),
                "exec-100", STARTED, COMPLETED, null);

        AttributionChain chain = new AttributionChain(List.of(
                new AttributionChain.Entry("human-001", AttributionChain.Entry.Kind.HUMAN_INSTRUCTION, "instruction-100", REQUESTED),
                new AttributionChain.Entry("head-001", AttributionChain.Entry.Kind.DECISION, "decision-100", APPROVED),
                new AttributionChain.Entry("authority-head-001", AttributionChain.Entry.Kind.AUTHORIZATION, "auth-100", STARTED),
                new AttributionChain.Entry("worker-001", AttributionChain.Entry.Kind.WORKER_EXECUTION, "exec-100", STARTED),
                new AttributionChain.Entry("runtime-100", AttributionChain.Entry.Kind.RUNTIME, "runtime-100", STARTED),
                new AttributionChain.Entry("system", AttributionChain.Entry.Kind.OUTCOME, "exec-100", COMPLETED)));

        assertEquals(Proposal.State.APPROVED, proposal.state());
        assertEquals(ExecutionRecord.Status.SUCCEEDED, execution.status());
        assertEquals("auth-100", execution.authorizationReference());
        assertEquals(6, chain.entries().size());
        assertEquals(AttributionChain.Entry.Kind.OUTCOME, chain.entries().get(5).kind());
    }

    @Test
    void authorizationDoesNotOverrideInsufficientExecutionCapacity() {
        ExecutionFeasibility blocked = new ExecutionFeasibility(
                ExecutionFeasibility.Status.BLOCKED, 0, 1, List.of("capacity-insufficient"));

        ExecutionRecord execution = new ExecutionService().execute(
                proposal(), approval(), request(), authorization(true), blocked,
                p -> fail("executor must not run"), "exec-101", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.BLOCKED, execution.status());
        assertEquals("capacity-insufficient", execution.failureReason());
        assertEquals("auth-100", execution.authorizationReference());
    }
}
