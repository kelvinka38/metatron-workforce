package com.metatron.workforce.runtime;

import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Canonical server-side authority for repository authentication. Workers, model clients,
 * planners, workspaces and sandboxes never own the repository credential.
 */
@Component
public final class RepositoryCredentialAuthority {
    public static final String UNAVAILABLE = "REPOSITORY_CONTROL_PLANE_UNAVAILABLE";
    private static final String ENVIRONMENT_KEY = "GITHUB_TOKEN";
    private final String githubToken;

    public RepositoryCredentialAuthority(@Value("${GITHUB_TOKEN:}") String githubToken) {
        this.githubToken = normalize(githubToken);
    }

    public boolean provisioned() { return !githubToken.isBlank(); }

    /** Server-side compatibility value for Repository Control Plane adapters only. */
    public String tokenOrEmpty() { return githubToken; }

    public String requireToken() {
        if (githubToken.isBlank()) {
            throw new GovernanceDeniedException(UNAVAILABLE, "repository credential unavailable");
        }
        return githubToken;
    }

    /** Compatibility bridge for legacy non-Spring repository readers. */
    public static String resolveProcessToken() {
        return normalize(System.getenv(ENVIRONMENT_KEY));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
