package com.metatron.workforce.phase11;

import java.util.List;
import java.util.Objects;

/**
 * Aggregates evidence produced by Phase 11 acceptance tests into an explicit
 * hardening gate result. It does not manufacture evidence or replace domain
 * authorities; callers must supply observed acceptance outcomes.
 */
public final class Phase11HardeningService {

    public HardeningAssessment assess(
            boolean authorization,
            boolean organization,
            boolean capacity,
            boolean economic,
            boolean communication,
            boolean execution,
            boolean learning,
            boolean recovery,
            List<String> criticalFailures) {
        Objects.requireNonNull(criticalFailures, "criticalFailures");
        List<String> failures = List.copyOf(criticalFailures);
        return new HardeningAssessment(
                authorization,
                organization,
                capacity,
                economic,
                communication,
                execution,
                learning,
                recovery,
                failures);
    }

    public record HardeningAssessment(
            boolean authorization,
            boolean organization,
            boolean capacity,
            boolean economic,
            boolean communication,
            boolean execution,
            boolean learning,
            boolean recovery,
            List<String> criticalFailures) {

        public HardeningAssessment {
            Objects.requireNonNull(criticalFailures, "criticalFailures");
            criticalFailures = List.copyOf(criticalFailures);
        }

        public boolean allMandatoryPass() {
            return authorization
                    && organization
                    && capacity
                    && economic
                    && communication
                    && execution
                    && learning
                    && recovery
                    && criticalFailures.isEmpty();
        }
    }
}
