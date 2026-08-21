package com.metatron.workforce.phase4;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DelegationTest {

    private static final Instant EFFECTIVE =
            Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant EXPIRES =
            Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void createsBoundedDelegationWithAuthorityScopeContextAndEvidence() {
        Delegation delegation = new Delegation(
                "delegation-001",
                "worker-head",
                "worker-subordinate",
                "authority-001",
                "approve-work",
                "org-context-001",
                EFFECTIVE,
                EXPIRES,
                "actor-001",
                "evidence-001");

        assertEquals("delegation-001", delegation.delegationId());
        assertEquals("worker-head", delegation.delegatorWorkerId());
        assertEquals("worker-subordinate", delegation.delegateeWorkerId());
        assertEquals("authority-001", delegation.authorityReference());
        assertEquals("approve-work", delegation.scope());
        assertEquals("org-context-001", delegation.organizationContextId());
        assertEquals("actor-001", delegation.initiatedBy());
        assertEquals("evidence-001", delegation.evidenceReference());
    }

    @Test
    void delegationIsActiveOnlyInsideItsEffectiveWindow() {
        Delegation delegation = delegation();

        assertFalse(delegation.activeAt(
                Instant.parse("2025-12-31T23:59:59Z")));
        assertTrue(delegation.activeAt(EFFECTIVE));
        assertTrue(delegation.activeAt(
                Instant.parse("2026-01-15T00:00:00Z")));
        assertFalse(delegation.activeAt(EXPIRES));
    }

    @Test
    void openEndedDelegationRemainsActiveAfterEffectiveTime() {
        Delegation delegation = new Delegation(
                "delegation-002",
                "worker-head",
                "worker-subordinate",
                "authority-001",
                "approve-work",
                "org-context-001",
                EFFECTIVE,
                null,
                "actor-001",
                "evidence-001");

        assertTrue(delegation.activeAt(
                Instant.parse("2027-01-01T00:00:00Z")));
    }

    @Test
    void rejectsSelfDelegation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Delegation(
                        "delegation-003",
                        "worker-a",
                        "worker-a",
                        "authority-001",
                        "approve-work",
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "actor-001",
                        "evidence-001"));
    }

    @Test
    void rejectsExpiryBeforeEffectiveTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Delegation(
                        "delegation-004",
                        "worker-a",
                        "worker-b",
                        "authority-001",
                        "approve-work",
                        "org-context-001",
                        EXPIRES,
                        EFFECTIVE,
                        "actor-001",
                        "evidence-001"));
    }

    @Test
    void rejectsMissingRequiredDelegationEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Delegation(
                        "delegation-005",
                        "worker-a",
                        "worker-b",
                        "authority-001",
                        "approve-work",
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "",
                        "evidence-001"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Delegation(
                        "delegation-006",
                        "worker-a",
                        "worker-b",
                        "authority-001",
                        "",
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "actor-001",
                        "evidence-001"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new Delegation(
                        "delegation-007",
                        "worker-a",
                        "worker-b",
                        "authority-001",
                        "approve-work",
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "actor-001",
                        ""));
    }

    private static Delegation delegation() {
        return new Delegation(
                "delegation-001",
                "worker-head",
                "worker-subordinate",
                "authority-001",
                "approve-work",
                "org-context-001",
                EFFECTIVE,
                EXPIRES,
                "actor-001",
                "evidence-001");
    }
}
