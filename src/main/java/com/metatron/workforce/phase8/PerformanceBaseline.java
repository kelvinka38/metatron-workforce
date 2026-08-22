package com.metatron.workforce.phase8;

import java.util.Objects;

public record PerformanceBaseline(
        String id,
        String metric,
        double value,
        String direction,
        String evidenceReference) {

    public PerformanceBaseline {
        Objects.requireNonNull(id);
        Objects.requireNonNull(metric);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(evidenceReference);

        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Baseline value must be finite.");
        }
        if (!direction.equals("HIGHER_IS_BETTER")
                && !direction.equals("LOWER_IS_BETTER")) {
            throw new IllegalArgumentException("Unsupported metric direction.");
        }
    }
}
