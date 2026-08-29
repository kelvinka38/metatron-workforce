package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Semantic declaration of an exact arithmetic operation. Operands are data inputs, not authority/evidence.
 * The deterministic engine validates numeric syntax and performs the calculation without an LLM.
 */
public record DeterministicComputationSpec(
        String label,
        DeterministicComputationOperation operation,
        List<String> operands,
        String unit) {

    public DeterministicComputationSpec {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(operands, "operands");
        Objects.requireNonNull(unit, "unit");
        operands = List.copyOf(operands);
        if (label.isBlank()) throw new IllegalArgumentException("computation label must not be blank");
        if (operands.isEmpty()) throw new IllegalArgumentException("computation operands must not be empty");
    }
}
