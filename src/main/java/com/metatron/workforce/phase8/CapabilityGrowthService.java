package com.metatron.workforce.phase8;

import java.util.Objects;

public final class CapabilityGrowthService {

    public CapabilityCandidate proposeCapability(
            String id,
            String workerId,
            String behavior,
            String evidence) {

        return CapabilityCandidate.propose(id, workerId, behavior, evidence);
    }

    public CapabilityCandidate qualify(
            CapabilityCandidate candidate,
            String validationEvidence) {

        Objects.requireNonNull(candidate);
        return candidate.validate(validationEvidence);
    }
}
