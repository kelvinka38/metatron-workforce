package com.metatron.workforce.execution;

import java.util.Objects;

/**
 * A fail-closed pre-execution reading requirement.
 *
 * Guidance constrains execution context but does not create authority.
 * publicResourcePath must resolve inside the Workforce runtime publication.
 */
public record GuidanceRequirement(
        String guidanceId,
        String publicResourcePath,
        String sourceRef
) {
    public GuidanceRequirement {
        guidanceId = require(guidanceId, "guidanceId");
        publicResourcePath = require(publicResourcePath, "publicResourcePath");
        sourceRef = require(sourceRef, "sourceRef");
        if (!publicResourcePath.startsWith("/public/docs/")) {
            throw new IllegalArgumentException("guidance must use /public/docs publication");
        }
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
