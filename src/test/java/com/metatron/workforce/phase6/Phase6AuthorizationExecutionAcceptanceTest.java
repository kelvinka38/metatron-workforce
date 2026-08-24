package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase6AuthorizationExecutionAcceptanceTest {
    private static final Instant REQUESTED = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant APPROVED = Instant.parse("2026-01-01T10:05:00Z");
    private static final Instant STARTED = Instant.parse("2026-01-01T10:10:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-01-01T10:11:00Z");

    private WorkProposal proposal() {
        return new WorkProposal("proposal-1", "worker-1", "work-1", "publish", "team-a", "ctx-1", REQUESTED);
    }

    private ApprovalDecision approval(boolean approved) {
        return new ApprovalDecision("decision-1", "proposal-1", "head-1", approved, APPROVED, "authority-head-publish", approved ? "approved" : "rejected");
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("worker-1", "publisher", "authority-head-publish", "team-a", "publish", "ctx-1", STARTED, "resource-1");
    }

    @Test
    void proposalAndApprovalRemainExplicitAndAttributable() {
        var proposal = proposal();
        var decision = approval(true);
        assertEquals("proposal-1", decision.proposalId());
        assertEquals("head-1", decision.approverId());
        assertTrue(decision.approved());
    }

    @Test
    void rejectedProposalCannotBeAuthorized() {
        var service = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-1"));
        var result = service.authorize(proposal(), approval(false), request());
        assertFalse(result.allowed());
        assertEquals("proposal not approved", result.reason());
    }

    @Test
    void authorizationRequiresActorScopeActionAndContextAlignment() {
        var service = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-1"));
        var mismatched = new AuthorizationRequest("worker-2", "publisher", "authority-head-publish", "team-a", "publish", "ctx-1", STARTED, "resource-1");
        var result = service.authorize(proposal(), approval(true), mismatched);
        assertFalse(result.allowed());
        assertEquals("actor-mismatch", result.reason());
    }

    @Test
    void externalPolicyRemainsAuthoritativeAndCannotBeBypassed() {
        var service = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.denied("policy-7", "resource not authorized by policy"));
        var result = service.authorize(proposal(), approval(true), request());
        assertFalse(result.allowed());
        assertEquals("policy-7", result.authorizationReference());
        assertEquals("resource not authorized by policy", result.reason());
    }

    @Test
    void authorizedExecutionPreservesAuthorizationAndInputsOutputs() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-1"));
        var service = new ExecutionService();
        var record = service.execute(proposal(), approval(true), request(), auth,
                p -> ExecutionService.ExecutionResult.success(List.of("draft"), List.of("published")),
                "execution-1", STARTED, COMPLETED);

        assertEquals(ExecutionRecord.Status.SUCCEEDED, record.status());
        assertEquals("worker-1", record.actorId());
        assertEquals("auth-1", record.authorizationReference());
        assertEquals(List.of("draft"), record.inputs());
        assertEquals(List.of("published"), record.outputs());
    }

    @Test
    void executionFailureIsRecordedRatherThanConvertedIntoSuccess() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-2"));
        var record = new ExecutionService().execute(proposal(), approval(true), request(), auth,
                p -> ExecutionService.ExecutionResult.failure(List.of("draft"), "downstream unavailable"),
                "execution-2", STARTED, COMPLETED);

        assertEquals(ExecutionRecord.Status.FAILED, record.status());
        assertEquals("downstream unavailable", record.failureReason());
        assertEquals("auth-2", record.authorizationReference());
    }

    @Test
    void unauthorizedExecutionFailsClosed() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.denied("policy-9", "forbidden"));
        assertThrows(SecurityException.class, () -> new ExecutionService().execute(
                proposal(), approval(true), request(), auth,
                p -> ExecutionService.ExecutionResult.success(List.of(), List.of("must-not-run")),
                "execution-3", STARTED, COMPLETED));
    }

    @Test
    void attributionChainPreservesHumanDecisionWorkerExecutionAndOutcome() {
        var chain = new AttributionChain(List.of(
                new AttributionChain.Entry("human-1", AttributionChain.Entry.Kind.HUMAN_INSTRUCTION, "instruction-1", REQUESTED),
                new AttributionChain.Entry("head-1", AttributionChain.Entry.Kind.DECISION, "decision-1", APPROVED),
                new AttributionChain.Entry("worker-1", AttributionChain.Entry.Kind.WORKER_EXECUTION, "execution-1", STARTED),
                new AttributionChain.Entry("system", AttributionChain.Entry.Kind.OUTCOME, "outcome-1", COMPLETED)));

        assertEquals(4, chain.entries().size());
        assertEquals(AttributionChain.Entry.Kind.HUMAN_INSTRUCTION, chain.entries().get(0).kind());
        assertEquals(AttributionChain.Entry.Kind.DECISION, chain.entries().get(1).kind());
        assertEquals(AttributionChain.Entry.Kind.WORKER_EXECUTION, chain.entries().get(2).kind());
        assertEquals(AttributionChain.Entry.Kind.OUTCOME, chain.entries().get(3).kind());
        assertThrows(UnsupportedOperationException.class, () -> chain.entries().add(chain.entries().get(0)));
    }
}
