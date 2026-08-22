package com.metatron.workforce.phase8;

import java.util.List;
import java.util.Objects;

public final class WorkforceLearningService {

    public WorkforcePracticeCandidate proposePattern(
            String id,
            List<String> workerEvidence,
            String pattern) {

        Objects.requireNonNull(id);
        Objects.requireNonNull(workerEvidence);
        Objects.requireNonNull(pattern);

        if (workerEvidence.size() < 2) {
            throw new IllegalArgumentException(
                    "Workforce practice requires evidence from multiple workers");
        }

        return new WorkforcePracticeCandidate(
                id,
                List.copyOf(workerEvidence),
                pattern,
                null,
                false);
    }

    public WorkforcePracticeCandidate validate(
            WorkforcePracticeCandidate candidate,
            String validationEvidence) {

        Objects.requireNonNull(candidate);
        return candidate.validate(validationEvidence);
    }
}
