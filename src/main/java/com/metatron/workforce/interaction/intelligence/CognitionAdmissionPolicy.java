package com.metatron.workforce.interaction.intelligence;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Fail-closed compute-ownership admission for Intelligence requests. */
public final class CognitionAdmissionPolicy {
    public Set<IntelligenceComputeOwner> allowedComputeOwners(IntelligenceOriginContext origin) {
        Objects.requireNonNull(origin, "origin");
        return switch (origin.originType()) {
            case WORKER -> Set.of(IntelligenceComputeOwner.METATRON_OWNED);
            case HUMAN -> EnumSet.allOf(IntelligenceComputeOwner.class);
            case SYSTEM, MEETING -> Set.of(IntelligenceComputeOwner.METATRON_OWNED);
        };
    }

    public void requireAllowed(IntelligenceOriginContext origin, IntelligenceComputeOwner owner) {
        Objects.requireNonNull(owner, "owner");
        if (!allowedComputeOwners(origin).contains(owner)) {
            throw new SecurityException("WORKER_EXTERNAL_INFERENCE_DENIED origin=" + origin.originType()
                    + " compute_owner=" + owner);
        }
    }
}
