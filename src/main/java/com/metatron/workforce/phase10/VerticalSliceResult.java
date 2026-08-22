package com.metatron.workforce.phase10;

import java.util.Objects;

public record VerticalSliceResult(
        VerticalSliceRequest request,
        Assignment assignment,
        FarmOperatingPlan plan,
        Approval approval,
        ExecutionOutcome execution,
        CycleReport report,
        EconomicSliceEvidence economicEvidence,
        LearningImprovement learning,
        FarmOperatingPlan nextPlan,
        String reviewStatus) {
    public VerticalSliceResult {
        Objects.requireNonNull(request);
        Objects.requireNonNull(assignment);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(approval);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(report);
        Objects.requireNonNull(economicEvidence);
        Objects.requireNonNull(learning);
        Objects.requireNonNull(nextPlan);
        Objects.requireNonNull(reviewStatus);
    }
}
