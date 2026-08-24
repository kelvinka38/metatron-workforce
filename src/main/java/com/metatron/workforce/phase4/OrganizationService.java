package com.metatron.workforce.phase4;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Coordinates Workforce-side organizational structure without becoming the authority domain. */
public final class OrganizationService {
    private final Phase4AuthorizationPolicy policy;
    private final List<OrganizationUnit> units = new ArrayList<>();
    private final List<Role> roles = new ArrayList<>();
    private final List<Position> positions = new ArrayList<>();
    private final List<OrganizationRelationship> relationships = new ArrayList<>();
    private final List<Delegation> delegations = new ArrayList<>();

    public OrganizationService(Phase4AuthorizationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public OrganizationUnit addUnit(String actorId, OrganizationUnit unit, Instant at) {
        authorize(actorId, unit.unitId(), "CREATE_UNIT", "ORGANIZATION_STRUCTURE", unit.unitId(), at);
        units.add(unit);
        return unit;
    }

    public Role addRole(String actorId, Role role, String organizationContextId, Instant at) {
        authorize(actorId, role.roleId(), "CREATE_ROLE", "ROLE_DEFINITION", organizationContextId, at);
        roles.add(role);
        return role;
    }

    public Position occupy(String actorId, Position position, Instant at) {
        authorize(actorId, position.workerId(), "OCCUPY_POSITION", position.roleId(), position.organizationUnitId(), at);
        positions.add(position);
        return position;
    }

    public OrganizationRelationship addRelationship(String actorId, OrganizationRelationship relationship, Instant at) {
        authorize(actorId, relationship.targetWorkerId(), relationship.type().name(), "RELATIONSHIP", relationship.organizationContextId(), at);
        relationships.add(relationship);
        return relationship;
    }

    public Delegation addDelegation(String actorId, Delegation delegation, String delegatorScope, Instant at) {
        authorize(actorId, delegation.delegateeWorkerId(), "DELEGATE", delegation.scope(), delegation.organizationContextId(), at);
        if (!scopeWithin(delegatorScope, delegation.scope())) {
            throw new SecurityException("delegation scope exceeds delegator authority scope");
        }
        delegations.add(delegation);
        return delegation;
    }

    public boolean delegated(String workerId, String action, String organizationContextId, Instant at) {
        return delegations.stream().anyMatch(d -> d.delegateeWorkerId().equals(workerId)
                && d.organizationContextId().equals(organizationContextId)
                && d.activeAt(at)
                && scopeWithin(d.scope(), action));
    }

    public List<OrganizationRelationship> relationshipsAt(Instant at) {
        return relationships.stream().filter(r -> r.activeAt(at)).toList();
    }

    public List<OrganizationRelationship> reportingTo(String workerId, Instant at) {
        return relationships.stream().filter(r -> r.activeAt(at)
                && r.sourceWorkerId().equals(workerId)
                && (r.type() == OrganizationRelationship.RelationshipType.REPORTS_TO
                    || r.type() == OrganizationRelationship.RelationshipType.MANAGES
                    || r.type() == OrganizationRelationship.RelationshipType.SUPERVISES)).toList();
    }

    public List<OrganizationRelationship> coordinationFor(String workerId, Instant at) {
        return relationships.stream().filter(r -> r.activeAt(at)
                && r.type() == OrganizationRelationship.RelationshipType.COORDINATES_WITH
                && (r.sourceWorkerId().equals(workerId) || r.targetWorkerId().equals(workerId))).toList();
    }

    public List<OrganizationUnit> units() { return List.copyOf(units); }
    public List<Role> roles() { return List.copyOf(roles); }
    public List<Position> positions() { return List.copyOf(positions); }
    public List<OrganizationRelationship> relationships() { return List.copyOf(relationships); }
    public List<Delegation> delegations() { return List.copyOf(delegations); }

    private void authorize(String actorId, String targetId, String action, String scope, String context, Instant at) {
        Phase4AuthorizationPolicy.AuthorizationDecision decision = policy.authorize(actorId, targetId, action, scope, context, at);
        if (!decision.allowed()) throw new SecurityException(decision.reason());
    }

    static boolean scopeWithin(String grantedScope, String requestedAction) {
        if (grantedScope == null || grantedScope.isBlank() || requestedAction == null || requestedAction.isBlank()) return false;
        String requested = requestedAction.trim();
        for (String token : grantedScope.split(",")) {
            String normalized = token.trim();
            if (normalized.equals("*") || normalized.equals(requested)) return true;
            if (normalized.endsWith(".*") && requested.startsWith(normalized.substring(0, normalized.length() - 2))) return true;
        }
        return false;
    }
}
