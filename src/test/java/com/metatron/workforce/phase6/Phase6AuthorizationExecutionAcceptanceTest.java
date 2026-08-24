package com.metatron.workforce.phase6;

import com.metatron.workforce.phase5.ExecutionFeasibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private ExecutionFeasibility feasible() {
        return new ExecutionFeasibility(ExecutionFeasibility.Status.FEASIBLE, 0, 0, List.of());
    }

    @Test
    void proposalAndApprovalRemainExplicitAndAttributable() {
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
    void authorizationCannotBecomeEffectiveBeforeApprovalTime() {
        var service = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-1"));
        // After proposal creation but before approval: this isolates approval effectiveness.
        var early = new AuthorizationRequest("worker-1", "publisher", "authority-head-publish", "team-a", "publish", "ctx-1", APPROVED.minusSeconds(1), "resource-1");
        var result = service.authorize(proposal(), approval(true), early);
        assertFalse(result.allowed());
        assertEquals("approval-not-effective", result.reason());
    }

    @Test
    void externalPolicyRemainsAuthoritativeAndCannotBeBypassed() {
        var service = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.denied("policy-7", "resource not authorized by policy"));
        var result = service.authorize(proposal(), approval(true), request());
        assertFalse(result.allowed());
        assertEquals("policy-7", result.authorizationReference());
    }

    @Test
    void authorizedExecutionPreservesAuthorizationAndInputsOutputs() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-1"));
        var record = new ExecutionService().execute(proposal(), approval(true), request(), auth, feasible(),
                p -> ExecutionService.ExecutionResult.success(List.of("draft"), List.of("published")),
                "execution-1", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.SUCCEEDED, record.status());
        assertEquals("worker-1", record.actorId());
        assertEquals("auth-1", record.authorizationReference());
        assertEquals(List.of("draft"), record.inputs());
        assertEquals(List.of("published"), record.outputs());
    }

    @Test
    void executionFailureIsRecordedRatherThanConvertedIntoSuccess() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-2"));
        var record = new ExecutionService().execute(proposal(), approval(true), request(), auth, feasible(),
                p -> ExecutionService.ExecutionResult.failure(List.of("draft"), "downstream unavailable"),
                "execution-2", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.FAILED, record.status());
        assertEquals("downstream unavailable", record.failureReason());
        assertEquals("auth-2", record.authorizationReference());
    }

    @Test
    void authorizationAndRealityAreBothAdmissionBoundaries() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-3"));
        var blocked = new ExecutionFeasibility(ExecutionFeasibility.Status.BLOCKED, 0, 0, List.of("resource-withdrawn"));
        var record = new ExecutionService().execute(proposal(), approval(true), request(), auth, blocked,
                p -> fail("executor must not run"), "execution-3", STARTED, COMPLETED, null);
        assertEquals(ExecutionRecord.Status.BLOCKED, record.status());
        assertEquals("resource-withdrawn", record.failureReason());
        assertEquals("auth-3", record.authorizationReference());
    }

    @Test
    void partialRealityCannotBecomeFullExecution() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.allowed("auth-4"));
        var partial = new ExecutionFeasibility(ExecutionFeasibility.Status.PARTIAL, 128, 4, List.of());
        var record = new ExecutionService().execute(proposal(), approval(true), request(), auth, partial,
                p -> fail("executor must not run"), "execution-4", STARTED, COMPLETED, null);
        assertEquals(ExecutionRecord.Status.PARTIAL, record.status());
    }

    @Test
    void materialAuthorizationChangeRequiresRevalidation() {
        var allowed = new AtomicBoolean(true);
        var auth = new AuthorizationService(req -> allowed.get()
                ? Phase6AuthorizationPolicy.Decision.allowed("auth-5")
                : Phase6AuthorizationPolicy.Decision.denied("auth-revoked", "authority revoked"));
        assertTrue(new ExecutionService().revalidate(proposal(), approval(true), request(), auth).allowed());
        allowed.set(false);
        var result = new ExecutionService().revalidate(proposal(), approval(true), request(), auth);
        assertFalse(result.allowed());
        assertEquals("auth-revoked", result.authorizationReference());
    }

    @Test
    void retryAndCancellationRemainDistinctHistoricalStates() {
        var retry = new ExecutionRecord("execution-6b", "proposal-1", "worker-1", "publish", STARTED, COMPLETED,
                "ctx-1", "auth-6", List.of("draft"), List.of(), ExecutionRecord.Status.FAILED, "timeout", "execution-6a");
        var cancelled = new ExecutionRecord("execution-7", "proposal-1", "worker-1", "publish", STARTED, COMPLETED,
                "ctx-1", "auth-7", List.of(), List.of(), ExecutionRecord.Status.CANCELLED, null, null);
        assertEquals("execution-6a", retry.retryOfExecutionId());
        assertEquals(ExecutionRecord.Status.CANCELLED, cancelled.status());
        assertNotEquals(retry.executionId(), retry.retryOfExecutionId());
    }

    @Test
    void attributionChainPreservesHumanDecisionAuthorizationAssignmentRuntimeAndOutcome() {
        var chain = new AttributionChain(List.of(
                new AttributionChain.Entry("human-1", AttributionChain.Entry.Kind.HUMAN_INSTRUCTION, "instruction-1", REQUESTED),
                new AttributionChain.Entry("head-1", AttributionChain.Entry.Kind.DECISION, "decision-1", APPROVED),
                new AttributionChain.Entry("policy", AttributionChain.Entry.Kind.AUTHORIZATION, "auth-1", APPROVED.plusSeconds(1)),
                new AttributionChain.Entry("head-1", AttributionChain.Entry.Kind.ASSIGNMENT, "assignment-1", APPROVED.plusSeconds(2)),
                new AttributionChain.Entry("worker-1", AttributionChain.Entry.Kind.WORKER_EXECUTION, "execution-1", STARTED),
                new AttributionChain.Entry("runtime-1", AttributionChain.Entry.Kind.RUNTIME, "runtime-1", STARTED.plusSeconds(1)),
                new AttributionChain.Entry("system", AttributionChain.Entry.Kind.OUTCOME, "outcome-1", COMPLETED)));

        assertEquals(7, chain.entries().size());
        assertEquals(AttributionChain.Entry.Kind.HUMAN_INSTRUCTION, chain.entries().get(0).kind());
        assertEquals(AttributionChain.Entry.Kind.OUTCOME, chain.entries().get(6).kind());
        assertThrows(UnsupportedOperationException.class, () -> chain.entries().add(chain.entries().get(0)));
        assertThrows(IllegalArgumentException.class, () -> new AttributionChain(List.of(
                new AttributionChain.Entry("a", AttributionChain.Entry.Kind.DECISION, "1", APPROVED),
                new AttributionChain.Entry("b", AttributionChain.Entry.Kind.OUTCOME, "2", REQUESTED))));
    }

    @Test
    void unauthorizedExecutionFailsClosed() {
        var auth = new AuthorizationService(req -> Phase6AuthorizationPolicy.Decision.denied("policy-9", "forbidden"));
        assertThrows(SecurityException.class, () -> new ExecutionService().execute(proposal(), approval(true), request(), auth, feasible(),
                p -> ExecutionService.ExecutionResult.success(List.of(), List.of("must-not-run")),
                "execution-9", STARTED, COMPLETED, null));
    }
}
