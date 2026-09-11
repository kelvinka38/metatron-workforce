package com.metatron.workforce.runtime;

import java.util.Locale;
import java.util.Set;

/** Canonical repository scope approved for the current Metatron institutional repository control plane. */
public final class CanonicalRepositoryScope {
    public static final Set<String> REPOSITORIES = Set.of(
            "kelvinka38/universal",
            "kelvinka38/metatron-institution",
            "kelvinka38/metatron-workforce",
            "kelvinka38/bios");

    private CanonicalRepositoryScope() {}

    public static boolean allowed(String repository) {
        if (repository == null) return false;
        String value = normalize(repository);
        return REPOSITORIES.contains(value);
    }

    public static String requireAllowed(String repository) {
        String value = normalize(repository);
        if (!REPOSITORIES.contains(value)) {
            throw new SecurityException("repository-outside-canonical-scope:" + value);
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
