package com.metatron.workforce.phase4;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationRelationshipTest {

    private static final Instant EFFECTIVE =
            Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant ENDED =
            Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void createsReportingRelationshipWithAttribution() {
        OrganizationRelationship relationship =
                new OrganizationRelationship(
                        "rel-001",
                        "worker-head",
                        "worker-subordinate",
                        OrganizationRelationship.RelationshipType.REPORTS_TO,
                        "org-context-001",
                        EFFECTIVE,
                        ENDED,
                        "actor-001",
                        "authority-001",
                        "evidence-001");

        assertEquals("rel-001", relationship.relationshipId());
        assertEquals("worker-head", relationship.sourceWorkerId());
        assertEquals("worker-subordinate", relationship.targetWorkerId());
        assertEquals(OrganizationRelationship.RelationshipType.REPORTS_TO, relationship.type());
        assertEquals("org-context-001", relationship.organizationContextId());
        assertEquals("actor-001", relationship.initiatedBy());
        assertEquals("authority-001", relationship.authorityReference());
        assertEquals("evidence-001", relationship.evidenceReference());
    }

    @Test
    void relationshipIsActiveWithinItsEffectiveWindow() {
        OrganizationRelationship relationship =
                new OrganizationRelationship(
                        "rel-002",
                        "worker-a",
                        "worker-b",
                        OrganizationRelationship.RelationshipType.MANAGES,
                        "org-context-001",
                        EFFECTIVE,
                        ENDED,
                        "actor-001",
                        "authority-001",
                        "evidence-001");

        assertFalse(relationship.activeAt(Instant.parse("2025-12-31T23:59:59Z")));
        assertTrue(relationship.activeAt(Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(relationship.activeAt(Instant.parse("2026-01-15T00:00:00Z")));
        assertFalse(relationship.activeAt(Instant.parse("2026-02-01T00:00:00Z")));
    }

    @Test
    void openEndedRelationshipRemainsActiveAfterEffectiveTime() {
        OrganizationRelationship relationship =
                new OrganizationRelationship(
                        "rel-003",
                        "worker-a",
                        "worker-b",
                        OrganizationRelationship.RelationshipType.COORDINATES_WITH,
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "actor-001",
                        "authority-001",
                        "evidence-001");

        assertTrue(relationship.activeAt(Instant.parse("2027-01-01T00:00:00Z")));
    }

    @Test
    void rejectsSelfRelationship() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrganizationRelationship(
                        "rel-004",
                        "worker-a",
                        "worker-a",
                        OrganizationRelationship.RelationshipType.REPORTS_TO,
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "actor-001",
                        "authority-001",
                        "evidence-001"));
    }

    @Test
    void rejectsEndBeforeEffectiveTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrganizationRelationship(
                        "rel-005",
                        "worker-a",
                        "worker-b",
                        OrganizationRelationship.RelationshipType.SUPERVISES,
                        "org-context-001",
                        ENDED,
                        EFFECTIVE,
                        "actor-001",
                        "authority-001",
                        "evidence-001"));
    }

    @Test
    void rejectsMissingAttributionAndEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrganizationRelationship(
                        "rel-006",
                        "worker-a",
                        "worker-b",
                        OrganizationRelationship.RelationshipType.ADVISES,
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "",
                        "authority-001",
                        "evidence-001"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OrganizationRelationship(
                        "rel-007",
                        "worker-a",
                        "worker-b",
                        OrganizationRelationship.RelationshipType.ADVISES,
                        "org-context-001",
                        EFFECTIVE,
                        null,
                        "actor-001",
                        "authority-001",
                        ""));
    }

    @Test
    void supportsDistinctRelationshipSemantics() {
        assertEquals(6, OrganizationRelationship.RelationshipType.values().length);

        assertNotEquals(
                OrganizationRelationship.RelationshipType.REPORTS_TO,
                OrganizationRelationship.RelationshipType.MANAGES);
        assertNotEquals(
                OrganizationRelationship.RelationshipType.MANAGES,
                OrganizationRelationship.RelationshipType.SUPERVISES);
        assertNotEquals(
                OrganizationRelationship.RelationshipType.ADVISES,
                OrganizationRelationship.RelationshipType.COORDINATES_WITH);
    }
}
