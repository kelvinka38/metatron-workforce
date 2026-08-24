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
        // This is intentionally after proposal creation but before approval, so the
        // failure is specifically approval effectiveness rather than proposal chronology.
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
