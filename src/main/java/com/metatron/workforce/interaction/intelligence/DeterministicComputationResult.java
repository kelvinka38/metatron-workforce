package com.metatron.workforce.interaction.intelligence;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact deterministic calculation output. */
public record DeterministicComputationResult(
        String label,
        DeterministicComputationOperation operation,
        BigDecimal value,
        String unit,
        String expression) {

    public DeterministicComputationResult {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(expression, "expression");
    }
}
