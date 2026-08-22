package com.metatron.workforce.phase8;

import java.util.Objects;

public final class Phase8ImprovementService {

    public ImprovementMeasurement measure(
            PerformanceBaseline baseline,
            double newPerformance) {

        return ImprovementMeasurement.measure(baseline, newPerformance);
    }

    public CapabilityCandidate proposeCapability(
            String id,
            String workerId,
            String behavior,
            String evidence) {

        return CapabilityCandidate.propose(
                id,
                workerId,
                behavior,
                evidence);
    }

    public WorkforcePracticeCandidate proposeWorkforcePractice(
            String id,
            java.util.List<String> workerEvidence,
            String pattern) {

        return new WorkforcePracticeCandidate(
                id,
                workerEvidence,
                pattern,
                null,
                false);
    }

    public CapabilityCandidate qualifyCapability(
            CapabilityCandidate candidate,
            String validationEvidence) {

        Objects.requireNonNull(candidate);
        return candidate.validate(validationEvidence);
    }

    public WorkforcePracticeCandidate validateWorkforcePractice(
            WorkforcePracticeCandidate candidate,
            String validationEvidence) {

        Objects.requireNonNull(candidate);
        return candidate.validate(validationEvidence);
    }
}
