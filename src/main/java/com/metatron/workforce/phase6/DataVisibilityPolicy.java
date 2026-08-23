package com.metatron.workforce.phase6;

import java.util.Objects;

/**
 * Minimal production-boundary visibility rule: data is visible only inside
 * the organization context that owns the authorization scope.
 */
public final class DataVisibilityPolicy {

    public Decision evaluate(String requesterOrganizationId, String resourceOrganizationId) {
        Objects.requireNonNull(requesterOrganizationId, "requesterOrganizationId");
        Objects.requireNonNull(resourceOrganizationId, "resourceOrganizationId");

        boolean visible = requesterOrganizationId.equals(resourceOrganizationId);
        return new Decision(
                visible,
                visible ? "organization context matches resource owner"
                        : "organization context does not match resource owner");
    }

    public record Decision(boolean visible, String reason) {}
}
