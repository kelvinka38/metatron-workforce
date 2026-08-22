package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {
    private static final Instant EFFECTIVE = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-08-20T10:00:00Z");

    private AuthorizationRequest request() {
        return new AuthorizationRequest(
                "req-001", "worker-001", "head", "EXECUTE_WORK", "farm-A",
                "org-A", "authority-001", "delegation-001", "policy-001",
                DECIDED, EFFECTIVE, EXPIRES, "evidence-001");
    }

    @Test
    void allowsOnlyWhenPolicyAllowsAndRequestIsTemporallyValid() {
        AuthorizationDecision decision = new AuthorizationService().resolve(
                request(), DECIDED, "head-001", ignored -> true);

        assertEquals(AuthorizationDecision.Outcome.ALLOW, decision.outcome());
        assertTrue(decision.usableAt(DECIDED));
        assertEquals("authority-001", decision.authorityReference());
        assertEquals("delegation-001", decision.delegationReference());
    }

    @Test
    void deniesWhenPolicyRejects() {
        AuthorizationDecision decision = new AuthorizationService().resolve(
                request(), DECIDED, "head-001", ignored -> false);

        assertEquals(AuthorizationDecision.Outcome.DENY, decision.outcome());
        assertFalse(decision.usableAt(DECIDED));
    }

    @Test
    void deniesOutsideValidityWindow() {
        Instant afterExpiry = Instant.parse("2026-09-01T00:00:00Z");
        AuthorizationDecision decision = new AuthorizationService().resolve(
                request(), afterExpiry, "head-001", ignored -> true);

        assertEquals(AuthorizationDecision.Outcome.DENY, decision.outcome());
        assertFalse(decision.usableAt(afterExpiry));
    }

    @Test
    void reviewAndDeferRemainDistinctFromDeny() {
        AuthorizationService service = new AuthorizationService();

        assertEquals(AuthorizationDecision.Outcome.REVIEW,
                service.review(request(), DECIDED, "head-001", "dual approval required").outcome());
        assertEquals(AuthorizationDecision.Outcome.DEFER,
                service.defer(request(), DECIDED, "head-001", "budget confirmation pending").outcome());
    }
}
