package com.metatron.workforce.phase4;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EscalationTest {

    private static final Instant CREATED =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant ROUTED =
            Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant ACKNOWLEDGED =
            Instant.parse("2026-01-01T02:00:00Z");
    private static final Instant RESOLVED =
            Instant.parse("2026-01-01T03:00:00Z");

    @Test
    void createsAttributableEscalationWithOperationalContext() {
        Escalation escalation = new Escalation(
                "esc-001",
                "worker-001",
                "assignment-001",
                "org-context-001",
                Escalation.Category.BLOCKED_WORK,
                "Required resource is unavailable.",
                Escalation.Urgency.HIGH,
                "evidence-001",
                CREATED,
                Escalation.State.OPEN,
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals("esc-001", escalation.escalationId());
        assertEquals("worker-001", escalation.originatingWorkerId());
        assertEquals("assignment-001", escalation.workContextId());
        assertEquals("org-context-001", escalation.organizationContextId());
        assertEquals(Escalation.Category.BLOCKED_WORK, escalation.category());
        assertEquals(Escalation.Urgency.HIGH, escalation.urgency());
        assertEquals("evidence-001", escalation.evidenceReference());
        assertEquals(Escalation.State.OPEN, escalation.state());
    }

    @Test
    void supportsAllGateG4CategoriesAndUrgencies() {
        assertEquals(8, Escalation.Category.values().length);
        assertEquals(4, Escalation.Urgency.values().length);
        assertEquals(9, Escalation.State.values().length);
    }

    @Test
    void preservesBoundedRoutingTimeline() {
        Escalation escalation = new Escalation(
                "esc-002",
                "worker-001",
                "work-001",
                "org-context-001",
                Escalation.Category.INSUFFICIENT_AUTHORITY,
                "Worker lacks authority for the requested action.",
                Escalation.Urgency.NORMAL,
                "evidence-002",
                CREATED,
                Escalation.State.RESOLVING,
                "worker-head",
                "authority-001",
                ROUTED,
                ACKNOWLEDGED,
                null,
                null);

        assertEquals("worker-head", escalation.routeTargetWorkerId());
        assertEquals("authority-001", escalation.routeAuthorityReference());
        assertEquals(ROUTED, escalation.routedAt());
        assertEquals(ACKNOWLEDGED, escalation.acknowledgedAt());
        assertNull(escalation.resolvedAt());
        assertFalse(escalation.terminal());
        assertTrue(escalation.activeAt(ACKNOWLEDGED));
    }

    @Test
    void terminalResolutionPreservesResponseReference() {
        Escalation escalation = new Escalation(
                "esc-003",
                "worker-001",
                "work-001",
                "org-context-001",
                Escalation.Category.RISK,
                "Material operational risk requires review.",
                Escalation.Urgency.CRITICAL,
                "evidence-003",
                CREATED,
                Escalation.State.RESOLVED,
                "worker-head",
                "authority-001",
                ROUTED,
                ACKNOWLEDGED,
                RESOLVED,
                "response-001");

        assertTrue(escalation.terminal());
        assertEquals(RESOLVED, escalation.resolvedAt());
        assertEquals("response-001", escalation.responseReference());
        assertFalse(escalation.activeAt(RESOLVED));
    }

    @Test
    void rejectsAuthorityReferenceWithoutRouteTarget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Escalation(
                        "esc-004",
                        "worker-001",
                        "work-001",
                        "org-context-001",
                        Escalation.Category.POLICY_AMBIGUITY,
                        "Policy interpretation is unclear.",
                        Escalation.Urgency.NORMAL,
                        "evidence-004",
                        CREATED,
                        Escalation.State.ROUTING,
                        null,
                        "authority-001",
                        null,
                        null,
                        null,
                        null));
    }

    @Test
    void rejectsRouteTargetWithoutAuthorityReference() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Escalation(
                        "esc-005",
                        "worker-001",
                        "work-001",
                        "org-context-001",
                        Escalation.Category.CONFLICT,
                        "Workers disagree on the applicable operational decision.",
                        Escalation.Urgency.HIGH,
                        "evidence-005",
                        CREATED,
                        Escalation.State.ROUTED,
                        "worker-head",
                        null,
                        ROUTED,
                        null,
                        null,
                        null));
    }

    @Test
    void rejectsInvalidTemporalOrdering() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Escalation(
                        "esc-006",
                        "worker-001",
                        "work-001",
                        "org-context-001",
                        Escalation.Category.RESOURCE_SHORTAGE,
                        "Required material is unavailable.",
                        Escalation.Urgency.HIGH,
                        "evidence-006",
                        CREATED,
                        Escalation.State.ACKNOWLEDGED,
                        "worker-head",
                        "authority-001",
                        CREATED.minusSeconds(1),
                        null,
                        null,
                        null));
    }

    @Test
    void requiresAcknowledgementBeforeResolution() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Escalation(
                        "esc-007",
                        "worker-001",
                        "work-001",
                        "org-context-001",
                        Escalation.Category.EXCEPTION,
                        "Execution encountered an exception.",
                        Escalation.Urgency.CRITICAL,
                        "evidence-007",
                        CREATED,
                        Escalation.State.RESOLVED,
                        "worker-head",
                        "authority-001",
                        ROUTED,
                        null,
                        RESOLVED,
                        "response-007"));
    }
}
