package com.metatron.workforce.phase8;

import java.util.List;
import java.util.Objects;

public record WorkforcePracticeCandidate(
        String id,
        List<String> workerEvidence,
        String pattern,
        String validationEvidence,
        boolean validated) {

    public WorkforcePracticeCandidate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(workerEvidence);
        Objects.requireNonNull(pattern);

        if (workerEvidence.size() < 2) {
            throw new IllegalArgumentException(
                    "A workforce-level pattern requires evidence from at least two Workers.");
        }

        if (validated && (validationEvidence == null || validationEvidence.isBlank())) {
            throw new IllegalArgumentException(
                    "Validated workforce practice requires validation evidence.");
        }
    }

    public WorkforcePracticeCandidate validate(String evidence) {
        Objects.requireNonNull(evidence);
        if (evidence.isBlank()) {
            throw new IllegalArgumentException("Validation evidence is required.");
        }

        return new WorkforcePracticeCandidate(
                id,
                workerEvidence,
                pattern,
                evidence,
                true);
    }
    public boolean isQualified() {
        return validationEvidence != null;
    }
}
