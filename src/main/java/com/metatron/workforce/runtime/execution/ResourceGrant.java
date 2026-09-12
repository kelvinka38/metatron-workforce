package com.metatron.workforce.runtime.execution;

import java.util.List;

public record ResourceGrant(String attemptId, long attemptFencingToken, List<ResourceLease> leases) {
    public ResourceGrant {
        if(attemptId==null||attemptId.isBlank()) throw new IllegalArgumentException("attemptId required");
        if(attemptFencingToken<1) throw new IllegalArgumentException("attemptFencingToken must be positive");
        leases = leases == null ? List.of() : List.copyOf(leases);
    }
}
