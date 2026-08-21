package com.metatron.workforce.phase4;

import java.time.Instant;
import java.util.Objects;

/**
 * Workforce-side representation of an attributable organizational escalation.
 *
 * <p>An escalation records the operational condition and its routing context.
 * It does not itself create authority or authorize the requested action.</p>
 */
public record Escalation(
        String escalationId,
        String originatingWorkerId,
        String workContextId,
        String organizationContextId,
        Category category,
        String reason,
        Urgency urgency,
        String evidenceReference,
        Instant createdAt,
        State state,
        String routeTargetWorkerId,
        String routeAuthorityReference,
        Instant routedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        String responseReference) {

    public Escalation {
        requireText(escalationId, "escalationId");
        requireText(originatingWorkerId, "originatingWorkerId");
        requireText(workContextId, "workContextId");
        requireText(organizationContextId, "organizationContextId");
        Objects.requireNonNull(category, "category");
        requireText(reason, "reason");
        Objects.requireNonNull(urgency, "urgency");
        requireText(evidenceReference, "evidenceReference");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");

        if (routeTargetWorkerId == null && routeAuthorityReference != null) {
            throw new IllegalArgumentException("routeAuthorityReference requires routeTargetWorkerId");
        }
        if (routeTargetWorkerId != null && routeAuthorityReference == null) {
            throw new IllegalArgumentException("routeTargetWorkerId requires routeAuthorityReference");
        }
        if (routedAt != null && routedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("routedAt must not precede createdAt");
        }
        if (acknowledgedAt != null && routedAt == null) {
            throw new IllegalArgumentException("acknowledgedAt requires routedAt");
        }
        if (acknowledgedAt != null && acknowledgedAt.isBefore(routedAt)) {
            throw new IllegalArgumentException("acknowledgedAt must not precede routedAt");
        }
        if (resolvedAt != null && acknowledgedAt == null) {
            throw new IllegalArgumentException("resolvedAt requires acknowledgedAt");
        }
        if (resolvedAt != null && resolvedAt.isBefore(acknowledgedAt)) {
            throw new IllegalArgumentException("resolvedAt must not precede acknowledgedAt");
        }
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(createdAt) && resolvedAt == null;
    }

    public boolean terminal() {
        return state == State.RESOLVED
                || state == State.REJECTED
                || state == State.DEFERRED
                || state == State.UNRESOLVED;
    }

    public enum Category {
        BLOCKED_WORK,
        INSUFFICIENT_AUTHORITY,
        RESOURCE_SHORTAGE,
        CAPACITY_SHORTAGE,
        CONFLICT,
        EXCEPTION,
        RISK,
        POLICY_AMBIGUITY
    }

    public enum Urgency {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }

    public enum State {
        OPEN,
        ROUTING,
        ROUTED,
        ACKNOWLEDGED,
        RESOLVING,
        RESOLVED,
        REJECTED,
        DEFERRED,
        UNRESOLVED
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
