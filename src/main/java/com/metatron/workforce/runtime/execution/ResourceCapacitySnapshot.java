package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.Objects;

public record ResourceCapacitySnapshot(
        ResourceClaim.ResourceClass resourceClass,
        String resourceId,
        double total,
        double used,
        double reserved,
        double available,
        Instant observedAt,
        long version) {
    public ResourceCapacitySnapshot {
        Objects.requireNonNull(resourceClass);
        if(resourceId==null||resourceId.isBlank()) throw new IllegalArgumentException("resourceId required");
        if(total<0||used<0||reserved<0||available<0||version<1) throw new IllegalArgumentException("invalid capacity snapshot");
        Objects.requireNonNull(observedAt);
    }
}
