package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.Set;
import java.util.Objects;

public record ResourceFencingState(
        String resourceId,
        long currentFencingToken,
        long stateVersion,
        Set<String> activeLeaseIds,
        Instant updatedAt) {
    public ResourceFencingState {
        if(resourceId==null||resourceId.isBlank()) throw new IllegalArgumentException("resourceId required");
        if(currentFencingToken<0||stateVersion<1) throw new IllegalArgumentException("invalid resource fencing state");
        activeLeaseIds=activeLeaseIds==null?Set.of():Set.copyOf(activeLeaseIds);
        Objects.requireNonNull(updatedAt);
    }
}
