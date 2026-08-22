package com.metatron.workforce.phase10;

import java.util.Objects;

public record VerticalSliceResult(
        VerticalSliceRequest request,
        FarmOperatingPlan plan,
        Approval approval,
        ExecutionOutcome execution,
        CycleReport report,
        LearningImprovement learning,
        FarmOperatingPlan nextPlan,
        String reviewStatus) {
    public VerticalSliceResult {
        Objects.requireNonNull(request);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(approval);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(report);
        Objects.requireNonNull(learning);
        Objects.requireNonNull(nextPlan);
        Objects.requireNonNull(reviewStatus);
    }
}
