package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {
    private static final Instant REQUESTED = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant APPROVED = Instant.parse("2026-08-20T10:05:00Z");
    private static final Instant AUTHORIZED = Instant.parse("2026-08-20T10:10:00Z");

    private WorkProposal proposal() {
        return new WorkProposal("proposal-001", "worker-001", "work-001", "EXECUTE_WORK", "farm-A", "org-A", REQUESTED);
    }

    private ApprovalDecision approval(boolean approved) {
        return new ApprovalDecision("decision-001", "proposal-001", "head-001", approved, APPROVED,
                "authority-001", approved ? "approved" : "rejected");
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("worker-001", "publisher", "authority-001", "farm-A", "EXECUTE_WORK",
                "org-A", AUTHORIZED, "resource-001");
    }

    @Test
    void allowsWhenApprovalAndPolicyAllow() {
        AuthorizationService service = new AuthorizationService(
                ignored -> Phase6AuthorizationPolicy.Decision.allowed("auth-001"));

        AuthorizationService.AuthorizationResult result = service.authorize(proposal(), approval(true), request());

        assertTrue(result.allowed());
        assertEquals("auth-001", result.authorizationReference());
    }

    @Test
    void deniesWhenApprovalRejects() {
        AuthorizationService service = new AuthorizationService(
                ignored -> Phase6AuthorizationPolicy.Decision.allowed("auth-001"));

        AuthorizationService.AuthorizationResult result = service.authorize(proposal(), approval(false), request());

        assertFalse(result.allowed());
        assertEquals("proposal not approved", result.reason());
    }

    @Test
    void deniesWhenExternalPolicyRejects() {
        AuthorizationService service = new AuthorizationService(
                ignored -> Phase6AuthorizationPolicy.Decision.denied("policy-001", "resource not authorized"));

        AuthorizationService.AuthorizationResult result = service.authorize(proposal(), approval(true), request());

        assertFalse(result.allowed());
        assertEquals("policy-001", result.authorizationReference());
    }

    @Test
    void deniesWhenActorActionScopeOrContextDoesNotMatchProposal() {
        AuthorizationService service = new AuthorizationService(
                ignored -> Phase6AuthorizationPolicy.Decision.allowed("auth-001"));

        AuthorizationRequest actorMismatch = new AuthorizationRequest(
                "worker-002", "publisher", "authority-001", "farm-A", "EXECUTE_WORK", "org-A", AUTHORIZED, "resource-001");
        AuthorizationService.AuthorizationResult result = service.authorize(proposal(), approval(true), actorMismatch);

        assertFalse(result.allowed());
        assertEquals("actor-mismatch", result.reason());
    }

    @Test
    void deniesWhenAuthorizationPredatesProposal() {
        AuthorizationService service = new AuthorizationService(
                ignored -> Phase6AuthorizationPolicy.Decision.allowed("auth-001"));

        AuthorizationRequest early = new AuthorizationRequest(
                "worker-001", "publisher", "authority-001", "farm-A", "EXECUTE_WORK", "org-A", REQUESTED.minusSeconds(1), "resource-001");
        AuthorizationService.AuthorizationResult result = service.authorize(proposal(), approval(true), early);

        assertFalse(result.allowed());
        assertEquals("time-invalid", result.reason());
    }
}
