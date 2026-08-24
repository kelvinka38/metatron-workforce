package com.metatron.workforce.phase4;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Routes organizational escalations without granting authority through routing alone. */
public final class EscalationService {
    private final OrganizationService organization;
    private final Phase4AuthorizationPolicy policy;
    private final Clock clock;
    private final Map<String, Route> explicitRoutes = new HashMap<>();

    public EscalationService(OrganizationService organization, Phase4AuthorizationPolicy policy, Clock clock) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void configureRoute(String organizationContextId, String targetWorkerId, String authorityReference) {
        explicitRoutes.put(organizationContextId, new Route(targetWorkerId, authorityReference));
    }

    public Escalation create(String actorId, String escalationId, String originatingWorkerId, String workContextId,
                             String organizationContextId, Escalation.Category category, String reason,
                             Escalation.Urgency urgency, String evidenceReference) {
        Instant now = clock.instant();
        authorize(actorId, originatingWorkerId, "RAISE_ESCALATION", category.name(), organizationContextId, now);
        return new Escalation(escalationId, originatingWorkerId, workContextId, organizationContextId,
                category, reason, urgency, evidenceReference, now, Escalation.State.OPEN,
                null, null, null, null, null, null);
    }

    public Escalation route(Escalation escalation) {
        Instant now = clock.instant();
        if (escalation.terminal()) throw new IllegalStateException("terminal escalation cannot be routed");
        Route route = resolve(escalation, now);
        if (route == null) {
            return copy(escalation, Escalation.State.UNRESOLVED, null, null, null, null, null, null);
        }
        authorize(escalation.originatingWorkerId(), route.targetWorkerId(), "RECEIVE_ESCALATION",
                escalation.category().name(), escalation.organizationContextId(), now);
        return copy(escalation, Escalation.State.ROUTED, route.targetWorkerId(), route.authorityReference(),
                now, null, null, null);
    }

    public Escalation acknowledge(Escalation escalation, String actorId) {
        Instant now = clock.instant();
        requireState(escalation, Escalation.State.ROUTED);
        authorize(actorId, escalation.routeTargetWorkerId(), "ACKNOWLEDGE_ESCALATION",
                escalation.category().name(), escalation.organizationContextId(), now);
        return copy(escalation, Escalation.State.ACKNOWLEDGED, escalation.routeTargetWorkerId(),
                escalation.routeAuthorityReference(), escalation.routedAt(), now, null, null);
    }

    public Escalation resolve(Escalation escalation, String actorId, String responseReference) {
        Instant now = clock.instant();
        requireState(escalation, Escalation.State.ACKNOWLEDGED, Escalation.State.RESOLVING);
        authorize(actorId, escalation.routeTargetWorkerId(), "RESOLVE_ESCALATION",
                escalation.category().name(), escalation.organizationContextId(), now);
        return copy(escalation, Escalation.State.RESOLVED, escalation.routeTargetWorkerId(),
                escalation.routeAuthorityReference(), escalation.routedAt(), escalation.acknowledgedAt(), now, responseReference);
    }

    private Route resolve(Escalation escalation, Instant at) {
        Route explicit = explicitRoutes.get(escalation.organizationContextId());
        if (explicit != null) return explicit;

        for (OrganizationRelationship.RelationshipType type : new OrganizationRelationship.RelationshipType[]{
                OrganizationRelationship.RelationshipType.REPORTS_TO,
                OrganizationRelationship.RelationshipType.MANAGES,
                OrganizationRelationship.RelationshipType.SUPERVISES}) {
            for (OrganizationRelationship relationship : organization.relationshipsAt(at)) {
                if (relationship.sourceWorkerId().equals(escalation.originatingWorkerId())
                        && relationship.organizationContextId().equals(escalation.organizationContextId())
                        && relationship.type() == type) {
                    return new Route(relationship.targetWorkerId(), relationship.authorityReference());
                }
            }
        }

        String requested = "ESCALATION:" + escalation.category().name();
        return organization.delegations().stream()
                .filter(d -> d.organizationContextId().equals(escalation.organizationContextId())
                        && d.delegatorWorkerId().equals(escalation.originatingWorkerId())
                        && d.activeAt(at)
                        && OrganizationService.scopeWithin(d.scope(), requested))
                .findFirst()
                .map(d -> new Route(d.delegateeWorkerId(), d.authorityReference()))
                .orElse(null);
    }

    private void authorize(String actorId, String targetId, String action, String scope, String context, Instant at) {
        Phase4AuthorizationPolicy.AuthorizationDecision decision = policy.authorize(actorId, targetId, action, scope, context, at);
        if (!decision.allowed()) throw new SecurityException(decision.reason());
    }

    private static void requireState(Escalation escalation, Escalation.State... allowed) {
        for (Escalation.State state : allowed) if (escalation.state() == state) return;
        throw new IllegalStateException("invalid escalation state: " + escalation.state());
    }

    private static Escalation copy(Escalation e, Escalation.State state, String target, String authority,
                                   Instant routed, Instant acknowledged, Instant resolved, String response) {
        return new Escalation(e.escalationId(), e.originatingWorkerId(), e.workContextId(), e.organizationContextId(),
                e.category(), e.reason(), e.urgency(), e.evidenceReference(), e.createdAt(), state,
                target, authority, routed, acknowledged, resolved, response);
    }

    private record Route(String targetWorkerId, String authorityReference) {
        Route {
            if (targetWorkerId == null || targetWorkerId.isBlank()) throw new IllegalArgumentException("targetWorkerId must not be blank");
            if (authorityReference == null || authorityReference.isBlank()) throw new IllegalArgumentException("authorityReference must not be blank");
        }
    }
}
