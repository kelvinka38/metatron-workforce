package com.metatron.workforce.runtime.execution;

import java.util.List;
import java.util.Map;

public record ResourceAssessment(boolean grantable, List<ResourceClaim> claims, Map<String,String> blockers) {
    public ResourceAssessment {
        claims = claims == null ? List.of() : List.copyOf(claims);
        blockers = blockers == null ? Map.of() : Map.copyOf(blockers);
    }
}
