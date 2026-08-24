package com.metatron.workforce.phase5;

import java.util.List;
import java.util.Objects;

public record ExecutionFeasibility(
        Status status,
        double laborDeficit,
        int qualifiedStaffingDeficit,
        List<String> blockingReasons) {

    public ExecutionFeasibility {
        Objects.requireNonNull(status, "status");
        if (laborDeficit < 0 || !Double.isFinite(laborDeficit)) throw new IllegalArgumentException("laborDeficit must be finite and non-negative");
        if (qualifiedStaffingDeficit < 0) throw new IllegalArgumentException("qualifiedStaffingDeficit must be non-negative");
        blockingReasons = List.copyOf(Objects.requireNonNull(blockingReasons, "blockingReasons"));
    }

    public enum Status { FEASIBLE, PARTIAL, BLOCKED }
}
