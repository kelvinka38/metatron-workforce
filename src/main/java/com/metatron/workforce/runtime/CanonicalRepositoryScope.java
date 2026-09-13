package com.metatron.workforce.runtime;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical repository identifier boundary for the Repository Control Plane.
 *
 * <p>This class intentionally performs syntax/normalization checks only. Repository authorization
 * is decided server-side by the provisioned GitHub credential when the control plane resolves or
 * materializes the repository. Workers, MCP/model clients and sandboxes never receive that
 * credential.</p>
 */
public final class CanonicalRepositoryScope {
    private static final Pattern REPOSITORY = Pattern.compile(
            "^[a-z0-9][a-z0-9_.-]{0,99}/[a-z0-9][a-z0-9_.-]{0,99}$");

    private CanonicalRepositoryScope() {}

    /** Compatibility name retained for existing call sites; this is no longer a static allowlist. */
    public static boolean allowed(String repository) {
        try {
            requireAllowed(repository);
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    /**
     * Require a safe GitHub owner/repository identifier. Access is checked later by the server-side
     * Repository Control Plane credential, not by a hard-coded repository list.
     */
    public static String requireAllowed(String repository) {
        String value = normalize(repository);
        if (value.length() > 200 || !REPOSITORY.matcher(value).matches()
                || value.contains("..") || value.contains("//")) {
            throw new SecurityException("repository-identifier-invalid:" + value);
        }
        return value;
    }

    public static String normalize(String repository) {
        String value = repository == null ? "" : repository.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
