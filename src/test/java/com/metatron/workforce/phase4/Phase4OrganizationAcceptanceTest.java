package com.metatron.workforce.phase4;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class Phase4OrganizationAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Phase4AuthorizationPolicy allowAll() {
        return (actor, target, action, scope, context, at) ->
                Phase4AuthorizationPolicy.AuthorizationDecision.allowed("auth-" + actor + "-" + action);
    }

    @Test
    void provesMultipleOrganizationLevelsPositionRoleAndWorkerOccupancy() {
        OrganizationService service = new OrganizationService(allowAll());
        service.addUnit("human-1", unit("institution", null, OrganizationUnit.UnitType.INSTITUTION), NOW);
        service.addUnit("human-1", unit("org", "institution", OrganizationUnit.UnitType.ORGANIZATION), NOW);
        service.addUnit("human-1", unit("department", "org", OrganizationUnit.UnitType.DEPARTMENT), NOW);
        service.addUnit("human-1", unit("team", "department", OrganizationUnit.UnitType.TEAM), NOW);
        service.addRole("human-1", new Role("role-head", "Head", "WORKFORCE_MANAGEMENT"), "team", NOW);
        service.occupy("human-1", new Position("pos-head", "team", "role-head", "worker-head",
                NOW, null, "human-1", "authority-org", "evidence-org"), NOW);

        assertEquals(4, service.units().size());
        assertEquals("department", service.units().stream().filter(u -> u.unitId().equals("team"))
                .findFirst().orElseThrow().parentUnitId());
        assertEquals("worker-head", service.positions().get(0).workerId());
        assertEquals("role-head", service.positions().get(0).roleId());
    }

    @Test
    void provesTypedReportingAndCrossTeamCoordinationAreDistinct() {
        OrganizationService service = new OrganizationService(allowAll());
        service.addRelationship("human-1", relationship("rel-report", "worker-sub", "worker-head",
                OrganizationRelationship.RelationshipType.REPORTS_TO, NOW, null), NOW);
        service.addRelationship("human-1", relationship("rel-manage", "worker-head", "worker-director",
                OrganizationRelationship.RelationshipType.MANAGES, NOW, null), NOW);
        service.addRelationship("human-1", relationship("rel-coordinate", "head-tech", "head-people",
                OrganizationRelationship.RelationshipType.COORDINATES_WITH, NOW, null), NOW);

        assertEquals(1, service.reportingTo("worker-sub", NOW).size());
        assertEquals(OrganizationRelationship.RelationshipType.REPORTS_TO,
                service.reportingTo("worker-sub", NOW).get(0).type());
        assertEquals(1, service.coordinationFor("head-tech", NOW).size());
        assertTrue(service.reportingTo("head-tech", NOW).isEmpty());
    }

    @Test
    void provesBoundedDelegationAndAuthorityLimits() {
        OrganizationService service = new OrganizationService(allowAll());
        Delegation delegation = new Delegation("deleg-1", "worker-head", "worker-delegatee",
                "authority-approve", "APPROVE_WORK,ESCALATION:*", "team", NOW,
                NOW.plusSeconds(3600), "human-1", "evidence-delegation");
        service.addDelegation("worker-head", delegation, "APPROVE_WORK,ESCALATION:*", NOW);

        assertTrue(service.delegated("worker-delegatee", "APPROVE_WORK", "team", NOW));
        assertTrue(service.delegated("worker-delegatee", "ESCALATION:BLOCKED_WORK", "team", NOW));
        assertFalse(service.delegated("worker-delegatee", "SPEND_FUNDS", "team", NOW));
        assertFalse(service.delegated("worker-delegatee", "APPROVE_WORK", "team", NOW.plusSeconds(3600)));

        Delegation narrow = new Delegation("deleg-2", "worker-head", "worker-limited",
                "authority-approve", "APPROVE_WORK", "team", NOW, null, "human-1", "evidence-narrow");
        assertThrows(SecurityException.class,
                () -> service.addDelegation("worker-head", narrow, "REPORT_ONLY", NOW));
    }

    @Test
    void provesEscalationRoutesFromRealReportingRelationship() {
        OrganizationService organization = new OrganizationService(allowAll());
        organization.addRelationship("human-1", relationship("rel-route", "worker-sub", "worker-head",
                OrganizationRelationship.RelationshipType.REPORTS_TO, NOW, null), NOW);
        EscalationService service = new EscalationService(organization, allowAll(), CLOCK);

        Escalation escalation = service.create("worker-sub", "esc-1", "worker-sub", "assignment-42", "team",
                Escalation.Category.BLOCKED_WORK, "Resource unavailable", Escalation.Urgency.HIGH, "evidence-42");
        escalation = service.route(escalation);
        escalation = service.acknowledge(escalation, "worker-head");
        escalation = service.resolve(escalation, "worker-head", "decision-42");

        assertEquals(Escalation.State.RESOLVED, escalation.state());
        assertEquals("worker-head", escalation.routeTargetWorkerId());
        assertEquals("authority-report", escalation.routeAuthorityReference());
        assertEquals("decision-42", escalation.responseReference());
        assertTrue(escalation.resolvedAt().isAfter(escalation.createdAt()));
    }

    @Test
    void provesExplicitRoutePrecedesReportingAndNeverCreatesExecutionAuthority() {
        OrganizationService organization = new OrganizationService(allowAll());
        organization.addRelationship("human-1", relationship("rel-route", "worker-sub", "worker-head",
                OrganizationRelationship.RelationshipType.REPORTS_TO, NOW, null), NOW);
        EscalationService service = new EscalationService(organization, allowAll(), CLOCK);
        service.configureRoute("team", "exception-authority", "authority-exception");

        Escalation escalation = service.create("worker-sub", "esc-2", "worker-sub", "work-2", "team",
                Escalation.Category.POLICY_AMBIGUITY, "Policy is unclear", Escalation.Urgency.NORMAL, "evidence-2");
        escalation = service.route(escalation);

        assertEquals(Escalation.State.ROUTED, escalation.state());
        assertEquals("exception-authority", escalation.routeTargetWorkerId());
        assertEquals("authority-exception", escalation.routeAuthorityReference());
    }

    @Test
    void provesMissingRouteBecomesExplicitUnresolvedState() {
        OrganizationService organization = new OrganizationService(allowAll());
        EscalationService service = new EscalationService(organization, allowAll(), CLOCK);
        Escalation escalation = service.create("worker-orphan", "esc-3", "worker-orphan", "work-3", "isolated-team",
                Escalation.Category.INSUFFICIENT_AUTHORITY, "No authority route exists", Escalation.Urgency.CRITICAL, "evidence-3");

        escalation = service.route(escalation);
        assertEquals(Escalation.State.UNRESOLVED, escalation.state());
        assertNull(escalation.routeTargetWorkerId());
        assertEquals("worker-orphan", escalation.originatingWorkerId());
        assertEquals("evidence-3", escalation.evidenceReference());
    }

    @Test
    void provesTemporalRelationshipHistoryIsPreserved() {
        OrganizationService service = new OrganizationService(allowAll());
        Instant ended = NOW.plusSeconds(3600);
        OrganizationRelationship relationship = relationship("rel-temporal", "worker-a", "worker-b",
                OrganizationRelationship.RelationshipType.SUPERVISES, NOW, ended);
        service.addRelationship("human-1", relationship, NOW);

        assertEquals(1, service.relationshipsAt(NOW).size());
        assertTrue(service.relationshipsAt(NOW.plusSeconds(3599)).size() == 1);
        assertTrue(service.relationshipsAt(ended).isEmpty());
        assertEquals(ended, service.relationships().get(0).endedAt());
    }

    @Test
    void rejectsUnauthorizedOrganizationalMutation() {
        Phase4AuthorizationPolicy deny = (actor, target, action, scope, context, at) ->
                Phase4AuthorizationPolicy.AuthorizationDecision.denied("auth-denied", "not authorized");
        OrganizationService service = new OrganizationService(deny);

        assertThrows(SecurityException.class,
                () -> service.addRelationship("worker-untrusted", relationship("rel-denied", "a", "b",
                        OrganizationRelationship.RelationshipType.MANAGES, NOW, null), NOW));
        assertTrue(service.relationships().isEmpty());
    }

    private static OrganizationUnit unit(String id, String parent, OrganizationUnit.UnitType type) {
        return new OrganizationUnit(id, parent, type, id, NOW, null, "human-1", "authority-org", "evidence-org");
    }

    private static OrganizationRelationship relationship(String id, String source, String target,
                                                         OrganizationRelationship.RelationshipType type,
                                                         Instant effective, Instant ended) {
        String authority = type == OrganizationRelationship.RelationshipType.REPORTS_TO
                ? "authority-report" : "authority-relationship";
        return new OrganizationRelationship(id, source, target, type, "team", effective, ended,
                "human-1", authority, "evidence-" + id);
    }
}
